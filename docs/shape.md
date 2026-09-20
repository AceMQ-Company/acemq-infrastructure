# Language and shape

Two decisions, both of which have been open long enough to stop the work
starting. This page closes them, with the reasons, and with what each choice
costs.

## The recommendation, first

**Java 17, built to a GraalVM native image for distribution. A core library, a
CLI over it in the same repository and the same release, and an operator later
or never.**

The rest of this page is why, and what that gives up.

## Language: Java or Go

The owner's brief said "Java or Go or anything suitable", which is the right way
to ask it. Both are suitable. They fail differently.

### The case for Java is one library

[`acemq-java-rabbitmq-admin`](https://acemq.org/acemq-java-rabbitmq-admin/)
already implements, tested against a real broker, every RabbitMQ operation a
cutover needs:

| What a cutover needs | What the library already has |
|---|---|
| Copy a cluster's shape | `exportDefinitions()` / `importDefinitions()` — and the import is a **merge**, which is exactly right for a target that is already partly configured |
| Move what is left behind | shovel declare and delete |
| Copy without consuming | federation upstreams and links (exchange federation) |
| Retention and delivery rules | policies and operator policies |
| Who is still attached | connections, channels, consumers — and close |
| Is it empty yet | queue depth, consumer count, and the queue arguments AMQP will not tell you |
| Fence the target | vhost and user limits |

Writing that again in Go is perhaps three or four thousand lines of HTTP client
and JSON model. That is not the expensive part. The expensive part is that the
existing library carries the knowledge of things the broker does that nobody
guesses: that the default vhost has to arrive as `%2F` and that `URLEncoder`
turns a space into a plus on the way; that RabbitMQ 4 records `x-queue-type` on
every queue where 3.x did not; that an h2c upgrade breaks every request with a
body. Each of those was a bug once. A fresh Go client rediscovers them one
incident at a time, and it rediscovers them during a cutover, which is the worst
place available.

There is a second cost, and it is the one that does not go away: two management
clients in two languages **drift**. The drift will not show up as a compile
error. It will show up as a shovel the Go orchestrator declared in a shape the
Java library reads back as something else. A capability the estate depends on
during its most dangerous operation should have exactly one implementation.

### The case for Go is one binary

Go's advantage is real and it is not about the language. It is that
`GOOS=linux GOARCH=amd64 go build` produces a 12MB static binary from any
machine, and that binary runs in a scratch container with nothing installed. For
a CLI that gets dropped into a pipeline, that is close to the whole product
experience.

Java's answer is GraalVM, and the important thing about that answer here is that
it is not theoretical. `acemq-java-amqp` already ships native-image metadata and
already builds one; the toolchain is proven in this workspace, on this team, on
code that talks to this broker. A native image of this tool is a single
executable with no JVM to install and a start-up measured in tens of
milliseconds, which is what a jreleaser-shaped CLI wants.

So the binary argument narrows to *how much harder* GraalVM is than `go build`,
and the honest answer is: meaningfully harder, in three specific ways.

### What choosing Java costs

1. **The native image is a second build with its own failure mode.** Reflection
   is what a native image takes away, and a YAML parser binding into
   configuration objects is reflection by definition. Every one of those needs a
   registration, and a missing one does not fail the build — it fails at run
   time, in the field, with a message about a class that is not there. The
   consequence for CI is not optional: **the tests must run against the native
   binary, not the jar.** A green jar build proves nothing about the artifact
   users get.
2. **Cross-compilation is not free.** GraalVM builds for the host. Shipping
   linux-amd64, linux-arm64 and darwin-arm64 means three runners in the release
   workflow, where Go means three lines in one. This is annoying rather than
   hard, and it is a permanent tax on every release.
3. **Size.** Expect 40–80MB against Go's 10–15MB. For a tool downloaded once
   into a pipeline image this does not matter much, and it is worth saying
   plainly rather than pretending it is not true.

### What choosing Go costs

The admin client, twice: once to write, and once forever, in maintenance and
drift. And the accumulated broker knowledge above, which is not in a
specification anywhere — it is in that library's tests.

There is one scenario where Go wins outright, and it is worth naming because it
is the scenario that could change this decision later: **if the primary
deliverable were a Kubernetes operator, Go would be correct**, because
controller-runtime is Go and there is no equivalent worth using elsewhere. That
is not an argument for writing the core in Go. It is an argument for keeping the
operator a separate, later artifact — which is the conclusion the next section
reaches anyway, for independent reasons.

### The baseline

Java 17, not 11. The client libraries target bytecode 11 because an application
on Spring Boot 2.7 has to be able to use them; that constraint applies to a
*library other people depend on*, and this is an application. It takes 17 and
uses it — records for the configuration model, sealed interfaces for the step
types, pattern matching for the planner. There is no caller to hold back.

## Shape: library, CLI, or operator

This is the question that has kept the item in Backlog, and the reason it stayed
there is that it was asked as an exclusive choice. It is not one. They are
layers, they serve different people, and the only real decision is which ships
first.

### The library is the only thing the others are made of

A core library that models a deployment, plans it, and executes it, with no
opinion about where its configuration came from.

It is for the estate's own automation — the pipeline that already knows which
cluster is live and what the current release is, the runbook tool, the
integration test that wants to assert a cutover happened and then assert what
moved. Those callers have context the config file does not have and never will.

It is also the only layer that can be tested honestly. A planner is a pure
function from configuration and two probed clusters to a list of steps; that is
a unit test. An executor against a real pair of brokers is an integration test.
Neither of those is reachable through a subprocess boundary without a great deal
of pain.

### The CLI is the product

`acemq-infra validate`, `plan`, `apply`, `status`, `rollback`.

This is the jreleaser lesson, and it is worth stating the lesson rather than
just citing the tool. What jreleaser gets right is not its feature list. It is
that **the configuration file is the interface**, the CLI is a thin reader of
it, there is one documented way to do a complicated thing rather than six
partial ones, and a dry run is not a flag bolted on at the end but half of the
normal workflow.

A broker cutover wants exactly that treatment, because it has the same shape as
a release: rare, high-stakes, reviewed by people who were not in the room when
it was written, and much better as a file in a repository than as an argument
list in somebody's shell history. `plan` writing a readable artifact that goes
into a pull request is not a nicety — it is the feature that makes the rest of
the tool trustworthy.

### The operator is for a different estate, and it is later

An operator is the same core library in a reconcile loop, with the plan surfaced
as a custom resource's status. It is genuinely the more native answer for an
estate that already expresses brokers as CRs through the RabbitMQ Cluster
Operator.

It is later for three reasons, and only the first is about effort:

1. **A cutover models badly as desired state.** Reconciliation asks "what should
   be true?" and drives towards it, repeatedly, idempotently. A cutover is a
   process with a middle in which things are deliberately half-moved, and
   "repeatedly and idempotently" is a dangerous property to have while a shovel
   is consuming from blue. Expressing it as a CR means expressing a state
   machine in status fields and being very careful that a controller restart
   does not restart the drain. That is solvable and it is real work, and none of
   it is on the path to a tool that does the job.
2. **It takes a dependency on somebody else's resource model.** The Cluster
   Operator's CRDs become part of this tool's contract.
3. **The estates that most need this do not have it.** The migrations that hurt
   are the ones with brokers on VMs, brokers in two clouds, and one cluster
   nobody will move. There is no CR to apply. A binary and a YAML file work
   everywhere; an operator works in one place.

So: an operator if and when somebody asks for it with a real estate behind the
ask, built on the same core, sharing the same configuration schema. Not first,
and possibly not ever.

### What ships first

**The library and the CLI together, in this repository, in one release.** A CLI
with no library underneath it cannot be embedded. A library with no CLI over it
cannot be demonstrated, and a tool nobody can demonstrate does not get adopted.
They are one deliverable.

## Distribution

Through the acemq.org GitHub-hosted feeds, like everything else in this project:
the library as a Maven artifact, the CLI as native binaries attached to the
release, and a GitHub Action wrapping the binary so a pipeline can use it in
three lines. No external registry, which is the standing policy across all six
repositories and not a gap.
