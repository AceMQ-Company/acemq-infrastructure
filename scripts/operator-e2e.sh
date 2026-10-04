#!/usr/bin/env bash
# The operator, end to end, on a throwaway kind cluster it creates and deletes.
#
#   ./scripts/operator-e2e.sh             build, create the cluster, run, delete it
#   ./scripts/operator-e2e.sh --keep      leave the cluster up afterwards, to look
#   ./scripts/operator-e2e.sh --no-build  use the images already built
#   ./scripts/operator-e2e.sh --reuse     run again on a cluster an earlier --keep left
#
# Three runs against two real RabbitmqClusters, blue and green, made by the
# RabbitMQ Cluster Operator (pinned below, with the cert-manager it needs)
# inside the throwaway cluster:
#
#   1. cutover   a backlog on blue with a consumer attached; a Cutover is
#                planned, an approval for the wrong plan is refused, the right
#                one runs it. Every message is accounted for: acked by the
#                consumer on blue, or on green.
#   2. rollback  a second Cutover, completed, then spec.action: rollback. The
#                estate ends where it started.
#   3. restart   the operator pod is killed (no grace) while the drain step
#                runs. The new one must mark the Cutover Interrupted, name the
#                step, and run nothing; then a rollback from there.
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
CLIENT_IMAGE=acemq-infra-e2e-client:dev
QUEUE=orders.new
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HERE="$ROOT/scripts/operator-e2e"

KEEP=0
BUILD=1
REUSE=0
for argument in "$@"; do
  case "$argument" in
    --keep) KEEP=1 ;;
    --no-build) BUILD=0 ;;
    --reuse) REUSE=1; KEEP=1 ;;
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
  (cd "$ROOT" && mvn -B -q --no-transfer-progress -pl acemq-infra-operator -am package \
      -DskipTests -DskipITs)
  docker build -q -t "$OPERATOR_IMAGE" "$ROOT/acemq-infra-operator" >/dev/null
  docker build -q -t "$CLIENT_IMAGE" "$HERE" >/dev/null
fi

trap cleanup EXIT
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  [[ $REUSE == 1 ]] || { KEEP=1; fail "a kind cluster named $CLUSTER already exists; this script only uses one it created (--reuse to run on it again)"; }
  say "reusing kind cluster $CLUSTER"
  k -n "$NS" delete cutovers --all --ignore-not-found >/dev/null 2>&1 || true
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
for colour in blue green; do
  k apply -f - >/dev/null <<EOF
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
done
for colour in blue green; do
  for _ in $(seq 1 120); do
    k -n "$NS" get rabbitmqcluster "$colour" >/dev/null 2>&1 && break; sleep 1
  done
  k -n "$NS" wait --for=condition=AllReplicasReady "rabbitmqcluster/$colour" --timeout=10m
done

say "the operator"
k apply -f "$ROOT/deploy/crd.yaml" >/dev/null
# The manifest names the published image; the run uses the one just built and
# loaded, so nothing is pulled from GHCR.
sed -E "s#image: ghcr.io/acemq-company/acemq-infra-operator:.*#image: $OPERATOR_IMAGE#" \
  "$ROOT/deploy/operator.yaml" | k apply -f - >/dev/null
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

cutover() { # name — a Cutover carrying deployment.yaml, credentials from the Secrets
  {
    cat <<EOF
apiVersion: infra.acemq.org/v1alpha1
kind: Cutover
metadata:
  name: $1
  namespace: $NS
spec:
  variables:
    - {name: BLUE_USERNAME, secretKeyRef: {name: blue-default-user, key: username}}
    - {name: BLUE_PASSWORD, secretKeyRef: {name: blue-default-user, key: password}}
    - {name: GREEN_USERNAME, secretKeyRef: {name: green-default-user, key: username}}
    - {name: GREEN_PASSWORD, secretKeyRef: {name: green-default-user, key: password}}
  deployment: |
EOF
    sed 's/^/    /' "$HERE/deployment.yaml"
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
sleep 5
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
ACKED=$(k -n "$NS" logs consumer | sed -n 's/^final acked=//p')
GREEN=$(depth green); BLUE=$(depth blue)
echo "  published $PUBLISHED; acked on blue $ACKED; on green $GREEN; left on blue $BLUE"
expect "the consumer was closed by the cutover" "$([[ -n "$ACKED" ]] && echo yes)" yes
expect "blue is drained" "$BLUE" 0
expect "blue has no consumers" "$(consumers blue)" 0
expect "every message accounted for (acked + green)" "$((ACKED + GREEN))" "$PUBLISHED"
expect "journal outcome" "$(field e2e-cutover journalOutcome)" completed
RUN1="published=$PUBLISHED acked-on-blue=$ACKED moved-to-green=$GREEN blue-after=$BLUE"

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

say "passed"
echo "  1. cutover : $RUN1"
echo "  2. rollback: $RUN2"
echo "  3. restart : $RUN3"
k -n "$NS" get cutovers
