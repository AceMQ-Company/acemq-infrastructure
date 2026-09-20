# Changelog

All notable changes to this repository are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Nothing has been released and nothing is tagged. The first version will be
`0.1.0`, and it will be [milestone one](docs/roadmap.md) — `validate` and
`plan`, and nothing that writes to a broker.

## [Unreleased]

### Added

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

- No product code. An executor that pretends to deploy would be worse than
  nothing; see `docs/roadmap.md` for what gets built first and why it is the
  half that cannot break production.
- No provisioning. Terraform and the RabbitMQ Cluster Operator make clusters.
- No second broker provider. Writing one speculatively would make the seam
  worse rather than better.
