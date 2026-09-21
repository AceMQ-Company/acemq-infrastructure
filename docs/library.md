# The library

[Milestone one](roadmap.md) is written: the configuration model, the parser, the
validator, `probe()` against a real cluster, the default step list for each
operation, the planner, and the CLI over all of it. This page says what exists,
what the types are called, and — the part that matters most to whoever writes
phase 2 — what has deliberately been left alone.

Nothing here writes to a broker. That is not a limitation of this stage; it is
the deliverable, and the section on [how that is enforced](#how-writes-nothing-is-structural)
is the one to read if you read one part of this page.

## Modules

```
acemq-infra-parent          the reactor
├── acemq-infra-core        the deployment file, what is wrong with it, and the plan
├── acemq-infra-rabbitmq    probe(), over acemq-java-rabbitmq-admin
└── acemq-infra-cli         acemq-infra validate | plan
```

A reactor rather than one jar, for one reason that is worth stating plainly.
[Language and shape](shape.md) already committed to shipping a library and a CLI
in one release, so the CLI needs a module whatever else happens. The provider is
the interesting one: the validator and the planner are pure functions and have
to stay testable with no broker and no management client anywhere near them, and
the moment core and the RabbitMQ provider share a jar that is a convention
somebody has to remember rather than a fact the compiler knows.

The dependency runs one way and only one way — `cli` on both, `rabbitmq` on
`core`, and `core` on nothing but SnakeYAML. Maven refuses the cycle that would
be needed to reverse it.

The version in every pom is `0.1.0-SNAPSHOT` and stays that way. The release
version comes from the tag and is stamped with `versions:set`, which is how the
other repositories here avoid a number in a committed file that has to be
remembered and bumped.

## The packages, and what each one is not allowed to know

| Package | What is in it | What it must not depend on |
|---|---|---|
| `org.acemq.infra.yaml` | A node tree with the line numbers kept | Anything above it. It does not know what a deployment is |
| `org.acemq.infra.config` | The records, the parser, `${VAR}` interpolation | The validator, the planner, any broker |
| `org.acemq.infra.provider` | `Capability`, `Prober`, and the values a probe returns | The configuration model |
| `org.acemq.infra.validate` | The rules | Anything with an address in it |
| `org.acemq.infra.plan` | The default step lists, the planner, the plan | Anything that can write |
| `org.acemq.infra.provider.rabbitmq` | `probe()`, and the management client | — it is the bottom of the stack and the only thing with a socket |
| `org.acemq.infra.cli` | Two commands and the streams they write to | Nothing beyond what the two commands need |

The arrow from `validate` to `provider` is the one that was a decision rather
than an accident, and it is explained below. The one that is not there at all —
from `plan` to anything that can write — is the subject of its own section.

`org.acemq.infra.provider` does not know a deployment file exists, which is why
`probe()` takes a `ClusterAccess` (a name, a management URL, a vhost, a user and
a password) rather than the configuration model's `Cluster`. The cost is a
five-field translation in the CLI. What it buys is a probe that a Kubernetes
secret, or a test, can drive with no YAML document in the picture.

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

## The types, end to end

```java
DeploymentFile file = DeploymentFiles.load(path, Environment.system());
ValidationReport report = Validator.validate(file);

ProbedCluster blue = new RabbitProbe().probe(ClusterAccess.to(
        "blue", "https://blue.internal:15671", "/orders", user, password));
ProbedCluster green = new RabbitProbe().probe(greenAccess);

Plan plan = Planner.plan(file, blue, green);
System.out.print(plan.render());
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
  the tests that run after it. The CLI has a second implementation of it, and
  that turned out to be where the one open question from this page's first
  edition got answered — see [unset variables](#unset-variables-and-what-validate-does-about-them).
- **`ProbedCluster`**, what one cluster turned out to be: a product and version,
  the plugin-level facilities the summary prints, an `Inventory` of what was
  counted, one *verdict* per `Capability`, and the notes the probe refuses to
  guess at. A record, which is the decision the planner's testability rests on.
- **`Prober`**, one method, `ProbedCluster probe(ClusterAccess)`. The first of
  [the nine verbs](broker-agnostic.md), declared in the same change as the
  RabbitMQ implementation of it. The other eight are still absent, still on
  purpose, and each will arrive the same way.
- **`Planner.plan`**, a pure function: a validated file and two probed clusters
  in, a `Plan` out. `Plan.render()` is the text that goes in a pull request.
- **`DefaultSteps.of`**, the step list an operation gets when the file writes
  none — built out of the same `Step` and `Action` records a file parses into,
  so that the thing printed and the thing a file can say are the same thing.

## What `probe()` reads, and what it concludes

[Broker-agnostic, honestly](broker-agnostic.md) claims the capability model
earns its place on a single broker because a capability is missing for three
different reasons with three different fixes. The probe is that claim in code,
and the three are kept apart:

| Missing because | Established by | The fix |
|---|---|---|
| A plugin is not enabled | asking the endpoint and reading the status code | `rabbitmq-plugins enable`, a minute |
| The version is too old | the version string, read past its suffixes | an upgrade, which is often the cutover itself |
| The credentials are not permitted | the management user's tags | a tag on a user |

Every verdict carries the observation behind it and the plan prints the
observation, because the useful half of "`DRAIN_BY_SHOVEL` is missing" is the
sentence after it.

Two of the nine are conclusions rather than observations and say so:
`TOPOLOGY_IMPORT_MERGE` and `CONNECTION_CLOSE` are read off the user's tags,
because nothing short of writing can prove that a definitions import or a
connection close would be permitted, and a probe that proved it by doing it
would not be a probe.

The plugin check is the only thing in the provider that does not go through
`acemq-java-rabbitmq-admin`, and the reason is a deliberate decision in that
library rather than a gap in it. The management API answers **406** for an
endpoint whose plugin is absent, and the admin client maps that onto an empty
list so that "are there any shovels?" stays answerable on a broker that simply
has none. That is right for a client and useless for a probe: a cluster with the
plugin enabled and no shovels, and a cluster without the plugin, produce the
same empty list. So the probe asks `/api/shovels` and `/api/federation-links`
itself and reads the code. It is a GET whose body is discarded.

## How "writes nothing" is structural

The last clause of [the roadmap](roadmap.md)'s sentence for milestone one is
that `plan` writes nothing to either broker, and the point of the milestone is
that clause. It is arranged to be a fact rather than a discipline, in four
places:

1. **The planner has no client to write through.** It is in `acemq-infra-core`,
   which depends on SnakeYAML and nothing else. A line of code in the planner
   cannot name a method that writes to a broker, because no such method is on
   its classpath — and Maven refuses the cycle that would put one there.
2. **The broker is gone before the planner runs.** Its input is a
   `ProbedCluster`, which is a record: a snapshot taken once. A planner holding
   a live connection could go back and ask for more, and anything that can ask
   can be changed to tell.
3. **The probe holds a client that cannot write.** `RabbitAdmin` has about
   twenty methods that write — `declareShovel`, `putPolicy`,
   `importDefinitions`, `closeConnection`, `deleteQueue`. The probe never holds
   one. It holds a `ReadOnlyAdmin`, a wrapper with the writing half left off, so
   a future change that wanted to declare a shovel during a plan would have to
   add the method to a class whose name says why it should not.
4. **Nothing reads a clock or a working directory.** A `{{timestamp}}` in a
   backup path is printed as `{{timestamp}}`. That keeps the planner pure, and
   it is also what makes two plans diffable — which is the point of a plan going
   into a pull request at all.

An integration test asserts the outcome those four produce: take a real
cluster's definitions document, probe it four times over with two different
accounts, take it again, compare.

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

## Unset variables, and what `validate` does about them

The first edition of this page left one question to whoever wrote the CLI:
`${VAR}` is fatal when unset, `scripts/lint-deployment.py` treats the same
condition as a warning, and something has to decide what `validate` does in the
continuous integration where a deployment file is actually reviewed — an
environment that deliberately holds no production secrets and should not be made
to hold them.

**The two commands differ, because the two commands do different things next.**

`plan` authenticates against two brokers. An unset variable is fatal for it and
stays fatal, and every unset variable in the file is reported in one run rather
than one per run.

`validate` never opens a socket. Its job is "is this file well-formed and do its
rules hold", and that question has an answer whether or not the password is in
the room. So it substitutes each reference back in as its own text —
`${BLUE_PASSWORD}` stays `${BLUE_PASSWORD}` — reports every name it had to do
that for, and carries on:

```console
$ acemq-infra validate -f orders.yaml
orders.yaml: ok — blueGreen, 9 steps, 2 clusters, 1 warning
  warning: orders.yaml:25: deployment.steps: absent; the default step list ...
  warning: 6 variables are not set here and were left as written: BLUE_MGMT_URL, ...
           validate reads the file and never connects, so this checks its structure.
           plan needs them set: it authenticates. --require-variables makes this an error.
```

The alternative — making `validate` fatal too — is one behaviour rather than
two, which is worth something, and it was rejected for a specific reason. A
pull-request check could not then run it, so every pipeline would invent a set
of fake environment variables to get past a check about *structure*, and the
fakes would have to be maintained as the file grew. That is a worse trap than
two behaviours: a check that appears to be validating a file against the
environment it will run in, and is validating it against a fixture. And the
practical effect of refusing is that teams keep running the Python linter, which
does not refuse — leaving two implementations with no reason to agree, when
ending up with one was the entire point of moving the rules into Java.

So this is not a relaxation, and the flag says so: `--require-variables` makes
`validate` behave exactly as `plan` does, for the pipeline that *does* hold the
secrets and wants to prove a file resolves before a cutover window rather than
inside one.

Substituting the reference rather than an empty string matters for the same
reason: the rules being checked are about presence and shape, and an empty
string fails half of them for the wrong reason.
[Configuration](configuration.md) calls leaving the literal in place the old
format's bug, and it was a bug there because the value was then *used*. Here
nothing is ever dialled — the command that would dial it refuses to run without
the real thing.

## What is deliberately not here

- **The other eight verbs.** `snapshotTopology`, `applyTopology`,
  `listAttachments`, `detach`, `drain`, `mirror`, `measure` and `announce` are
  not written. [Broker-agnostic, honestly](broker-agnostic.md) is an argument
  that a seam extracted before its first implementation exists is the bad kind
  of seam, and the argument does not weaken because one verb has now been
  implemented. They arrive with the executor, each declared in the change that
  implements it.
- **`apply`.** Phase 1 stops at `validate` and `plan`. There is no subcommand
  for it and no flag that resembles one: an unimplemented command that prints
  "not yet" is worse than an absent one, because it is a thing somebody puts in
  a pipeline.
- **A canary's scope safety check.** [Canary](canary.md) requires that every
  consumer of a scoped queue belongs to one of the named services, and says the
  plan refuses when it does not. The plan currently *warns* that the check is
  phase 3's rather than performing it, because performing it properly means
  joining consumers to queues to connections to users and that join is the
  substance of the scope selector the roadmap puts in phase 3.
- **Rollback ordering rules.** The rollback's steps are checked structurally —
  ids, cluster references, guards with timeouts — but the ordering rules are
  not applied to them. A rollback legitimately drains with nothing having
  settled first, because the cutover it is undoing already stopped the producers
  and closed the consumers.
- **TLS options on the probe.** A cluster's `tls.verify` and `tls.caFile` are
  parsed, validated and not yet applied: `acemq-java-rabbitmq-admin` takes a
  URL and credentials, and the probe honours exactly what that client honours.
  The plan prints a note when the management URL is plain `http`, which is the
  half of it that can be observed rather than assumed.

## The rules, and the fixtures that keep them honest

The validator is `scripts/lint-deployment.py`'s rules in Java. The two
implementations still exist side by side, and what holds them in agreement is
that both run against the same six files: everything in `examples/` is accepted
by both, and everything in `examples/rejected/` is refused by both. They now
also agree about an unset `${VAR}`, which they did not before and which is
[the decision above](#unset-variables-and-what-validate-does-about-them).

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

## The step order, and where it comes from

The default list for `blueGreen` is [blue/green](blue-green.md)'s ten-step
sequence, less the `probe` step, which becomes the summary block at the top of
the plan because a reader wants both clusters' versions and plugins before the
first thing that would touch them. `backup` is the other way round: a block in
the file rather than an action, and a numbered step in the plan, because it
happens at a particular moment and a reader is entitled to know when.

```
  probe            requires: the capabilities, asserted before anything happens
  1 backup         the source's definitions, credentials redacted
  2 topology       the shape, WITHOUT policies and operatorPolicies
  3 announce-drain the envelope, immediately before the first wait
  4 pause-producers   publishRate = 0
  5 drain-consumers   unacked = 0, then close
  6 drain-messages    the shovel, then depth = 0
  7 policies       NOW the retention rules, onto a cluster that holds the backlog
  8 switch-endpoint   external, or the hook
  9 verify         green has consumers
```

Four of those are decisions rather than mechanics, and all four are asserted
position by position in the tests rather than as a set:

- **The topology copy is split in two with the drain between the halves.** An
  imported definitions document applies its policies immediately, so a
  `message-ttl` landing at step 2 is live on an empty cluster long before step 6
  starts shovelling a backlog into it. This is the arrangement that no
  arrangement of `useDefinitions`/`removeConnections`/`createShovels` could
  express, which is the whole reason [`steps:` replaced them](configuration.md).
- **Producers stop before consumers close**, because work left behind a closed
  consumer has to be moved by a shovel, and a shovel republishes.
- **`announce` is step 3 and not step 1**, because it is advisory and its only
  job is to give applications a head start on the guard immediately after it.
- **`verify` is a step**, because a cutover whose endpoint did not move looks
  exactly like one that worked until the next incident.

The plan does not transcribe "applied at step 7" from the documentation; it
looks for the later step whose `copyTopology` includes what this one excluded
and prints *that* number, and says "and no later step applies them" when there
is none. A reader uses that number to check that the rules really do land after
the drain, so it has to be true of the file in front of them rather than of the
worked example. ([The roadmap](roadmap.md)'s own sample says "applied at step 6"
where its `policies` step is step 7. That is the kind of thing a computed number
does not get wrong.)

`canary` is the same list at a smaller scope — the same machinery, the same
guards, the same rollback, with the scope threaded into the drain's queues and
the close step's users. `mirror` is four steps and no drain, and it is the one
place the topology split does *not* apply: the split protects a backlog that is
about to be delivered, a mirror never delivers one, and a shadow cluster whose
policies differ from the source's is not a comparison worth making.

## What turned out to be underspecified

Two things in the documented format, found by trying to implement it rather than
read it, and three more found by running the result against real brokers.

**`streams:` is a documented top-level key with no documented body.** The linter
accepts it and checks nothing inside; [the roadmap](roadmap.md)'s worked plan
says "streams.acknowledged is set"; [canary](canary.md) calls it "the
`streams.acknowledged` confirmation". No page writes the block out. The model
holds a single boolean, which is the smallest thing that satisfies all three,
and it will need revisiting when stream handling arrives in phase 3.

The consequence reached the plan output. The roadmap's worked warning ends
"consumers restart at `next`", which is a `streams.restartAt` the model does not
have, so the plan says what [message state](message-state.md) can support
instead — that every consumer restarts at whatever its `x-stream-offset` says.
A stream in the drain's scope with no `streams.acknowledged: true` in the file
is a **refusal**, not a warning, because the consequence — a week of
reprocessing, or a silent gap — is not visible in the estate afterwards.

**A queue pattern's syntax is not written down anywhere.**
`queues: ["orders.*", "!orders.audit"]` appears in three pages and no page says
whether `*` is a glob or a regular expression. They are read as globs, because
under a regular expression `orders.*` also matches `ordersXaudit` and `orders` —
the dot being a wildcard — and the file's author means the `orders` family. The
one it would wrongly take is an audit queue.

**`/api/federation-links/<vhost>` answers 400 on a broker without the plugin.**
The route does not exist, so the encoded vhost is read as something else, and
400 is not a sentence anything can conclude from. The unscoped path answers a
clean 404. Found by the integration test against the stock image; no amount of
reading the management API's documentation would have produced it.

**`/api/users` and `/api/permissions` answer 401 to a monitoring account.** The
first probe of exactly the estate the capability model exists for therefore died
with an exception instead of reporting what it found. A count that cannot be
taken is now nought and a note saying so, because a plan reading "0 users" where
the credentials could not look is indistinguishable from a cluster that has
none.

**Some vocabularies are closed and some only look closed.** `operation`,
`semantics`, `onTimeout` and `endpoint.kind` are written out in
[configuration](configuration.md) as a list of alternatives, so they are enums
and a word outside them is refused. `ackMode`, `deleteAfter` and a connection
selector's `role` each appear exactly once, as one example, with no list — so
they are kept as the text the file wrote. Closing a set the documentation has
not closed would mean refusing a spelling the format may well allow, and a
parser is a bad place to invent a format.
