# Changelog

All notable changes to this repository are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`0.1.0` is released: [milestone one](docs/roadmap.md), which is `validate` and
`plan` and nothing that writes to a broker. What is unreleased below is
[phase 2](docs/roadmap.md) — the executor, `apply`, and the rollback that a test
actually runs.

## [Unreleased]

### Added

- **`acemq-infra-execute`, the executor.** A fourth module, and it is the one
  that writes. Every action in the default blue/green list is carried out
  against real clusters — `Requires`, `CopyTopology`, `Announce`,
  `CloseConnections`, `Drain`, `Mirror` and `Switch`, with `backup` as the block
  it is rather than an action. It runs in one of two modes and there is no
  default: `Run.of(file)…cutover()` writes, `Run.of(file)…rehearsal()` does not,
  and a rehearsal wraps both brokers in a decorator that throws on every writing
  verb, so a step that forgot which mode it was in fails loudly instead of
  quietly writing to production.
- **The other eight verbs of [the provider seam](docs/broker-agnostic.md)**,
  each declared in the change that implements it, as `probe()` was:
  `snapshotTopology`, `applyTopology`, `listAttachments`, `detach`, `drain`,
  `mirror`, `measure` and `announce`. The destructive ones are shaped so they
  cannot be reached by accident — `detach` closes one named connection rather
  than a selector's worth, a drain refuses to be built with an empty queue list,
  and a mirror has no queue field at all.
- **Guards that can say they cannot see.** A condition is satisfied, not yet, or
  **unobservable**, and the third one fails immediately rather than waiting out
  a timeout and then honouring `onTimeout`. A guard whose condition was never
  read has not failed, and `continue` has no honest answer for it. `publishRate`
  is the case that made this necessary: it comes from a `message_stats` block
  that a broker with `rates_mode = none` simply does not have, and an absent
  block is not a publish rate of nought.
- **A settle window over the lagging statistics.** The management API's depths
  and counts refresh on `collect_statistics_interval` rather than on every
  publish — `scripts/blue-green-lab.sh` found its own `seed` reporting zeroes
  for queues it had just filled — so a guard is satisfied by a run of readings
  spanning fifteen seconds rather than by one, and a guard whose timeout is
  shorter than that window is refused before the run starts. A drain's guard
  also waits for the shovel to have torn itself down, which is a fact about the
  movement rather than a number out of the statistics database.
- **A preflight that refuses the run rather than the step.** A missing
  capability, a cluster the file names and the run has not got, a drain whose
  patterns select nothing, a guard with no timeout, an external endpoint switch
  in a run with nobody watching: all of them stop the cutover before the first
  write. Each would otherwise surface at the step that hit it, with the drain
  done and the source empty.
- **Rollback, derived from what actually happened.** Not from the plan: a
  cutover that stopped before the drain has moved nothing, and a rollback built
  from the plan would declare a shovel on a cluster that does not need one. The
  endpoint goes back first and the drain runs the other way, which is
  [the documented order](docs/blue-green.md). A topology copy, a connection
  close, a mirror and an announcement are deliberately not inverted, and the
  code says why for each.
- **A writing management client that does not weaken the read-only one.**
  `ReadOnlyAdmin` in `acemq-infra-rabbitmq` is untouched and that module still
  contains no method that writes to a broker — not none that are called, none
  that exist. The writing client is a different class with a different name in a
  different module, so `plan` remains structurally incapable of writing: every
  arrow still points at `acemq-infra-core`, which still has no broker client on
  its classpath.

- **`acemq-infra apply -f FILE`.** The third and last command, and the one that
  can break production. It probes both clusters, prints the plan it is about to
  carry out, **stops, and asks** — and the only thing that gets past that
  question is the word `yes`, typed at a terminal. Not `y`, and not the return
  key: the default a tired hand produces has to be the one that leaves the
  estate alone. Nothing has been written when the question is asked, and a
  cluster with no `amqp:` URI is refused here rather than warned about, because
  a drain is declared inside one broker and dials the other.
- **`--yes`, which consents to the run starting and to nothing after it.** Two
  steps in a cutover stop and ask — a guard whose `onTimeout` is `prompt`, and
  an `endpoint: external` switch — and those are answered by the terminal, or by
  `Console.unattended` when there is no terminal, and **no argument on the
  command line constructs either one**. Whether anybody is watching is a fact
  about the process, decided in one place from what `System.console()` answers,
  so no flag can claim a person is present who is not. A pipeline that passes
  `--yes` at a file with an external endpoint switch gets a run the executor
  refuses in preflight, before the first write. A run whose steps ask nobody
  anything needs no terminal and completes in a pipeline, which is why the rule
  is not "apply needs a terminal".
- **`--dry-run`, which probes rather than replays.** `plan` is a function of two
  probe snapshots; `--dry-run` re-probes both clusters and then rehearses every
  step against them — reading the topology, listing the connections, measuring
  the queues — so a guard reports what its condition is *at this moment* rather
  than what it will wait for. It runs over `Run…rehearsal()`, which wraps both
  brokers in a decorator whose writing verbs throw. `--yes` with `--dry-run` is
  refused rather than ignored: the two words together describe a situation that
  does not exist.
- **A terminal that cannot be faked, on three JDKs.** The check is not a null
  test on `System.console()`: up to 21 that method answers null when the streams
  are redirected, and from 22 it answers a console either way and adds
  `isTerminal()` to tell them apart. This repository builds on 17, 21 and 25, so
  a null check alone would report a scheduled job on 25 as a person at a
  keyboard.
- **The cutover-then-rollback integration test**, which is the point of this
  phase. Two RabbitMQ containers on a shared network — separate clusters rather
  than two nodes of one, as `scripts/blue-green-lab.sh` argues — seeded, cut
  over, and then rolled back with the list derived from what the cutover
  actually did. It asserts the topology copied, the policies landed after the
  drain, the queue the patterns excluded never moved, and every message back on
  blue and accounted for by identity. And it **counts what was duplicated**:
  an application arrives on green when the endpoint switches, is handed a
  prefetch it never settles, and those messages are carried back and handled a
  second time. The suite prints the figure and asserts it is not nought — 25 of
  120, a fifth of the backlog processed on both clusters. That number is the
  honest price of a rollback and somebody is entitled to it before they trust
  one.

### Fixed

- **The management API refused the first request with a body.** The JDK's
  `HttpClient` defaults to HTTP/2 and reaches it over cleartext by asking the
  server to upgrade, and against RabbitMQ's management listener that handshake
  fails whenever the request carrying it has a body — `EOF reached while
  reading`, with nothing on the broker's side to look at, while `curl` against
  the identical URL answers 200. A `GET` works, and once any `GET` has opened
  the connection the `POST` that reuses it succeeds, so the failure is exactly
  the first body-carrying request on a fresh client: in a blue/green run, the
  announcement at step 3. The client is pinned to HTTP/1.1. Found by the cutover
  integration test on the first run that got as far as announcing, which is the
  kind of thing no amount of reading the management API's documentation would
  have produced.

### Not done, deliberately

- **No `rollback` subcommand.** `Rollbacks.derive` is a library call and the
  integration test drives it. The missing piece is not the derivation but where
  the record of what a run did would live between two commands — a rollback is
  derived from the steps that reached `done`, so a second process would have to
  be handed that list rather than the file. Inventing a state file is a decision
  worth making deliberately rather than as a side effect of adding a verb.

## [0.1.0] - 2026-09-21

**This release plans a cutover and writes nothing to any broker.** That is
milestone one in a sentence, and the last clause is the point: it is the half
that cannot break production, so it can be pointed at live clusters from the
first day. `apply` is phase 2 and is deliberately not here.

### Added

- **`acemq-infra plan`.** Reads two real RabbitMQ clusters and prints exactly
  what a cutover would do, step by step, with the capabilities each step needs
  and the guards it will wait on — and writes nothing to either broker. That
  last clause is [milestone one](docs/roadmap.md), and it is structural rather
  than careful: the planner is in a module with no broker client on its
  classpath, its input is a snapshot record rather than a connection, and the
  probe holds a read-only wrapper with the management client's writing half left
  off. An integration test takes a cluster's definitions document, probes it
  four times over, takes it again, and compares.
- **`acemq-infra validate`**, the validator behind a command. An unset `${VAR}`
  is reported and the file is still checked, because `validate` never connects
  to anything; `--require-variables` makes it an error, which is what `plan`
  does unconditionally. `scripts/lint-deployment.py` and the Java validator now
  agree on that condition as well as on the six example files.
- **`probe()` over `acemq-java-rabbitmq-admin`**, in `acemq-infra-rabbitmq`:
  version, plugins, permissions and the capability set they add up to. A
  capability is missing for one of three reasons with three different fixes — a
  plugin to enable, a version to upgrade, a tag on a user — and every verdict
  carries the observation behind it, because the useful half of
  "`DRAIN_BY_SHOVEL` is missing" is the sentence after it.
- **The default step list for each operation**, expanded and printed in full, in
  the same `Step` and `Action` records a file parses into — so the list printed
  is a list that can be pasted back into the file, and a test validates it to
  prove it. The blue/green order is asserted position by position, because the
  split of the topology copy around the drain is the bug the format exists to
  prevent.
- **The planner**, a pure function from a validated file and two probed clusters
  to an ordered, diffable plan. Nothing in it reads a clock, which is both what
  keeps it pure and what makes two plans comparable in a pull request.
- **The design.** Eleven documents under `docs/`, covering what this is, the
  language and shape decisions, the provider seam, blue/green, canary, message
  state, the configuration format, the roadmap, and the prior art.
- **A configuration format**, evolved from the C# implementation at
  `felipeg48/infra-blue-green-deployment`. It keeps that format's named
  `clusters:` map, its `${VAR}` interpolation and its announcement envelope, and
  replaces the three overlapping `type`/`strategy`/`mode` fields with a single
  `operation:`, the assembly-qualified handler name with `provider: rabbitmq`,
  and the implied ordering of `useDefinitions`/`removeConnections`/
  `createShovels` with an explicit `steps:` list.
- **`scripts/blue-green-lab.sh`** — two separate single-node RabbitMQ clusters
  in Docker, with `seed`, `status`, `definitions`, `connections`, `drain`,
  `mirror` and `reset`. Two clusters rather than one two-node cluster, because a
  cutover moves between clusters that share nothing.
- **`scripts/lint-deployment.py`** — checks a deployment file against the
  documented format without touching a broker. Enforces the structural rules
  that cost messages: retention policies copied before a drain, a drain with no
  guard waiting on unacked, any spelling of a `percentage` key in a canary, and
  a missing `semantics`.
- **`examples/`** — worked `blueGreen`, `canary` and `mirror` configurations,
  plus `examples/rejected/`, three deliberately-broken files that CI asserts are
  rejected. That assertion is what proves the linter's rules are live rather
  than merely written.
- **The documentation site** — `docs/*.md` rendered by
  `.github/scripts/build-docs-site.sh` through pandoc, with a source-side link
  check (cross-page links are written as `.md` so the pages read correctly on
  GitHub) and a rendered link-and-anchor check.
- CI, the attribution guard, `SECURITY.md` and `NOTICE`.

### Decided

- **Java 17, distributed as a GraalVM native image.** `acemq-java-rabbitmq-admin`
  already implements every RabbitMQ operation a cutover needs, and carries the
  broker knowledge a fresh client would rediscover one incident at a time. The
  cost is a second build with its own failure mode, three release runners
  instead of one, and a binary several times Go's size. See `docs/shape.md`.
- **A core library with a CLI over it, in one release; an operator later or
  never.** They are layers rather than alternatives, which is what kept this
  item in Backlog. A cutover is a process with a deliberately half-moved middle
  and models badly as reconciled desired state.
- **Blue/green and canary are separate operations.** A broker canary is not a
  traffic split: splitting producers by percentage partitions the queue across
  two clusters. The format has no percentage field and the linter rejects one.
- **RabbitMQ is the only provider**, and the capability model earns its place on
  that one broker, because a cluster's capabilities depend on its version, its
  enabled plugins and its credentials.
- **A mirror federates exchanges, never queues.** Found by running the lab
  rather than by reasoning: queue federation pulls from its upstream only when
  the upstream has no local consumers, so it is a conditional move and not a
  copy. A mirror built from it stays empty while the source is healthy and
  begins draining the source the moment it is not — the opposite of what a
  mirror is for. The format offers no way to express it, the validator rejects
  `mirror.queues`, and `docs/message-state.md` carries the distinction between
  a shovel, a federated exchange and a federated queue.

### Not done, deliberately

- No `apply`, and no flag that resembles one. An executor that pretends to
  deploy would be worse than nothing, and an unimplemented subcommand printing
  "not yet" is a thing somebody puts in a pipeline. It is phase 2; see
  `docs/roadmap.md`.
- No verbs beyond `probe()`. The other eight all write, and each will be
  declared in the same change that implements it rather than a phase early.
- No provisioning. Terraform and the RabbitMQ Cluster Operator make clusters.
- No second broker provider. Writing one speculatively would make the seam
  worse rather than better.
