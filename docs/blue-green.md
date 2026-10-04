# Blue/green

Two complete clusters. Everything moves from one to the other, at a point in
time, and the one you left stays cold until you are sure.

That is a different operation from [canary](canary.md), not the same operation
with a flag. The two have different configuration, different plans, different
guards and different rollbacks, and the tool keeps them apart on purpose — the
reasoning for that is on the canary page, and it comes down to what a percentage
does to a queue.

## What it means for something that holds state

For a stateless web service, blue/green is nearly trivial: stand up green, point
the load balancer at it, keep blue for an hour. Requests are short, they hold
nothing, and the two versions never need to agree about anything.

A broker is the opposite of that in every respect that matters. It *is* the
state. Green comes up with blue's topology and an empty `orders.new`, and every
message sitting in blue's `orders.new` has to get across before blue can go
away. While that is happening there are two clusters, both partly live, and
consumers that can only see one of them.

So the operation is not "point at green". It is a sequence, and the sequence is
the product.

## The sequence

```
  1  probe            both clusters: version, plugins, permissions, capabilities
  2  backup           blue's definitions, to disk, credentials redacted
  3  topology         blue's shape onto green — NOT its retention policies
  4  announce         publish the envelope: a deployment has started
  5  pause-producers  wait until nothing is publishing to blue
  6  drain-consumers  wait until unacked is zero, then close what is left
  7  drain-messages   shovel blue -> green, wait until blue's queues are empty
  8  policies         NOW apply the retention and delivery policies to green
  9  switch-endpoint  the hook, or the human
 10  verify           green has consumers, blue has nothing left
```

Ten steps, and four of them are decisions rather than mechanics.

### Why the policies come last

This is the step order that catches people, and it costs messages when it is
wrong.

The definitions export carries policies, and an import applies them immediately.
If green receives a `message-ttl` policy or a `max-length` policy in step 3, it
is live before a single message has arrived — and then step 7 shovels a backlog
into a queue that is actively enforcing a limit designed for steady state. A
forty-minute backlog drained into a queue with a ten-minute TTL is a
forty-minute backlog discarded on arrival.

Splitting the topology import into "the shape" and "the rules" is not an
optimisation. It is the difference between a cutover and a data loss incident.

### Why producers stop before consumers

Because a consumer closed while producers are still publishing leaves work
behind it, and that work then has to be drained by a shovel — which changes the
message. See [message state](message-state.md) on what a shovel does to
`x-delivery-count` and to dead-letter history.

Stop the producers, and the consumers drink the queue down to zero on their own,
using the normal path, with their normal retry and dead-letter behaviour intact.
Then they have nothing left to do and can be closed cheaply. What the shovel
then moves is whatever the consumers could not finish inside the guard's
timeout, which in a healthy estate is very little and sometimes nothing at all.

The inversion — closing consumers first — is the instinct, because consumers are
the thing you are moving. It is the expensive order.

### Why `announce` is step 4 and not step 1

Because the announcement is advisory and its only job is to give the
applications a head start on the guard in step 5. Publishing it before the
topology exists means a well-behaved client drains, reconnects to an endpoint
that still points at blue, and does it all again ten minutes later when the real
cutover happens. Announce once, immediately before you start waiting.

### The endpoint is not ours

Step 9 is the one step this tool does not own, and the configuration makes that
explicit rather than hiding it.

The management API can close a connection. It cannot decide where the client
reconnects to — that is DNS, or a load balancer, or a connection string in a
config map, or a service mesh, and every estate does it differently. The old C#
implementation papered over this with a `shovelFixedDestination` pointing at
`green-haproxy`, which worked in the environment it was written for and encoded
an assumption nobody else shares.

So `endpoint:` is a first-class block with two honest modes: `external`, where
the tool stops and tells a human exactly what to switch and waits, and `hook`,
where it runs a command you supply. There is no third mode where it guesses.

## Guards, and what happens when one does not pass

Every waiting step has a guard: a condition, a timeout, and a decision about
what the timeout means.

```yaml
waitFor:
  publishRate: 0
  timeout: 2m
  onTimeout: prompt        # abort | continue | prompt
```

`abort` is the default for anything downstream of a destructive step. `continue`
exists because sometimes a straggler publisher is a health-check that will never
stop and you know it. `prompt` is for the attended cutover, which is most of the
first ten cutovers anyone runs.

The important property is that the choice is in the file, reviewed beforehand,
rather than made by a tired person at the moment the timer runs out.

## Rollback

Blue is kept. That is the whole reason to do it this way, and it is the part
most often thrown away by accident.

The failure mode is specific: the drain in step 7 is a shovel, and **a shovel
consumes**. By the time step 7 finishes, blue's queues are empty. Blue is still
there, still configured, still able to accept connections — but the messages are
on green now, and rolling back the endpoint alone gives you an empty cluster.

So rollback is a real operation with its own plan, not an undo:

```yaml
rollback:
  keep: blue
  for: 72h
  steps:
    - id: switch-endpoint
      endpoint: { target: blue }
    - id: drain-back
      drain: { from: green, to: blue, queues: ["orders.*"] }
```

And it inherits every hard problem the forward direction has, with one addition:
the messages coming back have now been republished twice, so whatever a shovel
did to their headers and delivery counts has been done to them twice. The docs
should not pretend a rollback is free. It is a second cutover in the other
direction, and it is worth running one in the lab before you need one in
production — which is what `scripts/blue-green-lab.sh` is for.

The `for: 72h` is not enforcement, it is documentation with a name: it is the
window during which somebody has agreed not to delete blue. The tool will remind
you it has passed. It will not delete anything.

### Running it: the journal, and `acemq-infra rollback`

A rollback is derived from what a run *did*, not from what was planned: the
steps that reached `done`, in reverse, with the drain run the other way and the
endpoint switched back first. A topology copy, a connection close, a mirror and
an announcement are not inverted, for the reasons `Rollbacks` gives. When the
file writes `rollback.steps` out, that list is used unchanged.

What a run did lives in the process that ran it, so `apply` writes it down. Every
cutover leaves a **journal**: `journals/<name>-<timestamp>.json` beside the
deployment file, or wherever `--journal PATH` says. It is created before the
first write — a run whose journal cannot be written does not start — and an
existing file is never overwritten, because it is the only record of how to undo
some earlier run. After that it is rewritten after every step through a
temporary file and an atomic rename, so a process killed mid-run leaves the
journal as it was after the last step and never a torn one. `apply` prints where
it is, and when there is something to undo, the command that undoes it:

```console
$ acemq-infra apply -f orders.yaml
...
journal: /work/journals/orders-blue-green-20261004T141500Z.json
...
the cutover completed. the rollback for what happened is 2 steps.
to undo it: acemq-infra rollback --journal /work/journals/orders-blue-green-20261004T141500Z.json
```

`acemq-infra rollback --journal PATH [-f FILE] [--dry-run] [--yes]` reads it
back. It re-probes both clusters, then rehearses the undo against them through
the same decorator `apply --dry-run` uses, whose writing verbs throw — that
rehearsal is the undo plan, printed with what it will cost:

```console
the cost of this rollback
  140 messages to carry back from green (orders.new, orders.priority)
  25 of them handed to a consumer on green and not settled when the rollback
  was asked for: when it follows the endpoint back they are requeued and handed
  out again — processed on both clusters
```

That second number is the one `BlueGreenCutoverIT` measures on the JVM, read
before the switch back because afterwards a requeued message is a requeued
message and nothing on the broker can tell them apart. Then it asks exactly as
`apply` does — the word `yes` at a terminal, or `--yes` from a pipeline, which
consents to the start and to nothing after it — and runs the rollback as a
second cutover, writing its own steps into the same journal. `--dry-run` stops
after the rehearsal. `-f` defaults to the file the journal names.

It refuses, and writes nothing, when:

- **the journal is from another plan.** The deployment file's bytes are
  fingerprinted, and the clusters' management URLs recorded; a changed file, a
  `from`/`to` that moved, or a variable that now points somewhere else is
  refused. The steps are recorded by their position in the file's step list and
  checked by id as well.
- **the journal is a newer format** than this build reads. It does not guess.
- **it has already been rolled back** — or a rollback of it was started. The
  journal is marked before the rollback's first write, so one that died halfway
  still cannot be started a second time from the same file.
- **the cutover's drain is still running.** A drain-back started while the
  forward shovel is moving messages would chase it in a circle.

A journal still saying `running` is the record of a crash. A step left at
`started` in it is counted as having happened — a drain killed halfway has moved
some of the messages, and the drain-back moves whatever it moved — and the
rollback says which.

The format, version 1:

```json
{
  "format": 1,
  "tool": "acemq-infra …",
  "deployment": "orders-blue-green",
  "file": "/work/orders.yaml",
  "fileSha256": "9f2c…",
  "from": "blue",
  "to": "green",
  "clusters": { "blue": "https://blue.internal:15671", "green": "https://green.internal:15671" },
  "startedAt": "2026-10-04T14:15:00Z",
  "outcome": "completed",
  "endedAt": "2026-10-04T14:31:12Z",
  "steps": [
    { "number": 1, "index": null, "id": "backup", "status": "done", "lines": ["…"] },
    { "number": 6, "index": 5, "id": "drain-messages", "status": "done", "lines": ["…"] }
  ],
  "movements": [
    { "label": "acemq-orders-blue-green-drain-blue-to-green", "on": "green", "parts": ["…"] }
  ],
  "rollback": { "startedAt": "…", "outcome": "completed", "steps": [ ], "movements": [ ] }
}
```

`outcome` is `running` until the run ends, then `completed`, `aborted`,
`stopped` or `refused`. A step's `status` is `started` until it ends, then
`done` or `failed`. `index` is the step's position in the file's step list, or
`null` for the backup, which is not a step in the file and is never undone.
`movements` are the shovels a drain declared. `rollback` is absent until a
rollback starts. Management URLs are recorded with any credentials stripped;
nothing else in the file is a secret. A format change that an older build could
misread bumps `format`.

## Active/passive, and the thing that word was hiding

The old configuration had `mode: ActivePassive`. It is worth unpacking, because
the phrase carries an assumption that should be stated instead of assumed.

Active/passive means green is not taking traffic until the cutover and blue is
not taking traffic after it — there is never a moment when both are serving the
same queue to different consumers. That is the only mode this tool supports for
blue/green, and it is not a limitation to be lifted later. **Active/active on a
single logical queue is not a deployment strategy, it is a split brain.** Two
clusters, both accepting publishes to `orders.new`, with consumers attached to
each, is two queues that share a name and nothing else.

There are real active/active topologies — federated exchanges fanning to
regional clusters with regionally-partitioned consumers, for instance — but
those are an architecture, designed up front, not something a deployment tool
puts an estate into for twenty minutes during an upgrade.
