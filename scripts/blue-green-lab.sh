#!/usr/bin/env bash
# Two independent RabbitMQ clusters, blue and green, in Docker.
#
#   ./scripts/blue-green-lab.sh up          both brokers, waiting until ready
#   ./scripts/blue-green-lab.sh seed        a topology and a backlog on blue
#   ./scripts/blue-green-lab.sh status      side-by-side: queues, depths, consumers
#   ./scripts/blue-green-lab.sh definitions blue | green
#   ./scripts/blue-green-lab.sh drain       shovel blue -> green, and watch it
#   ./scripts/blue-green-lab.sh mirror      federate the exchange blue -> green (copies)
#   ./scripts/blue-green-lab.sh connections blue | green
#   ./scripts/blue-green-lab.sh reset       empty both, keep them running
#   ./scripts/blue-green-lab.sh down        remove everything
#
# Every design question in docs/ needs two brokers to reason about, and reading
# about a shovel is not the same as watching one empty a queue. This is the
# thing to run while arguing with docs/message-state.md.
#
# Deliberately two SEPARATE single-node clusters rather than one cluster with two
# nodes. A cutover moves an estate between clusters that share nothing -- no
# Erlang cookie, no cluster membership, no shared storage. Two nodes of one
# cluster would let a whole class of mistake pass that production would not.
#
# Requires: docker, curl. jq if you have it -- the output is nicer, and the
# script works without it.
set -euo pipefail

BLUE_NAME="acemq-lab-blue"
GREEN_NAME="acemq-lab-green"
NETWORK="acemq-lab"
IMAGE="${ACEMQ_LAB_IMAGE:-rabbitmq:4-management}"

# Host ports. Blue takes the conventional pair so that anything pointed at a
# default RabbitMQ finds it; green is deliberately far away so a copy-pasted URL
# cannot accidentally hit the wrong cluster.
BLUE_AMQP=5672;  BLUE_MGMT=15672
GREEN_AMQP=5682; GREEN_MGMT=15682

USER_NAME="${ACEMQ_LAB_USER:-admin}"
PASSWORD="${ACEMQ_LAB_PASSWORD:-admin}"
VHOST="${ACEMQ_LAB_VHOST:-/}"

C_BLUE=$'\033[34m'; C_GREEN=$'\033[32m'; C_DIM=$'\033[2m'
C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_OFF=$'\033[0m'
[ -t 1 ] || { C_BLUE=""; C_GREEN=""; C_DIM=""; C_WARN=""; C_ERR=""; C_OFF=""; }

say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==>%s %s\n' "$C_DIM" "$C_OFF" "$*"; }
warn() { printf '%s !%s %s\n' "$C_WARN" "$C_OFF" "$*"; }
die()  { printf '%s !!%s %s\n' "$C_ERR" "$C_OFF" "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null || die "$1 is required"; }

# The management port for a cluster name, so every helper below takes "blue" or
# "green" rather than a port nobody can remember.
mgmt_port() {
  case "$1" in
    blue)  echo "$BLUE_MGMT" ;;
    green) echo "$GREEN_MGMT" ;;
    *) die "unknown cluster '$1' (expected blue or green)" ;;
  esac
}

api() {
  local cluster="$1"; shift
  local method="$1"; shift
  local path="$1"; shift
  curl -fsS -u "$USER_NAME:$PASSWORD" -X "$method" \
    -H 'content-type: application/json' \
    "http://localhost:$(mgmt_port "$cluster")/api$path" "$@"
}

# The default vhost has to arrive as %2F. Getting this wrong is the first bug
# anyone writes against this API, and the reason acemq-java-rabbitmq-admin has a
# test for it.
vhost_enc() {
  if [ "$VHOST" = "/" ]; then printf '%%2F'; else printf '%s' "${VHOST//\//%2F}"; fi
}

# Pretty-print stdin as JSON when jq is available, pass it through when not.
# No filter argument: every caller wants the whole document, and an optional
# parameter nothing passes is one shellcheck rightly objects to (SC2120).
pretty() { if command -v jq >/dev/null; then jq .; else cat; fi; }

# ---------------------------------------------------------------- up / down

start_one() {
  local name="$1" amqp="$2" mgmt="$3" colour="$4"
  if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
    if docker ps --format '{{.Names}}' | grep -qx "$name"; then
      say "  ${colour}${name}${C_OFF} already running"
      return
    fi
    docker start "$name" >/dev/null
    say "  ${colour}${name}${C_OFF} restarted"
    return
  fi
  docker run -d \
    --name "$name" \
    --network "$NETWORK" \
    --hostname "$name" \
    -e RABBITMQ_DEFAULT_USER="$USER_NAME" \
    -e RABBITMQ_DEFAULT_PASS="$PASSWORD" \
    -p "$amqp:5672" -p "$mgmt:15672" \
    "$IMAGE" >/dev/null
  say "  ${colour}${name}${C_OFF} started  amqp :$amqp  management :$mgmt"
}

wait_ready() {
  local cluster="$1" name="$2" tries=0
  printf '  waiting for %s ' "$name"
  until api "$cluster" GET /overview >/dev/null 2>&1; do
    tries=$((tries + 1))
    [ "$tries" -gt 90 ] && { printf '\n'; die "$name never became ready"; }
    printf '.'
    sleep 2
  done
  printf ' ready\n'
}

enable_plugins() {
  # rabbitmq:*-management ships the shovel and federation plugins but does not
  # enable them, and a cutover cannot happen without both. This is exactly the
  # capability probe described in docs/broker-agnostic.md, done by hand: a real
  # cluster may well not have these, and the tool has to find that out before
  # step 6 rather than during it.
  local name="$1"
  docker exec "$name" rabbitmq-plugins enable --quiet \
    rabbitmq_shovel rabbitmq_shovel_management \
    rabbitmq_federation rabbitmq_federation_management >/dev/null
}

cmd_up() {
  need docker; need curl
  step "network"
  docker network inspect "$NETWORK" >/dev/null 2>&1 \
    || { docker network create "$NETWORK" >/dev/null; say "  created $NETWORK"; }
  docker network inspect "$NETWORK" >/dev/null && say "  $NETWORK ready"

  step "brokers"
  start_one "$BLUE_NAME"  "$BLUE_AMQP"  "$BLUE_MGMT"  "$C_BLUE"
  start_one "$GREEN_NAME" "$GREEN_AMQP" "$GREEN_MGMT" "$C_GREEN"

  step "readiness"
  wait_ready blue  "$BLUE_NAME"
  wait_ready green "$GREEN_NAME"

  step "plugins"
  enable_plugins "$BLUE_NAME";  say "  blue:  shovel, federation"
  enable_plugins "$GREEN_NAME"; say "  green: shovel, federation"

  cat <<EOF

  ${C_BLUE}blue${C_OFF}   http://localhost:$BLUE_MGMT   amqp://$USER_NAME:$PASSWORD@localhost:$BLUE_AMQP
  ${C_GREEN}green${C_OFF}  http://localhost:$GREEN_MGMT   amqp://$USER_NAME:$PASSWORD@localhost:$GREEN_AMQP

  Two separate clusters. They share a Docker network so a shovel can reach
  across, and nothing else -- no cookie, no membership, no storage.

  next:  ./scripts/blue-green-lab.sh seed
EOF
}

cmd_down() {
  need docker
  step "removing"
  for name in "$BLUE_NAME" "$GREEN_NAME"; do
    if docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
      docker rm -f "$name" >/dev/null && say "  removed $name"
    fi
  done
  docker network rm "$NETWORK" >/dev/null 2>&1 && say "  removed $NETWORK" || true
}

# ---------------------------------------------------------------- seed

declare_queue() {
  local cluster="$1" queue="$2" type="${3:-classic}"
  api "$cluster" PUT "/queues/$(vhost_enc)/$queue" \
    -d "{\"durable\":true,\"arguments\":{\"x-queue-type\":\"$type\"}}" >/dev/null
}

bind_queue() {
  local cluster="$1" exchange="$2" queue="$3" key="$4"
  api "$cluster" POST "/bindings/$(vhost_enc)/e/$exchange/q/$queue" \
    -d "{\"routing_key\":\"$key\"}" >/dev/null
}

publish() {
  local cluster="$1" exchange="$2" key="$3" body="$4"
  api "$cluster" POST "/exchanges/$(vhost_enc)/$exchange/publish" \
    -d "{\"properties\":{\"delivery_mode\":2},\"routing_key\":\"$key\",\"payload\":$(
        printf '%s' "$body" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/"/; s/$/"/'
      ),\"payload_encoding\":\"string\"}" >/dev/null
}

cmd_seed() {
  need curl
  local count="${1:-200}"

  step "topology on blue"
  api blue PUT "/exchanges/$(vhost_enc)/orders" \
    -d '{"type":"topic","durable":true}' >/dev/null
  say "  exchange orders (topic)"

  declare_queue blue orders.new
  declare_queue blue orders.notifications
  declare_queue blue orders.audit
  declare_queue blue orders.priority quorum
  say "  queues   orders.new, orders.notifications, orders.audit, orders.priority (quorum)"

  bind_queue blue orders orders.new           "order.created"
  bind_queue blue orders orders.notifications "order.#"
  bind_queue blue orders orders.audit         "#"
  bind_queue blue orders orders.priority      "order.priority"
  say "  bindings 4"

  # A policy that would eat a backlog if it were applied to green before the
  # drain. This is the point docs/message-state.md makes about step ordering,
  # made available to try: copy the definitions with policies included, then
  # drain into a queue that is enforcing a ten-second TTL.
  api blue PUT "/policies/$(vhost_enc)/short-ttl" \
    -d '{"pattern":"^orders\\.notifications$","definition":{"message-ttl":10000},"priority":1,"apply-to":"queues"}' \
    >/dev/null
  say "  policy   short-ttl (message-ttl 10s on orders.notifications)"
  warn "that policy is here on purpose. Copy it to green before draining and"
  warn "watch the backlog disappear on arrival -- docs/message-state.md."

  step "backlog on blue"
  local i=0
  while [ "$i" -lt "$count" ]; do
    publish blue orders "order.created" "{\"order\":$i,\"seeded\":true}"
    i=$((i + 1))
    if [ $((i % 50)) -eq 0 ]; then printf '  %d/%d\n' "$i" "$count"; fi
  done
  say "  published $count messages to orders/order.created"

  # The management API's queue depths come from the statistics database, which
  # refreshes on an interval (collect_statistics_interval, 5s by default) rather
  # than on every publish. Calling status immediately after a publish reports
  # zeroes for queues that are demonstrably full, which is alarming and wrong.
  #
  # Worth knowing beyond this script: every depth guard the tool waits on --
  # `waitFor: { depth: 0 }` -- reads the same lagging number. A drain is not
  # finished when the API first says zero, and a guard with a timeout shorter
  # than the statistics interval can pass on a stale reading.
  step "settling (the statistics database refreshes on an interval)"
  sleep 6

  step "now"
  cmd_status
  say "  orders.notifications is nearly empty on purpose: the short-ttl policy"
  say "  seeded above is discarding those messages after ten seconds. That is"
  say "  the data-loss bug docs/message-state.md is about, running."
}

# ---------------------------------------------------------------- status

queue_table() {
  local cluster="$1"
  if command -v jq >/dev/null; then
    api "$cluster" GET "/queues/$(vhost_enc)" \
      | jq -r '.[] | "    \(.name)\t\(.messages // 0)\t\(.consumers // 0)\t\(.arguments["x-queue-type"] // "classic")"' \
      | sort | awk -F'\t' '{printf "    %-26s %8s msg %5s cons  %s\n", $1, $2, $3, $4}'
  else
    api "$cluster" GET "/queues/$(vhost_enc)" | tr ',' '\n' | grep -E '"name"|"messages"' || true
  fi
}

cmd_status() {
  need curl
  printf '\n%sblue%s  http://localhost:%s\n' "$C_BLUE" "$C_OFF" "$BLUE_MGMT"
  queue_table blue
  printf '\n%sgreen%s http://localhost:%s\n' "$C_GREEN" "$C_OFF" "$GREEN_MGMT"
  queue_table green

  if command -v jq >/dev/null; then
    local shovels
    shovels=$(api green GET "/parameters/shovel" 2>/dev/null | jq -r 'length' || echo 0)
    [ "$shovels" != "0" ] && printf '\n  %s shovel(s) declared on green\n' "$shovels"
    local upstreams
    upstreams=$(api green GET "/parameters/federation-upstream" 2>/dev/null | jq -r 'length' || echo 0)
    [ "$upstreams" != "0" ] && printf '  %s federation upstream(s) on green\n' "$upstreams"
  fi
  printf '\n'
}

cmd_definitions() {
  need curl
  local cluster="${1:-blue}"
  warn "this document contains password hashes. It is a credential."
  api "$cluster" GET "/definitions" | pretty
}

cmd_connections() {
  need curl
  local cluster="${1:-blue}"
  if command -v jq >/dev/null; then
    api "$cluster" GET "/connections" \
      | jq -r '.[] | "  \(.name)  user=\(.user)  channels=\(.channels)  state=\(.state)"'
  else
    api "$cluster" GET "/connections" | pretty
  fi
}

# ---------------------------------------------------------------- drain / mirror

cmd_drain() {
  need curl
  local queue="${1:-orders.new}"

  warn "a shovel CONSUMES. After this, $queue is empty on blue and the"
  warn "rollback for those messages is gone. docs/message-state.md."

  step "declaring shovel lab-drain-$queue on green"
  # Declared on the destination so that the destination's lifecycle owns it.
  # The source URI uses the container name because the shovel runs inside
  # green's broker, on the Docker network -- not on your laptop.
  api green PUT "/parameters/shovel/$(vhost_enc)/lab-drain-$queue" -d "$(cat <<JSON
{"value":{
  "src-protocol":"amqp091",
  "src-uri":"amqp://$USER_NAME:$PASSWORD@$BLUE_NAME:5672",
  "src-queue":"$queue",
  "dest-protocol":"amqp091",
  "dest-uri":"amqp://$USER_NAME:$PASSWORD@$GREEN_NAME:5672",
  "dest-queue":"$queue",
  "ack-mode":"on-confirm",
  "src-delete-after":"queue-length"
}}
JSON
)" >/dev/null
  say "  declared, src-delete-after=queue-length (it stops when blue is empty)"

  step "watching"
  local tries=0 depth
  while [ "$tries" -lt 60 ]; do
    if command -v jq >/dev/null; then
      depth=$(api blue GET "/queues/$(vhost_enc)/$queue" | jq -r '.messages // 0')
    else
      depth=0
    fi
    printf '  blue %s: %s messages\n' "$queue" "$depth"
    [ "$depth" = "0" ] && break
    tries=$((tries + 1))
    sleep 2
  done

  step "after"
  cmd_status
  say "  the shovel removes itself once the queue length it started with is moved."
  say "  clean up a stuck one with: ./scripts/blue-green-lab.sh drain-clear"
}

cmd_drain_clear() {
  need curl; need jq
  api green GET "/parameters/shovel" \
    | jq -r '.[] | select(.name | startswith("lab-drain-")) | .name' \
    | while read -r name; do
        api green DELETE "/parameters/shovel/$(vhost_enc)/$name" >/dev/null
        say "  deleted shovel $name"
      done
}

cmd_mirror() {
  need curl
  local exchange="${1:-orders}"
  local queue="${2:-orders.new}"

  # EXCHANGE federation, not queue federation, and the distinction is the whole
  # reason this command exists.
  #
  # A federated QUEUE pulls from its upstream only when the upstream has no
  # local consumers. It is a work-queueing mechanism: the message is consumed
  # upstream and is then gone from it. That is a conditional MOVE, and using it
  # to build a shadow environment gives you an empty green whenever blue's
  # consumers are healthy -- which is exactly what the first version of this
  # script did.
  #
  # A federated EXCHANGE replays what is published upstream into its own bound
  # queues. Blue routes the message to blue's queues as usual AND green receives
  # a copy. That is the mirror. docs/canary.md.
  say "EXCHANGE federation COPIES. Blue keeps everything and routes it as usual;"
  say "green's exchange receives a replay and routes it to green's own queues."
  say "It does not catch up on the existing backlog, and it never finishes."

  step "target topology on green"
  api green PUT "/exchanges/$(vhost_enc)/$exchange" \
    -d '{"type":"topic","durable":true}' >/dev/null
  declare_queue green "$queue"
  bind_queue green "$exchange" "$queue" "order.created"
  say "  exchange $exchange, queue $queue, binding order.created"
  say "  (a real cutover's topology step has already done this)"

  step "upstream on green"
  api green PUT "/parameters/federation-upstream/$(vhost_enc)/lab-blue" -d "$(cat <<JSON
{"value":{
  "uri":"amqp://$USER_NAME:$PASSWORD@$BLUE_NAME:5672",
  "prefetch-count":1000,
  "ack-mode":"on-confirm"
}}
JSON
)" >/dev/null
  say "  upstream lab-blue -> $BLUE_NAME"

  step "policy on green"
  # apply-to: exchanges. Pointing this at queues is the mistake described above
  # and it fails quietly -- the policy matches, the link comes up, and nothing
  # is ever copied while blue has consumers.
  api green PUT "/policies/$(vhost_enc)/lab-federate" \
    -d "{\"pattern\":\"^${exchange//./\\\\.}$\",\"definition\":{\"federation-upstream\":\"lab-blue\"},\"priority\":10,\"apply-to\":\"exchanges\"}" \
    >/dev/null
  say "  policy lab-federate on ^$exchange\$  (apply-to: exchanges)"
  say ""
  say "  publish to blue and watch BOTH depths rise -- that is the copy:"
  say "    ./scripts/blue-green-lab.sh seed 20"
  say "    ./scripts/blue-green-lab.sh status"
}

cmd_mirror_clear() {
  need curl
  api green DELETE "/policies/$(vhost_enc)/lab-federate" >/dev/null 2>&1 \
    && say "  deleted policy lab-federate" || true
  api green DELETE "/parameters/federation-upstream/$(vhost_enc)/lab-blue" >/dev/null 2>&1 \
    && say "  deleted upstream lab-blue" || true
}

# ---------------------------------------------------------------- reset

cmd_reset() {
  need curl
  cmd_drain_clear 2>/dev/null || true
  cmd_mirror_clear 2>/dev/null || true
  for cluster in blue green; do
    step "emptying $cluster"
    if command -v jq >/dev/null; then
      api "$cluster" GET "/queues/$(vhost_enc)" | jq -r '.[].name' \
        | while read -r q; do
            api "$cluster" DELETE "/queues/$(vhost_enc)/$q" >/dev/null && say "  deleted queue $q"
          done
      api "$cluster" GET "/exchanges/$(vhost_enc)" \
        | jq -r '.[] | select(.name == "orders") | .name' \
        | while read -r e; do
            api "$cluster" DELETE "/exchanges/$(vhost_enc)/$e" >/dev/null && say "  deleted exchange $e"
          done
      api "$cluster" GET "/policies/$(vhost_enc)" | jq -r '.[].name' \
        | while read -r p; do
            api "$cluster" DELETE "/policies/$(vhost_enc)/$p" >/dev/null && say "  deleted policy $p"
          done
    else
      warn "jq not installed; reset can only remove the known lab queues"
      for q in orders.new orders.notifications orders.audit orders.priority; do
        api "$cluster" DELETE "/queues/$(vhost_enc)/$q" >/dev/null 2>&1 \
          && say "  deleted queue $q" || true
      done
    fi
  done
  say ""
  say "  both clusters are running and empty. seed again with:"
  say "    ./scripts/blue-green-lab.sh seed"
}

# ---------------------------------------------------------------- dispatch

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
}

case "${1:-}" in
  up)          shift; cmd_up "$@" ;;
  down)        shift; cmd_down "$@" ;;
  seed)        shift; cmd_seed "$@" ;;
  status)      shift; cmd_status "$@" ;;
  definitions) shift; cmd_definitions "$@" ;;
  connections) shift; cmd_connections "$@" ;;
  drain)       shift; cmd_drain "$@" ;;
  drain-clear) shift; cmd_drain_clear "$@" ;;
  mirror)      shift; cmd_mirror "$@" ;;
  mirror-clear) shift; cmd_mirror_clear "$@" ;;
  reset)       shift; cmd_reset "$@" ;;
  ""|-h|--help|help) usage ;;
  *) die "unknown command '$1' -- try --help" ;;
esac
