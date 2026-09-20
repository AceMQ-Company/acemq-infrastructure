# Prior art

This was built once before, in C#, at
[felipeg48/infra-blue-green-deployment](https://github.com/felipeg48/infra-blue-green-deployment).
That repository is the actual brief for this one: the design in it was sound and
most of it is carried forward. This page records what was taken, what was left,
and one thing about it that is worth knowing before you read its README.

## What is actually in that repository

Worth establishing first, because the README describes more than the code
contains. At `HEAD` the tracked files are:

```
src/Api/Api.cs                     builders, config model, reflective dispatch
src/Api/ApiClient.cs               a small HTTP+JSON client
src/Api/ApiYamlConfig.cs           the YAML object model
src/Api/Utils/                     ${VAR} interpolation, dictionary helpers, logging
src/Cloud/Docker/DockerContainerBuilder.cs
src/Cloud/AWS/AwsClient.cs         an empty class
src/blue-green-deployment.yaml     the example configuration
test/Infra.Tests/YamlTests.cs      parses the YAML
docs/                              AWS.adoc, Simulator.adoc, definitions samples
```

The `RabbitDeployment` handler and `RabbitConfigOptions` that both the README
and the example configuration name — `"RabbitMQ.Rabbit.RabbitDeployment,
RabbitMQ, Version=1.0.0.0"` — are not in the repository. They live in an
assembly it loads by name. The tests exercise the YAML parser.

So what is being carried forward is **a design**, and the design was good. That
is a perfectly respectable thing for prior art to be, and it is better to say it
than to let somebody clone the repository expecting a working cutover.

## Taken

### The YAML front end, and `${VAR}` interpolation

The central idea — that this operation belongs in a file rather than in
arguments — was right, and the file's overall shape was right. Environment
interpolation was right too, and for exactly the reason it usually is: it keeps
credentials out of the document while leaving the document readable.

One change, described in [configuration](configuration.md): an unset variable is
now an error rather than being left as the literal `${BLUE_PASSWORD}`, which is
what the old `EnvironmentVariableConverter` did and which would surface much
later as a confusing authentication failure.

### `clusters:` as a named map

```yaml
clusters:
  blue:  { ... }
  green: { ... }
```

Credentials in one place, referenced by name from the deployment. Simple and
correct, kept verbatim. The names are keys rather than magic words, which the
old implementation also got right — `old`/`new` works identically.

### The announcement envelope

```yaml
envelope:
  exchange: ${EXCHANGE}
  routingKey: ${ROUTING_KEY}
  payload: |
    { "message": "Deployment started", ... }
```

The best idea in that repository, and the one that fits the AceMQ libraries
best. Publishing a message to tell clients a deployment has started turns a
forced disconnection into a requested drain, and the five client libraries
already know how to drain. Kept, renamed to `announce:`, otherwise unchanged.

### The pluggable provider

The concept is kept: one core, broker-specific implementations behind a seam.
The *mechanism* is not — see below.

### Backup before touching anything

`backup: true` in the old file. Kept and expanded into a block, because the
definitions document is a credential and a boolean could not express redaction.

### Events and a deployment status

`OnCompleted`, `OnFailed`, and a `DeploymentStatus` the handler updated. The
instinct is right — a cutover's real output is a timeline, not a return code.
Kept as structured per-step events, which the CLI renders as progress and the
library exposes as a stream.

### The distinction between shovel source and destination

The old config had `shovelFixedSource` and `shovelFixedDestination`, which is
nearly the right idea: a drain has a direction and both ends need naming. What
it lacked was the distinction between a shovel and federation — and, underneath
that, between exchange and queue federation, which is the single most
consequential and most easily-missed thing in this domain. See
[message state](message-state.md).

## Left

### The builder API

```csharp
NodeBuilder.WithScheme(Scheme.Http).WithHostName("localhost").WithPort(5672)
           .WithVirtualHost("/").WithConsolePort(15672)
           .WithUserName("admin").WithPassword("admin")
           .WithApiPath("/api").Build();
```

Eight fluent methods to build a record with eight fields. The cost is not the
verbosity — it is that the repository then had **two front ends**, a builder and
a YAML parser, each with its own object model and its own defaults, and every
feature had to be specified twice.

The library API stays, because [the library is the only thing the CLI is made
of](shape.md). It takes the same configuration object the YAML parses into. One
model, two ways in.

### Assembly-qualified type names in configuration

```yaml
infra:
  options: "RabbitMQ.Rabbit.RabbitConfigOptions, RabbitMQ, Version=1.0.0.0"
  handler: "RabbitMQ.Rabbit.RabbitDeployment, RabbitMQ, Version=1.0.0.0"
```

A class loader taking instructions from a configuration file. It is a security
problem, and it is a usability problem too: the version string means the file
breaks when the tool is upgraded, for a reason nobody reading the file can see.

Replaced by `provider: rabbitmq` — a short name resolved through a registry.

### `type`, `strategy` and `mode`

`BlueGreen`, `RollingUpdate`, `ActivePassive`. Three fields for one idea, two of
which could not vary, and `RollingUpdate` was Kubernetes vocabulary that
describes nothing a broker does. Replaced by a single `operation:`.

### The boolean step flags

```yaml
config:
  useDefinitions: true
  removeConnections: true
  createShovels: true
```

The order these implied was never written down, and — worse — no ordering of
three booleans can express the order that is actually correct, which splits the
definitions import in two and puts the drain between the halves. Replaced by an
explicit `steps:` list. This is the largest single change and the one that makes
the file reviewable.

### `shovelFixedDestination: amqp://admin:admin@green-haproxy:5672`

Two things wrong. A credential in plain text, which the interpolation feature
existed to prevent and which this line bypassed. And `green-haproxy`: the
assumption that a proxy in front of green is the tool's business, baked into a
field that had no name for what it was doing.

That second point turned into the `endpoint:` block, which is the tool saying
out loud that it does not own DNS.

### `src/Cloud/AWS` and `src/Cloud/Docker`

`AwsClient.cs` is an empty class. `DockerContainerBuilder.cs` builds a RabbitMQ
container specification with a proxy flag.

Both are provisioning, and provisioning is not this tool's job —
[what this is](what-it-is.md) draws that line. Terraform, the RabbitMQ Cluster
Operator and your own pipeline make clusters; this tool moves between clusters
that exist. Stopping where that repository stopped was the right call, and it is
being made explicit rather than left as an unfinished directory.

The Docker builder survives in spirit as `scripts/blue-green-lab.sh`, which
stands up two clusters for development and demonstration and says clearly that
that is all it is for.

### `docs/Simulator.adoc`

A note pointing at RabbitMQSimulator, with the observation that it worked with
node v10. Replaced by two real brokers in the lab script. Reasoning against a
simulator is how you end up with a tool that works against a simulator.

### The .NET implementation itself

Not for any reason to do with .NET. The
[language decision](shape.md) is driven by
[`acemq-java-rabbitmq-admin`](https://acemq.org/acemq-java-rabbitmq-admin/)
already existing and already knowing how RabbitMQ's management API misbehaves —
which is the largest single asset available to this project and is a Java one.

## The through-line

The old repository got the *interface* right — a file, named clusters,
interpolated secrets, a pluggable provider, an announcement to clients, a backup
first — and had not yet got to the part where the ordering of the steps is the
product.

Everything on the "left" list above is a place where the file described an
intention and the code held the meaning. The replacement in every case is the
same move: put the meaning in the file, where somebody can read it in a pull
request on Monday before it runs on Thursday.
