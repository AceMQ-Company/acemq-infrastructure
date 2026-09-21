# The library

The first half of [milestone one](roadmap.md) is written: the configuration
model, the parser, and the validator. This page says what exists, what the types
are called, and — the part that matters most to whoever writes the next half —
what has deliberately been left alone.

Nothing here touches a broker. That is not a limitation of this stage; it is the
property the validator has to keep.

## Modules

```
acemq-infra-parent          the reactor
└── acemq-infra-core        the deployment file, and what is wrong with it
```

Two more follow, and the shape of the reactor is chosen so that they land
without moving anything:

```
    acemq-infra-rabbitmq    probe() and the verbs, over acemq-java-rabbitmq-admin
    acemq-infra-cli         acemq-infra validate | plan, native-imaged
```

A reactor rather than one jar, for one reason that is worth stating plainly.
[Language and shape](shape.md) already committed to shipping a library and a CLI
in one release, so the CLI needs a module whatever else happens. The provider is
the interesting one: the validator is a pure function over a parsed file and has
to stay testable with no broker and no management client anywhere near it, and
the moment core and the RabbitMQ provider share a jar that is a convention
somebody has to remember rather than a fact the compiler knows.

The version in every pom is `0.1.0-SNAPSHOT` and stays that way. The release
version comes from the tag and is stamped with `versions:set`, which is how the
other repositories here avoid a number in a committed file that has to be
remembered and bumped.

## The packages, and what each one is not allowed to know

| Package | What is in it | What it must not depend on |
|---|---|---|
| `org.acemq.infra.yaml` | A node tree with the line numbers kept | Anything above it. It does not know what a deployment is |
| `org.acemq.infra.config` | The records, the parser, `${VAR}` interpolation | The validator, the planner, any broker |
| `org.acemq.infra.provider` | `Capability` — and, next, `probe()` and the verbs | The configuration model |
| `org.acemq.infra.validate` | The rules | Anything with an address in it |

The arrow from `validate` to `provider` is the one that was a decision rather
than an accident, and it is explained below.

## Line numbers, and why the file is not databound

Every record carries a `Location` — the file and, where it is known, the line —
and so does every finding the validator produces. That costs a hand-written
mapping layer over SnakeYAML's composer instead of four lines of databinding,
and it buys two things.

A deployment file lands in a pull request days before it runs and is read by
people who were not in the room when it was written. An error that says
`deployment.steps[5]: something` makes that reader count list entries by hand;
one that says `orders.yaml:107` puts their cursor on the line. That is the whole
argument for the format existing at all, applied to the tool that reads it.

The second reason is the one [language and shape](shape.md) warned about when it
chose Java. A native image takes reflection away, a databinder *is* reflection,
and every record would need a registration whose absence fails in the field
rather than in the build. Nothing here is constructed reflectively, so there is
nothing to register.

## Two kinds of wrong

Reading a deployment file has two halves and the line between them is worth
knowing, because most of the interesting rules are on the far side of it.

**`ConfigException` means the file could not be represented.** Malformed YAML; a
field that must be a mapping and is a list; a word outside a vocabulary
[configuration](configuration.md) writes out as `a | b | c`; a duration that is
not a duration; a `${VAR}` that is not set. There is no model on the other side
of any of those, so there is nothing for a rule to run against. Every problem
found is collected and thrown together — a file with four unset variables should
say so once, not over four runs.

**Everything else is a `Finding` from the validator.** A required field that is
absent, a cluster name that refers to nothing, a drain in the wrong place. All
of those produce a model perfectly well.

Absence in particular is a validation matter and not a parse one, and
`semantics:` is why. It is the most consequential thing a file can leave out,
and it deserves the validator's sentence about what the two values mean rather
than a parser's sentence about a missing key.

## The types the next half builds on

```java
DeploymentFile file = DeploymentFiles.load(path, Environment.system());
ValidationReport report = Validator.validate(file);
```

- **`DeploymentFile`** and the records under it — `Cluster`, `Endpoint`,
  `Deployment`, `Step`, `WaitFor`, `MirrorSpec`, `Rollback`. Almost every field
  is an `Optional`, because the model has to be able to hold a file that is
  wrong; that is what lets the validator say which field and which line instead
  of failing at the first missing key.
- **`Action`**, sealed over the seven things a step can do: `Requires`,
  `CopyTopology`, `Announce`, `CloseConnections`, `Drain`, `Mirror`, `Switch`.
  Sealed so that the planner can switch over every one of them and the compiler
  says when a new one has been added and somewhere has not handled it.
  `Drain` and `Mirror` are separate types and not one type with a `mechanism:`
  field, for the reason [message state](message-state.md) gives at length.
- **`Validator.validate`**, a pure function: a parsed file in, a
  `ValidationReport` out. No clock, no filesystem, no connection.
- **`Environment`**, where `${VAR}` is looked up. An interface rather than a
  direct call to `System.getenv` so that the substitution can be tested without
  setting process environment variables, which is a thing a test cannot undo for
  the tests that run after it.

## Where the capability set lives, and why

`Capability` is in **`org.acemq.infra.provider`**, not beside the configuration
model, and the dependency runs one way: the validator reads it, and nothing in
that package knows a deployment file exists.

The vocabulary has two users who never meet. A file names capabilities in a
`probe` step's `requires:` list, and the validator checks those names with no
broker in sight. A provider's `probe()` returns the set a live cluster turned
out to have. [Broker-agnostic, honestly](broker-agnostic.md) draws the seam as
the verbs *and* the capability set together, and that is the right place for it:
one enum means a capability cannot be spelled one way in a file and another way
in a probe result, which is exactly how a capability model becomes decoration.

So the provider module adds `probe()` next to a vocabulary that is already
there, rather than introducing one and hoping the validator's copy agrees.

## What is deliberately not here

- **`probe()` and the other eight verbs.** Not written, on purpose.
  [Broker-agnostic, honestly](broker-agnostic.md) is an argument that a seam
  extracted before its first implementation exists is the bad kind of seam, and
  writing the interface a week before the thing that implements it would be
  exactly that. The verbs land with the RabbitMQ provider.
- **The default step lists.** `plan` fills in the list for the chosen operation
  when `steps:` is absent and prints it in full. The validator warns when a file
  leaves them out and refuses an *empty* list, which is the distinction the
  model keeps — `Optional<List<Step>>`, where absent and empty are different
  questions.
- **The planner.** Configuration plus two probed clusters in, an ordered plan
  out. The records and the sealed `Action` are shaped for it; nothing has been
  guessed at on its behalf.
- **The CLI.** `acemq-infra validate` and `acemq-infra plan`, and nothing else.
  Note that `${VAR}` is fatal when unset, which is a decision the CLI inherits:
  a `validate` run in an environment that deliberately has no production secrets
  will need them supplied. `scripts/lint-deployment.py` treats the same
  condition as a warning, which is right for a linter and wrong for a tool that
  is about to authenticate.
- **Rollback ordering rules.** The rollback's steps are checked structurally —
  ids, cluster references, guards with timeouts — but the ordering rules are
  not applied to them. A rollback legitimately drains with nothing having
  settled first, because the cutover it is undoing already stopped the producers
  and closed the consumers.

## The rules, and the fixtures that keep them honest

The validator is `scripts/lint-deployment.py`'s rules in Java. The two
implementations exist side by side until the CLI ships, and what holds them in
agreement is that both run against the same six files: everything in
`examples/` is accepted by both, and everything in `examples/rejected/` is
refused by both.

The Java suite asserts the rejections *by message and by field*, not by "it was
refused". A rejected file with three deliberate mistakes in it is still refused
when two of the three rules have been broken, and that is the state the suite
has to be able to see.

One of those rules is a correctness finding rather than a transcription of the
schema, and losing it would be a regression in judgement rather than in code: a
mirror that names queues asks for queue federation, which pulls from its
upstream only when the upstream has no local consumers. A mirror built that way
sits empty while the source is healthy and starts draining it the moment the
source's consumers stop — which is exactly what a cutover does, deliberately. It
is an accidental drain that fires precisely when the rollback it was preserving
is needed. `examples/rejected/mirror-by-queue-federation.yaml` is that file, and
[message state](message-state.md) has the table.

## What turned out to be underspecified

Two things, found by trying to implement the format rather than read it.

**`streams:` is a documented top-level key with no documented body.** The linter
accepts it and checks nothing inside; [the roadmap](roadmap.md)'s worked plan
says "streams.acknowledged is set"; [canary](canary.md) calls it "the
`streams.acknowledged` confirmation". No page writes the block out. The model
holds a single boolean, which is the smallest thing that satisfies all three,
and it will need revisiting when stream handling arrives in phase 3.

**Some vocabularies are closed and some only look closed.** `operation`,
`semantics`, `onTimeout` and `endpoint.kind` are written out in
[configuration](configuration.md) as a list of alternatives, so they are enums
and a word outside them is refused. `ackMode`, `deleteAfter` and a connection
selector's `role` each appear exactly once, as one example, with no list — so
they are kept as the text the file wrote. Closing a set the documentation has
not closed would mean refusing a spelling the format may well allow, and a
parser is a bad place to invent a format.
