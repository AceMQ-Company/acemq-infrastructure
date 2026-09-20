# Roadmap

Six phases. The first is this repository and it is finished. The second is
milestone one and it deliberately cannot break anything.

## Phase 0 — the plan (this repository)

What you are reading. A design that somebody can disagree with in writing, a
configuration format, a development lab, and a list of things the tool will
never do.

**Done when** the documents exist and have been argued with. That is a real
completion criterion: the value of this phase is that the
[decisions](shape.md) are on paper and can be attacked before any code depends
on them.

**Deliverables:** `docs/`, the site, `scripts/blue-green-lab.sh`,
`scripts/lint-deployment.py`, `examples/`.

## Phase 1 — plan, not apply (milestone one)

**`acemq-infra plan -f deployment.yaml` reads two real RabbitMQ clusters and
prints exactly what a cutover would do, step by step, with the capabilities each
step needs and the guards it will wait on — and writes nothing to either
broker.**

That is milestone one in one sentence, and the last clause is the point.

What gets built:

- The configuration model — records, parsed from the YAML in
  [configuration](configuration.md), with `${VAR}` interpolation that fails on
  an unset variable.
- The validator, which is `lint-deployment.py`'s rules moved into Java and
  given a broker-free test suite.
- `probe()` against both clusters through
  [`acemq-java-rabbitmq-admin`](https://acemq.org/acemq-java-rabbitmq-admin/):
  version, plugins, permissions, and the resulting
  [capability set](broker-agnostic.md).
- The default step list for each operation, expanded and printed.
- The planner: configuration plus two probed clusters, in; an ordered,
  diffable, human-readable plan, out. A pure function, which is why it can be
  tested properly.
- `acemq-infra validate` and `acemq-infra plan`, and nothing else.

What it looks like:

```console
$ acemq-infra plan -f orders.yaml
orders-blue-green — blueGreen, blue → green, semantics=atLeastOnce

  probe             blue  RabbitMQ 3.13.7  shovel✓ federation✓ streams✓
                    green RabbitMQ 4.0.5   shovel✓ federation✓ streams✓
                    all 4 required capabilities present

  1 backup          blue definitions → ./backups/orders-blue-green-....json
                    credentials redacted
  2 topology        14 exchanges, 31 queues, 58 bindings, 6 users, 12 permissions
                    policies EXCLUDED — applied at step 6
  3 announce-drain  publish to orders.events / deployment.started
  4 pause-producers wait: blue publishRate=0, 2m, on timeout PROMPT
  5 drain-consumers close 8 connections (orders-service), after unacked=0
                    wait 5m, on timeout ABORT
  6 drain-messages  shovel blue → green, 29 queues (orders.audit excluded)
                    27,412 messages to move
                    wait: blue depth=0, 15m, on timeout ABORT
  7 policies        4 policies, 1 operator policy
  8 switch-endpoint EXTERNAL — will stop and wait:
                    "orders-amqp.internal is a CNAME switched by the platform team"
  9 verify          wait: green consumers>=1, 5m

warnings
  · 2 streams in scope (audit.events, audit.events.raw). Offsets do not travel
    between clusters. streams.acknowledged is set; consumers restart at `next`.
  · a shovel republishes: x-delivery-count resets and x-death is erased on all
    27,412 messages.
  · rollback drains green → blue. Blue's queues will be empty after step 6.

nothing was written. run `acemq-infra apply -f orders.yaml` to execute.
```

**Why this first.** Three reasons, and the third is the real one.

It is the half that cannot break production, so it can be built and used
against live clusters from the first week — which is the only way the model gets
corrected by reality rather than by argument.

It is the artifact that makes everything else reviewable. A plan in a pull
request is the whole jreleaser-shaped idea, and it is useful on its own even if
the executor never ships: a team that reads that output and then runs their
existing runbook has still had the tool find their missing shovel plugin and
their stream problem.

And building the planner forces every hard question to be answered on a screen,
in daylight, rather than at 3am. What does drain mean for this queue. Which
capability is missing. What happens to the stream. The planner cannot be written
without answering them, which is exactly why it goes first.

## Phase 2 — apply, for blue/green, on RabbitMQ

The executor. Every step type in the default blue/green list, driven against
real brokers, with `--dry-run` re-running the plan against the live clusters and
reporting what each step *would* do at this moment.

Rollback is built in this phase and not later, because a cutover without a
tested rollback is not finished. It gets its own integration test: run a
cutover, run the rollback, assert the estate is where it started and count what
was duplicated.

The integration tests run against `scripts/blue-green-lab.sh`, which is why the
lab exists in phase 0.

## Phase 3 — canary and mirror

The two other operations, as [separate operations](canary.md) sharing the
executor. The scope selector and its safety check — that every consumer of a
scoped queue belongs to a named service — is most of the work, and it is the
check that keeps a canary from becoming a partition.

Stream handling lands here too: the refusal, the per-consumer projection of what
each offset setting will do, and the `streams.acknowledged` confirmation.

## Phase 4 — distribution

GraalVM native images for linux-amd64, linux-arm64 and darwin-arm64, published
to the acemq.org GitHub-hosted feeds with the library as a Maven artifact
alongside. A GitHub Action wrapping the binary.

The CI requirement from [language and shape](shape.md) applies from the first
native build: **the test suite runs against the binary, not the jar.** A green
jar proves nothing about the artifact users get.

## Phase 5 — the operator, conditionally

Only if somebody asks with a real estate behind the ask. Same core library, same
configuration schema, a reconcile loop and a CRD. The design problem to solve
first is stated in [language and shape](shape.md): a cutover is a process with a
deliberately half-moved middle, and reconciliation is a bad model for that. A
controller restart must not restart a drain.

If nobody asks, this does not get built, and that is a successful outcome rather
than an incomplete one.

## What is explicitly not on this roadmap

- **Provisioning.** Not in any phase. Terraform and the Cluster Operator make
  clusters.
- **A second broker provider.** Not until there is an estate that needs it.
  Writing one speculatively would make the seam worse, not better.
- **A web UI.** The plan output is text because text goes in pull requests.
- **Scheduling.** The tool runs when something runs it.
- **Monitoring.** It measures during a cutover and stops when the cutover ends.

## Where this sits

On the board this is one item that has been in Backlog since the project
started, described in a single sentence. Phase 0 turns it into nine documents
and two scripts. Phase 1 is the first thing that would carry a version number,
and it would be `0.1.0` — the same starting point every other repository here
used, for the same reason: the API will change once real estates have argued
with it.
