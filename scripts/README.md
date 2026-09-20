# Scripts

Two scripts, and both are here because a design document about broker cutovers
that you cannot run anything against is a design document that drifts.

## `blue-green-lab.sh`

Two independent RabbitMQ clusters in Docker, so that every question in `docs/`
can be settled by looking rather than by arguing.

```console
$ ./scripts/blue-green-lab.sh up          # both brokers, waiting until ready
$ ./scripts/blue-green-lab.sh seed        # a topology and a backlog on blue
$ ./scripts/blue-green-lab.sh status      # side by side: queues, depths, consumers
$ ./scripts/blue-green-lab.sh drain       # shovel blue -> green, and watch it
$ ./scripts/blue-green-lab.sh mirror      # federate the exchange blue -> green
$ ./scripts/blue-green-lab.sh reset       # empty both, keep them running
$ ./scripts/blue-green-lab.sh down        # remove everything
```

Blue is on the conventional `5672`/`15672`; green is deliberately far away on
`5682`/`15682`, so a copy-pasted URL cannot quietly hit the wrong cluster.
Requires `docker` and `curl`; `jq` makes the output much better and the script
works without it.

**Two separate single-node clusters, not one cluster with two nodes.** A cutover
moves an estate between clusters that share nothing — no Erlang cookie, no
cluster membership, no storage. Two nodes of one cluster would let a whole class
of mistake pass that production would not.

Three things in it are worth running deliberately, because each demonstrates a
claim in the documentation that reads as abstract until you watch it:

- **`drain` empties blue.** A shovel consumes. Run it, then run `status`, and
  the rollback for those messages is visibly gone. This is the single most
  important sentence in `docs/message-state.md` and the one most often skimmed.
- **`mirror` does not.** Blue keeps everything, green gets a copy of what
  arrives from now on, and it never finishes — which is why it is an observation
  rather than a cutover. Note that it federates the **exchange**: the first
  version of this script federated the queue, which pulls only when the upstream
  has no consumers, and so copied nothing at all while blue was healthy. That
  correction is now `examples/rejected/mirror-by-queue-federation.yaml` and a
  rule in the linter.
- **`seed` installs a ten-second `message-ttl` policy on
  `orders.notifications`, on purpose.** Copy the definitions to green with
  policies included, then drain a backlog into it, and watch the backlog vanish
  on arrival. That is the data-loss bug the default step order exists to
  prevent, made available to try.

`up` also enables the shovel and federation plugins, which the management image
ships and does not enable. That is the capability probe from
`docs/broker-agnostic.md` done by hand: a real cluster may well not have them,
and the tool has to find that out before step six rather than during it.

## `lint-deployment.py`

Checks a deployment file against the format in `docs/configuration.md`, without
touching a broker.

```console
$ ./scripts/lint-deployment.py examples/blue-green.yaml
examples/blue-green.yaml: ok — blueGreen, 9 steps, 2 clusters
```

Requires PyYAML. This is the half of `acemq-infra validate` that can exist
before the tool does, and it exists now for a specific reason: a configuration
format that lives only in a document drifts from the examples illustrating it
within a week. CI runs it over everything in `examples/` and asserts that the
deliberately-broken files in `examples/rejected/` are rejected — that second
assertion is what proves the rules are live rather than merely written.

Beyond the schema, it enforces the four structural rules that cost messages:

| Rule | Why |
|---|---|
| Retention policies must not be copied to the target before the drain | A `message-ttl` or `max-length` policy is live the instant it lands and discards the backlog on arrival |
| A drain must be preceded by a guard on `publishRate` or `unacked` | Unacked deliveries requeue on the **source** when a connection closes, so draining first shovels messages consumers were still holding |
| No `percentage` (or `weight`, `split`, `trafficSplit`) in a canary | Splitting producers by percentage partitions the queue across two clusters — `docs/canary.md` |
| `semantics` is required, with no default | At-least-once means a message may be processed twice; at-most-once means one may be stranded. The tool does not choose this for you |
| A `mirror` names exchanges, never queues | Queue federation pulls only when the upstream has no consumers — a conditional move, not a copy |

When the tool exists these rules move into it, and this script goes away.

## Where the docs site is built

Not here. `.github/scripts/build-docs-site.sh`, because the docs workflow runs
from a checkout of this repository alone and a shared scripts folder would be
out of its reach. It runs locally too:

```console
$ bash .github/scripts/build-docs-site.sh
11 source pages, 40 internal links, every one resolves inside docs/
  rendered blue-green.html
  ...
11 pages, every internal link resolves and all 234 anchors exist
```
