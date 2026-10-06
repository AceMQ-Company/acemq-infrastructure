#!/usr/bin/env bash
# The operator, end to end, on a throwaway kind cluster it creates and deletes.
#
#   ./scripts/operator-e2e.sh             build, create the cluster, run, delete it
#   ./scripts/operator-e2e.sh --keep      leave the cluster up afterwards, to look
#   ./scripts/operator-e2e.sh --no-build  use the images already built
#   ./scripts/operator-e2e.sh --reuse     run again on a cluster an earlier --keep left
#   ./scripts/operator-e2e.sh --native    the same runs against the native image
#                                         (Dockerfile.native) instead of the JVM one
#
# Three runs against two real RabbitmqClusters, blue and green, made by the
# RabbitMQ Cluster Operator (pinned below, with the cert-manager it needs)
# inside the throwaway cluster:
#
#   1. cutover   a backlog on blue with a consumer attached; a Cutover is
#                planned, an approval for the wrong plan is refused, the right
#                one runs it. Every message is accounted for by id: handled by
#                the consumer on blue, or on green, or both — the atLeastOnce
#                duplicate, counted and held to what the close step reported.
#   2. rollback  a second Cutover, completed, then spec.action: rollback. The
#                estate ends where it started.
#   3. restart   the operator pod is killed (no grace) while the drain step
#                runs. The new one must mark the Cutover Interrupted, name the
#                step, and run nothing; then a rollback from there.
#   4. retained  a completed Cutover is deleted. Its journal must outlive it,
#                detached and labelled; a new Cutover adopts it through
#                spec.journalFrom and rolls the estate back.
#   5. refusals  a Secret without the infra.acemq.org/credentials label, and a
#                management URL off the operator's allowlist, are both refused
#                into status and nothing runs.
#   6. hooks     the operator as deploy/operator.yaml ships it, with
#                ACEMQ_INFRA_ALLOW_HOOKS=false: a Cutover with a hook endpoint
#                is Refused, with no plan and no journal.
#
# Runs 1 to 5 use a hook endpoint (/bin/true), so the operator is deployed with
# ACEMQ_INFRA_ALLOW_HOOKS=true: the harness opting in, as an estate would.
#
# Only the cluster this creates is touched: it is `kind-$CLUSTER` and nothing
# else, and it is deleted on exit unless --keep. Requires docker, kind, kubectl,
# jq and Maven.
set -euo pipefail

CLUSTER="${E2E_KIND_CLUSTER:-acemq-infra-e2e}"
CTX="kind-$CLUSTER"
NS=acemq-infra-e2e
OPERATOR_NS=acemq-infra-system
CLUSTER_OPERATOR=v2.23.0
CERT_MANAGER=v1.21.2   # the Cluster Operator's manifest has needed it since v2.22
OPERATOR_IMAGE=acemq-infra-operator:dev
DOCKERFILE=Dockerfile
CLIENT_IMAGE=acemq-infra-e2e-client:dev
QUEUE=orders.new
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HERE="$ROOT/scripts/operator-e2e"
WORK="$(mktemp -d)"

KEEP=0
BUILD=1
REUSE=0
for argument in "$@"; do
  case "$argument" in
    --keep) KEEP=1 ;;
    --no-build) BUILD=0 ;;
    --reuse) REUSE=1; KEEP=1 ;;
    --native) OPERATOR_IMAGE=acemq-infra-operator:dev-native; DOCKERFILE=Dockerfile.native ;;
    *) echo "unknown option: $argument" >&2; exit 2 ;;
  esac
done

k() { kubectl --context "$CTX" "$@"; }
say() { printf '\n== %s\n' "$*"; }
fail() {
  echo "FAIL: $*" >&2
  k -n "$NS" get cutovers -o wide >&2 || true
  k -n "$OPERATOR_NS" logs deploy/acemq-infra-operator --tail=80 >&2 || true
  exit 1
}
expect() { # description actual expected
  if [[ "$2" == "$3" ]]; then echo "  ok   $1: $2"; else fail "$1: got '$2', expected '$3'"; fi
}

cleanup() {
  local status=$?
  rm -rf "$WORK"
  if [[ $KEEP == 1 ]]; then
    echo "kept: kind cluster $CLUSTER (kind delete cluster --name $CLUSTER)"
  else
    kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
    echo "deleted: kind cluster $CLUSTER"
  fi
  exit $status
}

# ------------------------------------------------------------------ setup

if [[ $BUILD == 1 ]]; then
  say "build"
  # clean: target/lib is copied into the image whole, and a jar a dependency
  # change dropped would otherwise still be in it.
  (cd "$ROOT" && mvn -B -q --no-transfer-progress -pl acemq-infra-operator -am clean package \
      -DskipTests -DskipITs)
  docker build -q -f "$ROOT/acemq-infra-operator/$DOCKERFILE" -t "$OPERATOR_IMAGE" \
    "$ROOT/acemq-infra-operator" >/dev/null
  docker build -q -t "$CLIENT_IMAGE" "$HERE" >/dev/null
fi

# With --no-build, E2E_OPERATOR_IMAGE runs an image built some other way — for
# instance a JVM one under GraalVM's tracing agent, to collect metadata.
[[ $BUILD == 0 && -n "${E2E_OPERATOR_IMAGE:-}" ]] && OPERATOR_IMAGE="$E2E_OPERATOR_IMAGE"
trap cleanup EXIT
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  [[ $REUSE == 1 ]] || { KEEP=1; fail "a kind cluster named $CLUSTER already exists; this script only uses one it created (--reuse to run on it again)"; }
  say "reusing kind cluster $CLUSTER"
  k -n "$NS" delete cutovers --all --ignore-not-found >/dev/null 2>&1 || true
  # Journals an earlier run kept on purpose (run 4) would refuse this run's.
  k -n "$NS" delete configmaps -l app.kubernetes.io/managed-by=acemq-infra-operator >/dev/null 2>&1 || true
  k -n "$NS" delete pod consumer --ignore-not-found >/dev/null 2>&1 || true
else
  say "kind cluster $CLUSTER"
  kind create cluster --name "$CLUSTER" --wait 180s
fi
kind load docker-image "$OPERATOR_IMAGE" "$CLIENT_IMAGE" --name "$CLUSTER"

say "cert-manager $CERT_MANAGER, RabbitMQ Cluster Operator $CLUSTER_OPERATOR, blue and green"
k apply -f "https://github.com/cert-manager/cert-manager/releases/download/$CERT_MANAGER/cert-manager.yaml" >/dev/null
for deployment in cert-manager cert-manager-webhook cert-manager-cainjector; do
  k -n cert-manager rollout status "deploy/$deployment" --timeout=5m
done
# Retried: cert-manager's webhook answers a little after its Deployment is ready.
for attempt in $(seq 1 12); do
  k apply -f "https://github.com/rabbitmq/cluster-operator/releases/download/$CLUSTER_OPERATOR/cluster-operator.yml" >/dev/null && break
  [[ $attempt == 12 ]] && fail "the Cluster Operator's manifest would not apply"
  sleep 10
done
k -n rabbitmq-system rollout status deploy/rabbitmq-cluster-operator --timeout=5m
k create namespace "$NS" --dry-run=client -o yaml | k apply -f - >/dev/null
rabbitmq_cluster() {
  cat <<EOF
apiVersion: rabbitmq.com/v1beta1
kind: RabbitmqCluster
metadata:
  name: $colour
  namespace: $NS
spec:
  replicas: 1
  resources:
    requests: {cpu: 250m, memory: 600Mi}
    limits: {memory: 1Gi}
  persistence:
    storage: 1Gi
  rabbitmq:
    additionalPlugins: [rabbitmq_shovel, rabbitmq_shovel_management]
EOF
}
# Retried for the same reason: the Cluster Operator's own webhook refuses
# connections for a moment after its Deployment reports ready.
for colour in blue green; do
  for attempt in $(seq 1 12); do
    rabbitmq_cluster | k apply -f - >/dev/null && break
    [[ $attempt == 12 ]] && fail "RabbitmqCluster $colour would not apply"
    sleep 10
  done
done
for colour in blue green; do
  for _ in $(seq 1 120); do
    k -n "$NS" get rabbitmqcluster "$colour" >/dev/null 2>&1 && break; sleep 1
  done
  k -n "$NS" wait --for=condition=AllReplicasReady "rabbitmqcluster/$colour" --timeout=10m
done

# The operator reads only Secrets labelled for it. Not the default-user ones:
# the Cluster Operator rewrites their labels on every reconcile, and a label
# on the RabbitmqCluster would reach its erlang-cookie Secret as well. So the
# Cutovers below name a copy of their own, as docs/operator.md advises.
for colour in blue green; do
  user=$(k -n "$NS" get secret "$colour-default-user" -o jsonpath='{.data.username}' | base64 -d)
  pass=$(k -n "$NS" get secret "$colour-default-user" -o jsonpath='{.data.password}' | base64 -d)
  k -n "$NS" create secret generic "$colour-cutover" --from-literal=username="$user" \
    --from-literal=password="$pass" --dry-run=client -o yaml \
    | k label --local -f - infra.acemq.org/credentials=true -o yaml | k apply -f - >/dev/null
done

say "the operator"
k apply -f "$ROOT/deploy/crd.yaml" >/dev/null
# The manifest names the published image; the run uses the one just built and
# loaded, so nothing is pulled from GHCR. And it refuses hooks, which runs 1 to
# 5 use; run 6 puts that back.
sed -E -e "s#image: ghcr.io/acemq-company/acemq-infra-operator:.*#image: $OPERATOR_IMAGE#" \
  -e '/name: ACEMQ_INFRA_ALLOW_HOOKS/{n;s/"false"/"true"/;}' \
  "$ROOT/deploy/operator.yaml" > "$WORK/operator.yaml"
grep -A1 'name: ACEMQ_INFRA_ALLOW_HOOKS' "$WORK/operator.yaml" | grep -q '"true"' \
  || fail "deploy/operator.yaml no longer sets ACEMQ_INFRA_ALLOW_HOOKS where this expects it"
k apply -f "$WORK/operator.yaml" >/dev/null
[[ $REUSE == 1 ]] && k -n "$OPERATOR_NS" rollout restart deploy/acemq-infra-operator >/dev/null
k -n "$OPERATOR_NS" rollout status deploy/acemq-infra-operator --timeout=5m

# One pod to ask the brokers questions from, and the same image consumes.
client_pod() { # name restartPolicy command...
  local name=$1 policy=$2; shift 2
  local command; command=$(printf '"%s",' "$@"); command="[${command%,}]"
  k apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $name
  namespace: $NS
spec:
  restartPolicy: $policy
  containers:
    - name: client
      image: $CLIENT_IMAGE
      imagePullPolicy: Never
      command: $command
      env:
        - {name: BLUE_USERNAME, valueFrom: {secretKeyRef: {name: blue-default-user, key: username}}}
        - {name: BLUE_PASSWORD, valueFrom: {secretKeyRef: {name: blue-default-user, key: password}}}
        - {name: GREEN_USERNAME, valueFrom: {secretKeyRef: {name: green-default-user, key: username}}}
        - {name: GREEN_PASSWORD, valueFrom: {secretKeyRef: {name: green-default-user, key: password}}}
EOF
}
client_pod toolbox Always sleep 1000000
k -n "$NS" wait --for=condition=Ready pod/toolbox --timeout=2m >/dev/null
client() { k -n "$NS" exec toolbox -- python -u /client.py "$@"; }
depth() { client depth "$1" "$QUEUE" | awk '{print $1}'; }
consumers() { client depth "$1" "$QUEUE" | awk '{print $2}'; }

cutover() { # name [more spec] — a Cutover carrying deployment.yaml, credentials from the Secrets
  {
    cat <<EOF
apiVersion: infra.acemq.org/v1alpha1
kind: Cutover
metadata:
  name: $1
  namespace: $NS
spec:
  variables:
    - {name: BLUE_USERNAME, secretKeyRef: {name: blue-cutover, key: username}}
    - {name: BLUE_PASSWORD, secretKeyRef: {name: blue-cutover, key: password}}
    - {name: GREEN_USERNAME, secretKeyRef: {name: green-cutover, key: username}}
    - {name: GREEN_PASSWORD, secretKeyRef: {name: green-cutover, key: password}}
${2:-}
  deployment: |
EOF
    sed 's/^/    /' "${DEPLOYMENT:-$HERE/deployment.yaml}"
  } | k apply -f - >/dev/null
}
field() { k -n "$NS" get cutover "$1" -o jsonpath="{.status.$2}"; }
wait_phase() { # name phase seconds
  local phase=""
  for _ in $(seq 1 "$3"); do
    phase=$(field "$1" phase)
    [[ "$phase" == "$2" ]] && return 0
    case "$phase" in
      Failed|RollbackFailed|RollbackInterrupted) fail "$1 is $phase, waiting for $2: $(field "$1" message)" ;;
    esac
    sleep 1
  done
  fail "$1 did not reach $2 in $3s (it is '$phase'): $(field "$1" message)"
}
approve() { k -n "$NS" patch cutover "$1" --type merge -p "{\"spec\":{\"approve\":\"$2\"}}" >/dev/null; }
journal() { k -n "$NS" get configmap "$1-journal" -o jsonpath='{.data.journal\.json}'; }
reset_queues() { client purge blue "$QUEUE"; client purge green "$QUEUE"; }

# ------------------------------------------------------------------ 1. cutover

say "1. cutover: a backlog on blue with a consumer attached"
PUBLISHED=3000
reset_queues
client publish blue "$QUEUE" "$PUBLISHED"
client_pod consumer Never python -u /client.py consume blue "$QUEUE"
k -n "$NS" wait --for=condition=Ready pod/consumer --timeout=2m >/dev/null
# Until the management API reports the consumer, and one statistics interval
# more for its channel: the close step finds consuming connections through the
# channel statistics, and an operator quick enough off the mark (the native
# image is) otherwise closes nothing and the unacked wait aborts the run.
for _ in $(seq 1 60); do [[ "$(consumers blue)" == 1 ]] && break; sleep 1; done
expect "the consumer is attached" "$(consumers blue)" 1
sleep 6
cutover e2e-cutover
wait_phase e2e-cutover Planned 180
PLAN=$(field e2e-cutover planFingerprint)
echo "  plan $PLAN:"; field e2e-cutover plan | sed 's/^/    | /'
expect "nothing applied before approval" "$(k -n "$NS" get configmap e2e-cutover-journal 2>/dev/null | wc -l | tr -d ' ')" 0

approve e2e-cutover 0000000000000000
for _ in $(seq 1 60); do
  [[ "$(field e2e-cutover message)" == *"names plan 0000000000000000"* ]] && break; sleep 1
done
expect "a stale approval is refused" "$(field e2e-cutover phase)" Planned
expect "and writes no journal" "$(k -n "$NS" get configmap e2e-cutover-journal 2>/dev/null | wc -l | tr -d ' ')" 0

approve e2e-cutover "$PLAN"
wait_phase e2e-cutover Completed 300
for _ in $(seq 1 60); do
  [[ "$(k -n "$NS" get pod consumer -o jsonpath='{.status.phase}')" =~ Succeeded|Failed ]] && break; sleep 1
done
HANDLED=$(k -n "$NS" logs consumer | sed -n 's/^final handled=//p')
HANDLED_IDS=$(k -n "$NS" logs consumer | sed -n 's/^final ids=//p')
GREEN=$(depth green); BLUE=$(depth blue)
echo "  published $PUBLISHED; handled on blue $HANDLED; on green $GREEN; left on blue $BLUE"
expect "the consumer was closed by the cutover" "$([[ -n "$HANDLED" ]] && echo yes)" yes
expect "blue is drained" "$BLUE" 0
expect "blue has no consumers" "$(consumers blue)" 0
# atLeastOnce, measured rather than assumed: nothing lost, and what was
# duplicated no more than the close step said it could be. The duplicate is the
# message the consumer was handling when the close landed: once the broker has
# sent connection.close it discards the ack, so the message is requeued, moved
# by the drain and on green as well. Nothing can tell it apart from one never
# handled, so it is counted rather than expected away.
GREEN_IDS=$(client ids green "$QUEUE")
expect "every message on green listed" "$(jq length <<<"$GREEN_IDS")" "$GREEN"
ACCOUNT=$(jq -n --argjson p "$PUBLISHED" --argjson handled "$HANDLED_IDS" --argjson green "$GREEN_IDS" '
  ($handled + $green) as $all
  | {missing: ([range(0; $p)] - $all | length),
     duplicated: (($all | length) - ($all | unique | length)),
     ids: ($all | group_by(.) | map(select(length > 1)[0])),
     last: ($handled | last),
     unknown: ($all | unique | map(select(. < 0 or . >= $p)) | length)}')
DUPLICATED=$(jq .duplicated <<<"$ACCOUNT")
BOUND=$(journal e2e-cutover | jq -r '.steps[] | select(.id=="drain-consumers") | .lines[]' \
  | sed -n 's/^\([0-9][0-9]*\) delivered on blue and not settled.*/\1/p')
echo "  duplicated $DUPLICATED (handled on blue and on green): ids $(jq -c .ids <<<"$ACCOUNT")," \
  "the last handled on blue $(jq .last <<<"$ACCOUNT"); the close step said at most '$BOUND'"
expect "no message lost (every published id handled on blue or on green)" "$(jq .missing <<<"$ACCOUNT")" 0
expect "no message invented" "$(jq .unknown <<<"$ACCOUNT")" 0
expect "the close step reported what it could duplicate" "$([[ "$BOUND" =~ ^[0-9]+$ ]] && echo yes)" yes
expect "duplicates within what the close step reported" "$(( DUPLICATED <= BOUND ))" 1
expect "journal outcome" "$(field e2e-cutover journalOutcome)" completed
RUN1="published=$PUBLISHED handled-on-blue=$HANDLED moved-to-green=$GREEN duplicated=$DUPLICATED close-bound=$BOUND blue-after=$BLUE"

# ------------------------------------------------------------------ 2. rollback

say "2. rollback: a completed cutover undone through spec.action"
reset_queues
SEEDED=1000
client publish blue "$QUEUE" "$SEEDED"
expect "blue before" "$(depth blue)" "$SEEDED"
cutover e2e-rollback
wait_phase e2e-rollback Planned 180
approve e2e-rollback "$(field e2e-rollback planFingerprint)"
wait_phase e2e-rollback Completed 300
MOVED=$(depth green)
expect "green after the cutover" "$MOVED" "$SEEDED"
expect "blue after the cutover" "$(depth blue)" 0
k -n "$NS" patch cutover e2e-rollback --type merge -p '{"spec":{"action":"rollback"}}' >/dev/null
wait_phase e2e-rollback RolledBack 300
expect "blue after the rollback" "$(depth blue)" "$SEEDED"
expect "green after the rollback" "$(depth green)" 0
expect "rollback outcome" "$(field e2e-rollback rollbackOutcome)" completed
RUN2="blue-before=$SEEDED green-after-cutover=$MOVED blue-after-rollback=$(depth blue) green-after-rollback=$(depth green)"

# ------------------------------------------------------------------ 3. restart

say "3. restart: the operator killed while the drain runs"
reset_queues
BACKLOG=5000
client publish blue "$QUEUE" "$BACKLOG"
cutover e2e-restart
wait_phase e2e-restart Planned 180
OLD_POD=$(k -n "$OPERATOR_NS" get pod -l app.kubernetes.io/name=acemq-infra-operator -o jsonpath='{.items[0].metadata.name}')
approve e2e-restart "$(field e2e-restart planFingerprint)"
AT_KILL=""
for _ in $(seq 1 1200); do
  AT_KILL=$(journal e2e-restart 2>/dev/null || true)
  if [[ -n "$AT_KILL" ]] && jq -e '.steps[] | select(.id=="drain-messages" and .status=="started")' <<<"$AT_KILL" >/dev/null; then
    break
  fi
  AT_KILL=""
  sleep 0.25
done
[[ -n "$AT_KILL" ]] || fail "the drain step was never seen running"
k -n "$OPERATOR_NS" delete pod "$OLD_POD" --grace-period=0 --force >/dev/null 2>&1
KILLED_AT=$(date +%s)
echo "  killed $OPERATOR_NS/$OLD_POD with drain-messages started"
wait_phase e2e-restart Interrupted 240
echo "  Interrupted $(( $(date +%s) - KILLED_AT ))s after the kill"
echo "  message: $(field e2e-restart message)"
expect "journal outcome after restart" "$(field e2e-restart journalOutcome)" running
expect "last step named" "$(field e2e-restart lastStep)" "$(jq -r '.steps[-1] | "\(.number) \(.id) \(.status)"' <<<"$AT_KILL")"
sleep 20
NEW_POD=$(k -n "$OPERATOR_NS" get pod -l app.kubernetes.io/name=acemq-infra-operator -o jsonpath='{.items[0].metadata.name}')
expect "the journal is exactly as the kill left it (sha256)" \
  "$(journal e2e-restart | jq -c . | shasum -a 256 | cut -c1-16)" "$(jq -c . <<<"$AT_KILL" | shasum -a 256 | cut -c1-16)"
expect "the new operator applied nothing" \
  "$(k -n "$OPERATOR_NS" logs "$NEW_POD" | grep -c 'approved plan' || true)" 0
expect "still Interrupted" "$(field e2e-restart phase)" Interrupted
# The shovel the killed step declared finishes on the broker by itself; the
# rollback refuses while it is still moving messages, and is retried.
k -n "$NS" patch cutover e2e-restart --type merge -p '{"spec":{"action":"rollback"}}' >/dev/null
wait_phase e2e-restart RolledBack 420
expect "blue after the rollback" "$(depth blue)" "$BACKLOG"
expect "green after the rollback" "$(depth green)" 0
expect "no shovel left on blue" "$(client shovels blue)" 0
expect "no shovel left on green" "$(client shovels green)" 0
RUN3="backlog=$BACKLOG killed-at-step='$(jq -r '.steps[-1] | "\(.number) \(.id) \(.status)"' <<<"$AT_KILL")' steps-after-restart=$(journal e2e-restart | jq '.steps | length') blue-after-rollback=$(depth blue) green-after-rollback=$(depth green)"

# ------------------------------------------------------------------ 4. retained

say "4. retained: a deleted Cutover keeps its journal, and a new one rolls back from it"
reset_queues
SEEDED=500
client publish blue "$QUEUE" "$SEEDED"
cutover e2e-retain
wait_phase e2e-retain Planned 180
approve e2e-retain "$(field e2e-retain planFingerprint)"
wait_phase e2e-retain Completed 300
expect "green after the cutover" "$(depth green)" "$SEEDED"
k -n "$NS" delete cutover e2e-retain --timeout=120s >/dev/null
cm() { k -n "$NS" get configmap e2e-retain-journal -o jsonpath="$1"; }
sleep 10   # give the garbage collector the chance it must not take
expect "the journal outlived its Cutover" "$(cm '{.metadata.labels.infra\.acemq\.org/retained}')" true
expect "nothing owns it" "$(cm '{.metadata.ownerReferences}')" ""
expect "it names the Cutover" "$(cm '{.metadata.labels.infra\.acemq\.org/cutover}')" e2e-retain
echo "  reason: $(cm '{.metadata.annotations.infra\.acemq\.org/retained-reason}')"
# The by-hand route in docs/operator.md: both keys come out as the CLI reads them.
expect "the journal extracts" "$(cm '{.data.journal\.json}' | jq -r .outcome)" completed
expect "the deployment file extracts verbatim" "$(cm '{.data.deployment\.yaml}' | shasum -a 256 | cut -c1-16)" \
  "$(shasum -a 256 < "$HERE/deployment.yaml" | cut -c1-16)"
cutover e2e-undo "  action: rollback
  journalFrom: {configMapRef: {name: e2e-retain-journal}}"
wait_phase e2e-undo RolledBack 300
expect "blue after the rollback" "$(depth blue)" "$SEEDED"
expect "green after the rollback" "$(depth green)" 0
expect "the new Cutover took the journal" "$(cm '{.metadata.ownerReferences[0].name}')" e2e-undo
expect "rollback outcome" "$(field e2e-undo rollbackOutcome)" completed
k -n "$NS" delete cutover e2e-undo --timeout=120s >/dev/null
for _ in $(seq 1 60); do
  k -n "$NS" get configmap e2e-retain-journal >/dev/null 2>&1 || break; sleep 1
done
expect "a rolled-back journal goes with its Cutover" "$(k -n "$NS" get configmap e2e-retain-journal 2>/dev/null | wc -l | tr -d ' ')" 0
RUN4="seeded=$SEEDED kept-after-delete=yes rolled-back-by=e2e-undo blue-after=$(depth blue) green-after=$(depth green)"

# ------------------------------------------------------------------ 5. refusals

say "5. refusals: an unlabelled Secret, and a URL off the allowlist"
k -n "$NS" create secret generic e2e-unlabelled --from-literal=password=not-for-cutovers \
  --dry-run=client -o yaml | k apply -f - >/dev/null
cutover e2e-unlabelled "    - {name: BLUE_PASSWORD, secretKeyRef: {name: e2e-unlabelled, key: password}}"
wait_phase e2e-unlabelled Refused 120
MESSAGE=$(field e2e-unlabelled message)
echo "  message: $MESSAGE"
expect "the refusal names the label" "$([[ "$MESSAGE" == *'infra.acemq.org/credentials: "true"'* ]] && echo yes)" yes
expect "and the Secret" "$([[ "$MESSAGE" == *e2e-unlabelled* ]] && echo yes)" yes
expect "no plan was made" "$(field e2e-unlabelled planFingerprint)" ""
# Not a fully qualified Service name, so not under the default *.svc — and
# still a real, reachable broker, so only the allowlist stands in the way.
sed 's/\.acemq-infra-e2e\.svc:/.acemq-infra-e2e:/' "$HERE/deployment.yaml" > "$WORK/off-allowlist.yaml"
DEPLOYMENT="$WORK/off-allowlist.yaml" cutover e2e-off-allowlist
wait_phase e2e-off-allowlist Refused 120
MESSAGE=$(field e2e-off-allowlist message)
echo "  message: $MESSAGE"
expect "the refusal names the allowlist" "$([[ "$MESSAGE" == *ACEMQ_INFRA_ALLOWED_URLS* ]] && echo yes)" yes
expect "no plan was made" "$(field e2e-off-allowlist planFingerprint)" ""
expect "no journal for either" "$(k -n "$NS" get configmap e2e-unlabelled-journal e2e-off-allowlist-journal 2>/dev/null | wc -l | tr -d ' ')" 0
k -n "$NS" delete cutover e2e-unlabelled e2e-off-allowlist --timeout=120s >/dev/null
RUN5="unlabelled-secret=Refused off-allowlist-url=Refused"

# ------------------------------------------------------------------ 6. hooks

say "6. hooks: refused by the operator as it ships"
k -n "$OPERATOR_NS" set env deploy/acemq-infra-operator ACEMQ_INFRA_ALLOW_HOOKS=false >/dev/null
k -n "$OPERATOR_NS" rollout status deploy/acemq-infra-operator --timeout=5m
cutover e2e-hook
wait_phase e2e-hook Refused 180
MESSAGE=$(field e2e-hook message)
echo "  message: $MESSAGE"
expect "the refusal names the setting" "$([[ "$MESSAGE" == *ACEMQ_INFRA_ALLOW_HOOKS* ]] && echo yes)" yes
expect "no plan was made" "$(field e2e-hook planFingerprint)" ""
expect "no journal" "$(k -n "$NS" get configmap e2e-hook-journal 2>/dev/null | wc -l | tr -d ' ')" 0
k -n "$NS" delete cutover e2e-hook --timeout=120s >/dev/null
RUN6="hook-endpoint=Refused journal=none"

say "passed ($OPERATOR_IMAGE)"
echo "  1. cutover : $RUN1"
echo "  2. rollback: $RUN2"
echo "  3. restart : $RUN3"
echo "  4. retained: $RUN4"
echo "  5. refusals: $RUN5"
echo "  6. hooks   : $RUN6"
k -n "$NS" get cutovers
