# Configuration

The configuration file is the interface. Everything else — the CLI, the library,
an eventual operator — is a way of reading it.

That is the jreleaser lesson applied, and it has a consequence worth stating
early: **the file has to be good enough to review**. A cutover configuration
lands in a pull request days before it runs, and is read by people who were not
in the room when it was written. If it cannot be understood from the file alone,
the tool has failed at its main job regardless of how well the executor works.

The format below evolves the one the [C# implementation](prior-art.md) already
had. That format was good, and most of the changes here are about removing
vagueness rather than adding capability.

## A complete blue/green deployment

```yaml
apiVersion: acemq.org/v1alpha1
kind: Deployment
metadata:
  name: orders-blue-green

clusters:
  blue:
    management: ${BLUE_MGMT_URL}        # https://blue.internal:15671
    amqp:       ${BLUE_AMQP_URL}        # amqps://blue.internal:5671
    vhost:      /orders
    username:   ${BLUE_USERNAME}
    password:   ${BLUE_PASSWORD}
    tls:
      verify: true
      caFile: ${CA_FILE}
  green:
    management: ${GREEN_MGMT_URL}
    amqp:       ${GREEN_AMQP_URL}
    vhost:      /orders
    username:   ${GREEN_USERNAME}
    password:   ${GREEN_PASSWORD}

provider: rabbitmq

endpoint:
  kind: external          # external | hook
  description: >
    orders-amqp.internal is a CNAME switched by the platform team.
    The plan will stop here and wait.

deployment:
  operation: blueGreen
  from: blue
  to: green
  semantics: atLeastOnce

  backup:
    enabled: true
    path: ./backups/{{name}}-{{timestamp}}.json
    redactCredentials: true

  announce:
    exchange: ${ANNOUNCE_EXCHANGE}
    routingKey: deployment.started
    payload: |
      {
        "deployment": "orders-blue-green",
        "status": "started",
        "target": "green"
      }

  steps:
    - id: probe
      requires:
        - TOPOLOGY_EXPORT
        - TOPOLOGY_IMPORT_MERGE
        - DRAIN_BY_SHOVEL
        - CONNECTION_CLOSE

    - id: topology
      copyTopology:
        from: blue
        to: green
        vhosts: ["/orders"]
        include: [exchanges, queues, bindings, users, permissions, parameters]
        exclude: [policies, operatorPolicies]   # applied after the drain

    - id: announce-drain
      announce: {}                              # uses deployment.announce

    - id: pause-producers
      waitFor:
        on: blue
        publishRate: 0
        timeout: 2m
        onTimeout: prompt

    - id: drain-consumers
      closeConnections:
        on: blue
        select:
          users: [orders-service]
          role: consumer
        after:
          unacked: 0
          timeout: 5m
          onTimeout: abort

    - id: drain-messages
      drain:                                    # a shovel: messages MOVE
        from: blue
        to: green
        queues: ["orders.*", "!orders.audit"]
        ackMode: onConfirm
        deleteAfter: queueLength
      waitFor:
        on: blue
        depth: 0
        timeout: 15m
        onTimeout: abort

    - id: policies
      copyTopology:
        from: blue
        to: green
        include: [policies, operatorPolicies]

    - id: switch-endpoint
      endpoint:
        target: green

    - id: verify
      waitFor:
        on: green
        consumers: { min: 1 }
        timeout: 5m
        onTimeout: abort

rollback:
  keep: blue
  for: 72h
  steps:
    - id: switch-endpoint
      endpoint: { target: blue }
    - id: drain-back
      drain: { from: green, to: blue, queues: ["orders.*"] }
```

## What changed from the old format, and why

### `type` + `strategy` + `mode` became `operation`

The old file carried three fields for one idea:

```yaml
type: BlueGreen
strategy: RollingUpdate
mode: ActivePassive
```

`RollingUpdate` is Kubernetes vocabulary and does not describe anything a broker
does — there is no rolling anything in a cutover between two clusters.
`ActivePassive` was the only supported value and, as
[blue/green](blue-green.md) argues, the only value that makes sense. Three
fields, one meaning, two of them unable to vary.

```yaml
operation: blueGreen      # blueGreen | canary | mirror
```

One field, three values, and they are genuinely different operations rather than
variations on a theme.

### `infra.handler` and `infra.options` became `provider`

The old format named its implementation with an assembly-qualified type name:

```yaml
infra:
  options: "RabbitMQ.Rabbit.RabbitConfigOptions, RabbitMQ, Version=1.0.0.0"
  handler: "RabbitMQ.Rabbit.RabbitDeployment, RabbitMQ, Version=1.0.0.0"
```

That is a class loader taking instructions from a configuration file, which is
both a security problem and a usability one — the version string in particular
means the file breaks when the tool is upgraded, for no reason the reader can
see.

```yaml
provider: rabbitmq
providerConfig: {}        # provider-specific settings, if any
```

A short name, resolved through the provider registry described in
[broker-agnostic, honestly](broker-agnostic.md). There is one valid value today
and the tool says so when you write a different one.

### The boolean flags became an explicit `steps:` list

This is the biggest change and the one that makes the file reviewable.

```yaml
config:
  useDefinitions: true
  removeConnections: true
  createShovels: true
```

Three booleans that imply an order nobody wrote down. Does the definitions
import happen before or after connections are removed? Before or after the
shovels? The old implementation knew; the file did not say, and the answer was
in code. Worse, the *right* answer is not a single order — as
[message state](message-state.md) shows, the topology import has to be split in
two with the drain between the halves, and no arrangement of booleans expresses
that.

An explicit step list makes the order visible, lets you drop a step you do not
need, lets you insert one nobody anticipated, and gives every step an `id` that
the plan output, the status output and the error messages can all refer to.

Writing out ten steps by hand every time would be a burden, so `plan` fills in
the default list for the chosen operation when `steps:` is absent, and prints it
in full. You can then paste it into the file and edit it. The default is a
starting point rather than a hidden behaviour.

### `shovelFixedDestination` became `drain:`, and `endpoint:` was invented

The old file had:

```yaml
shovelFixedDestination: amqp://admin:admin@green-haproxy:5672
shovelFixedSource: ''
```

Two problems. A credential in plain text in a configuration file — solved by
`${VAR}` interpolation, which the old format already supported and this one
inherits. And, more interestingly, `green-haproxy`: the assumption that a proxy
in front of green is the tool's business, encoded in a field that had no name
for what it was doing.

The drain's endpoints now come from the named `clusters:` block, so there is one
place credentials live. And the thing `green-haproxy` was really about — how
clients find the live cluster — became an explicit top-level `endpoint:` block
with two honest modes:

```yaml
endpoint:
  kind: hook
  run: ./switch-endpoint.sh
  args: ["{{target}}"]
  timeout: 2m
```

or `kind: external`, where the tool stops, prints the description, and waits for
a human. There is no mode where it guesses.

### `backup: true` became a block

The definitions file is a credential — see
[message state](message-state.md) — so a boolean was never enough:

```yaml
backup:
  enabled: true
  path: ./backups/{{name}}-{{timestamp}}.json
  redactCredentials: true      # default
```

Redaction is the default, which means the backup is not by itself sufficient to
restore a cluster. That is a real trade-off and the tool says so when it writes
the file rather than leaving it to be discovered during a restore.

### `semantics` is new and mandatory

```yaml
semantics: atLeastOnce        # or atMostOnce
```

There is no default. `plan` fails if it is missing. This is the most
consequential decision in the file and the tool will not make it silently.

### `guards` became `waitFor` on the steps that wait

Rather than a global timeout, every waiting step carries its own condition,
timeout and timeout policy:

```yaml
waitFor:
  on: blue
  publishRate: 0
  unacked: 0
  depth: 0
  consumers: { min: 1, max: 0 }
  timeout: 5m
  onTimeout: abort            # abort | continue | prompt
```

`abort` is the default for any step downstream of something destructive. The
point is that the decision is in the file, agreed beforehand, rather than made
by a tired person watching a timer.

### What was kept unchanged

- **`clusters:` as a named map**, referenced by name from the deployment. One
  place for credentials, and the names `blue` and `green` are not magic — they
  are keys, and `old`/`new` or `dc1`/`dc2` work identically.
- **`${VAR}` interpolation.** Simple, obvious, and it keeps secrets out of the
  file. The one addition is that an unset variable is now an error rather than
  being left as the literal string `${BLUE_PASSWORD}` — which was the old
  behaviour and would have produced a confusing authentication failure much
  later.
- **The announcement envelope** — exchange, routing key, payload. The best idea
  in the old file, kept as-is.
- **A named deployment.** `metadata.name` appears in the plan, the backup
  filename, the announcement and every log line.

## The other two operations

A [canary](canary.md), which is a blue/green at the scope of one workload:

```yaml
deployment:
  operation: canary
  from: blue
  to: green
  semantics: atLeastOnce
  scope:
    vhost: /orders
    queues: ["orders.notifications", "orders.notifications.dlq"]
    services: [notification-service]
```

No percentage field, on purpose. The canary page explains at length why.

A [mirror](canary.md), which is an observation and not a cutover:

```yaml
deployment:
  operation: mirror
  from: blue
  to: green
  mirror:                       # exchange federation: messages are COPIED
    exchanges: ["orders"]
    upstream:
      uri: ${BLUE_AMQP_URL}
      prefetch: 1000
      ackMode: onConfirm
```

`exchanges:` and not `queues:`. A federated *queue* pulls from its upstream only
when the upstream has no local consumers — a conditional move, not a copy — so
a mirror built from queue federation sits empty while the source is healthy and
starts draining it the moment it is not. The format offers no way to ask for
that, and the validator rejects a `mirror` block naming queues.
[Message state](message-state.md) has the table.

A mirror has no `semantics`, because nothing moves, and no `rollback`, because
nothing happened. It does have a warning printed on every plan: green's
consumers must be in shadow mode, and the tool cannot check that.

## Validating a file

`scripts/lint-deployment.py` checks a configuration against this schema without
touching a broker: required fields, the step vocabulary, capability names,
`${VAR}` references against the current environment, and the handful of
structural rules that matter — a `drain` step with no preceding wait on
`unacked`, a `copyTopology` that includes policies before a drain, a `canary`
with a `percentage` key, a missing `semantics`.

It runs in CI against everything in `examples/`, which is how the format stays
honest while it is still only a document.

```console
$ ./scripts/lint-deployment.py examples/blue-green.yaml
examples/blue-green.yaml: ok — blueGreen, 9 steps, 2 clusters
```

Once the tool exists this becomes `acemq-infra validate`, and the linter's rules
move into it. It is written in Python now because the tool does not exist yet
and the format needs to be checkable today.
