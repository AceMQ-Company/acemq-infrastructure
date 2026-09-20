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
