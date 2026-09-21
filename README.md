# acemq-infrastructure

[![attribution guard](https://github.com/AceMQ-Company/acemq-infrastructure/actions/workflows/attribution-guard.yml/badge.svg?branch=main)](https://github.com/AceMQ-Company/acemq-infrastructure/actions/workflows/attribution-guard.yml)
[![ci](https://github.com/AceMQ-Company/acemq-infrastructure/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/AceMQ-Company/acemq-infrastructure/actions/workflows/ci.yml)
[![docs](https://img.shields.io/badge/docs-acemq.org-blue)](https://acemq.org/acemq-infrastructure/)
[![license](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![brokers](https://img.shields.io/badge/broker-RabbitMQ-lightgrey)](docs/broker-agnostic.md)

Blue/green and canary cutovers for message brokers — the part that **moves
clusters**.

> **Status: `validate`, `plan` and `apply` work against real RabbitMQ
> clusters.** [Milestone one](docs/roadmap.md) — the configuration model, the
> validator, `probe()`, the default step list and the planner — was released as
> `0.1.0` and writes nothing to a broker. [Phase 2](docs/roadmap.md) is now on
> `main`: the executor carries every step of the default blue/green list out
> against two clusters, `apply --dry-run` re-probes both and reports what each
> step would do at this moment, and the rollback is derived from what a run
> actually did and tested by running one — a cutover, then the rollback, with
> the estate asserted back where it started and the duplicate count measured
> rather than described. `apply` prints the plan and stops to ask before the
> first write. See [the roadmap](docs/roadmap.md) for the build order and
> [the library](docs/library.md) for what exists today.

The five AceMQ client libraries already do the client half of a cutover — drain,
pause, graceful shutdown, the same way in Java, Go, .NET, Python and Ruby. None
of them can tell the cluster they are draining *towards* that it exists. That is
what this is for.

## What a cutover is

Four things in a fixed order, and the order is the whole trick:

1. **Copy the shape.** Green gets blue's exchanges, queues, bindings, users and
   permissions — but not yet its retention policies, which would start deleting
   the messages you are about to move.
2. **Stop the producers, then the consumers.** In that order. A consumer closed
   while producers are still publishing leaves work behind it.
3. **Move what is left.** A shovel drains blue into green. It *consumes* from
   blue — correct here, and wrong everywhere else.
4. **Switch the endpoint, and keep blue.** The endpoint is not this tool's to
   own. Blue stays cold for as long as the rollback window says.

Everything difficult is in "what is left" and in the fact that there is no
moment at which both clusters agree. [Message state](docs/message-state.md) is
the page to read if you read one.

## What it looks like

Against the two clusters `scripts/blue-green-lab.sh` puts up, abbreviated:

```console
$ acemq-infra plan -f orders.yaml
orders-blue-green — blueGreen, blue → green, semantics=atLeastOnce

  probe             blue  RabbitMQ 3.13.7  shovel✓ federation✓ streams✓
                    green RabbitMQ 4.0.5   shovel✓ federation✓ streams✓
                    all 5 required capabilities present
  2 topology        14 exchanges, 31 queues, 58 bindings
                    policies, operatorPolicies EXCLUDED — applied at step 7
  6 drain-messages  shovel blue → green, 29 queues (orders.audit excluded)
                    27,412 messages to move
                    wait: blue depth=0, 15m, on timeout ABORT
  8 switch-endpoint EXTERNAL — will stop and wait

warnings
  · 2 streams in scope. Offsets do not travel between clusters.
  · a shovel republishes: x-delivery-count resets on all 27,412 messages.

nothing was written. run `acemq-infra apply -f` on this file to execute it, or
`apply --dry-run` to see what each step would do right now.
```

And when a cluster cannot do it, the plan says so at second zero rather than at
step six with half an estate moved:

```console
refused
  · step 6 drain-messages needs DRAIN_BY_SHOVEL on blue, which does not have
    it: rabbitmq_shovel and rabbitmq_shovel_management are not enabled on this
    cluster (/api/shovels is not there).
```

`acemq-infra apply -f orders.yaml` carries it out. It prints that plan, stops,
and asks — and the only thing that gets past the question is the word `yes`,
typed at a terminal. `--yes` is how a pipeline says it has already decided, and
it consents to the run *starting* and to nothing after it: an
`endpoint: external` switch or an `onTimeout: prompt` in a run with nobody
watching still stops it, and the external switch is refused in preflight before
the first write rather than discovered at step eight with the drain done.

`apply --dry-run` is not the plan printed again. It re-probes both clusters and
rehearses every step against them — reading the topology, listing the
connections, measuring the queues — so each guard reports what its condition is
*at this moment* rather than what it will wait for. It cannot write: the brokers
a rehearsal is handed throw on every verb that would.

## The decisions

| Question | Answer | Where |
|---|---|---|
| Java or Go? | **Java 17**, distributed as a GraalVM native image. `acemq-java-rabbitmq-admin` already implements every RabbitMQ operation a cutover needs, tested against a real broker | [Language and shape](docs/shape.md) |
| Library, CLI or operator? | **A core library and a CLI over it, in one release.** An operator later, or never — they are layers, not alternatives | [Language and shape](docs/shape.md) |
| How broker-agnostic, really? | Nine intent-level verbs and a capability set. RabbitMQ is the only provider and the capability model earns its place on that one broker | [Broker-agnostic, honestly](docs/broker-agnostic.md) |
| Blue/green vs canary? | **Different operations, not one with a flag.** A broker canary is not a traffic split — a percentage partitions the queue | [Blue/green](docs/blue-green.md), [Canary](docs/canary.md) |
| What makes this hard? | Message state. Unacked deliveries, shovels that consume, stream offsets that cannot travel, and no atomic cutover | [Message state](docs/message-state.md) |

## The configuration

The file is the interface. A cutover lands in a pull request days before it
runs, so it has to be readable by people who were not in the room:

```yaml
deployment:
  operation: blueGreen
  from: blue
  to: green
  semantics: atLeastOnce        # no default — the tool will not choose for you

  steps:
    - id: topology
      copyTopology:
        from: blue
        to: green
        exclude: [policies, operatorPolicies]   # applied after the drain
    - id: drain-messages
      drain: { from: blue, to: green, queues: ["orders.*"] }
      waitFor: { on: blue, depth: 0, timeout: 15m, onTimeout: abort }
```

The format evolves the one the [C# prior art](docs/prior-art.md) already had.
Full worked examples are in [`examples/`](examples/), and
[`docs/configuration.md`](docs/configuration.md) explains what changed and why.

## Scripts

Two, and both earn their place — every design question here needs two brokers to
reason about.

```console
$ ./scripts/blue-green-lab.sh up      # two separate RabbitMQ clusters in Docker
$ ./scripts/blue-green-lab.sh seed    # a topology and a backlog on blue
$ ./scripts/blue-green-lab.sh drain   # shovel blue → green, and watch it empty
$ ./scripts/blue-green-lab.sh status

$ ./scripts/lint-deployment.py examples/blue-green.yaml
examples/blue-green.yaml: ok — blueGreen, 9 steps, 2 clusters
```

The linter is the part of `acemq-infra validate` that can exist before the tool
does. It checks the structural rules that cost messages — policies copied before
a drain, a drain with nothing waiting on unacked, a `percentage` key in a canary,
a missing `semantics`. CI runs it over `examples/` and asserts that the
deliberately-broken files in `examples/rejected/` are rejected, which is how the
format stays honest while it is still only a document. See
[`scripts/README.md`](scripts/README.md).

## Building

```console
$ mvn verify
$ java -jar acemq-infra-cli/target/acemq-infra-cli-*.jar validate -f examples/blue-green.yaml
```

Java 17. Four modules: `acemq-infra-core` holds the configuration model, the
validator and the planner and has no broker client on its classpath;
`acemq-infra-rabbitmq` holds `probe()` over
[`acemq-java-rabbitmq-admin`](https://acemq.org/acemq-java-rabbitmq-admin/) and
contains no method that writes to a broker at all; `acemq-infra-execute` is the
one that writes, and it sits beside the provider rather than inside it, so the
two cannot see each other; `acemq-infra-cli` is the command line over all three.
The dependency runs one way, which is how "the planner writes nothing" is a fact
the compiler enforces rather than a rule somebody remembers.
[The library](docs/library.md) explains the layout, the types, and what has
deliberately been left.

The integration tests need Docker. The provider's run against a real broker
through Testcontainers, including one with the shovel and federation plugins
left disabled, which is the estate the capability model exists for. The
executor's put up **two** brokers on a shared network — the same two clusters
`scripts/blue-green-lab.sh` builds, and separate clusters rather than two nodes
of one, for the same reason — run a cutover, run the rollback derived from it,
and assert the estate is back where it started. `mvn test` runs everything else
without a daemon; nothing anywhere here skips when Docker is absent, because a
skip reads as a pass.

The Java validator and `scripts/lint-deployment.py` are held in agreement by the
same six files: everything in `examples/` is accepted by both, and everything in
`examples/rejected/` is refused by both, for the same reasons.

## What it will not do

- **Provision brokers.** Terraform and the RabbitMQ Cluster Operator make
  clusters; this moves between clusters that exist.
- **Own your endpoint.** DNS, load balancers and service meshes are yours. It
  tells you exactly when to switch, or runs a hook.
- **Make a cutover atomic.** There is no such thing, for the same reason there
  is no exactly-once delivery.
- **Move stream consumer offsets between clusters.** RabbitMQ has no mechanism
  for it, so the tool says what will happen and makes you confirm.
- **Preserve `x-delivery-count`, dead-letter history or ordering across a
  drain.** A shovel republishes, and a republished message is a new message.
- **Replace the client libraries' drain.** It asks; the application decides.
- **Carry messages.** It talks to management APIs.
- **Support a broker that is not RabbitMQ.** Not today.

## Documentation

[acemq.org/acemq-infrastructure](https://acemq.org/acemq-infrastructure/), built
from [`docs/`](docs/) by `.github/scripts/build-docs-site.sh`.

## Licence

Apache-2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

RabbitMQ is a trademark of Broadcom Inc. This project is not affiliated with it.
