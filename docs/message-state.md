# Message state

This is the page that explains why the tool is hard, and it is the one to read
if you only read one. Everything else here is plumbing around the fact that a
broker holds durable state and there is no moment at which two clusters agree
about it.

## There is no atomic cutover

Start here, because it is the constraint everything else descends from.

There is no instant at which a message stops being blue's responsibility and
starts being green's. There is a **window** — between the last publish to blue
and the first consume on green — during which a given message may be on blue, in
flight in a shovel, on green, or momentarily visible in two of those places. It
is the same shape as exactly-once delivery: not a problem you solve with more
care, a property the system does not have.

What a good tool does with an unsolvable problem is make it **short, bounded and
observable**, and then make you choose which way the ambiguity falls:

```yaml
deployment:
  semantics: atLeastOnce      # or atMostOnce
```

- **`atLeastOnce`** — a message may be processed on blue *and* on green.
  Consumers must be idempotent. This is the default.
- **`atMostOnce`** — a message may be stranded on blue and never processed at
  all. You must go back for it.

The default is `atLeastOnce` because a duplicate is a problem you can design
against, up front, with an idempotency key. A stranded message is a problem you
find in a month, in a reconciliation report, with no way left to tell which
messages were affected.

Setting this is not optional. If it is absent from the configuration, `plan`
fails and says why — a silent default here would be the tool making the most
consequential decision in the file on the user's behalf.

## In-flight and unacked deliveries

A message that has been delivered to a consumer but not yet acknowledged is
owned by that consumer, on blue. Close the connection and the broker requeues it
**on blue** — not on green, because green has never heard of it.

So closing everything at once is the worst available move. It maximises the
requeue storm, maximises what the shovel then has to move, and maximises the
duplicate count, all at the moment when the estate is least able to absorb any
of them.

This is why the [step order](blue-green.md) is forced: stop the producers, wait
for unacked to reach zero, *then* close the consumers. And it is where the
AceMQ client libraries do the real work — a service that drains properly settles
what it is holding through the normal path, with its own retry and dead-letter
behaviour intact. The orchestrator's contribution is to *ask* and then to
*wait*, which is what the announcement envelope and the `unacked: 0` guard are.

The unpleasant case is a consumer that never reaches zero: a handler wedged on a
downstream call, a prefetch of 5000 with a slow processor, a service that
ignored the announcement. The guard's `onTimeout` decides, and there is no good
answer, only a chosen one. `abort` leaves the estate on blue, which is the
correct default.

## Durable queues with data in them

A definitions export carries the *shape* of a queue — its name, its durability,
its arguments, its bindings. It never carries a single message. Green comes up
with a perfectly-configured, completely empty `orders.new`.

The mechanism for moving the contents is a shovel, and here is the thing to
internalise:

> **A shovel moves. A federated *exchange* copies. A federated *queue* does
> neither of those reliably — and that third case is the trap.**

A shovel takes a message off blue and puts it on green, unconditionally. After
it runs, the message is not on blue any more. That is exactly right for a drain,
and catastrophically wrong for anything else — a shovel declared "just to see
what happens" has emptied your rollback.

Federation is where the folklore is wrong, and it is worth being precise because
the two kinds behave nothing alike:

| Mechanism | What happens to the source | Use it for |
|---|---|---|
| Shovel | Message is consumed. Unconditional. | Draining |
| Federated **exchange** | Source routes as normal; a copy is replayed downstream | Mirroring |
| Federated **queue** | Message is consumed — but **only when the upstream queue has no local consumers** | Neither |

A federated queue is a *work-queueing* mechanism. RabbitMQ's own documentation
is explicit: it "allows local consumers to receive messages from remote queues
when no local consumers are active" on the upstream. So it is a conditional
move, not a copy, and the condition is the health of the very consumers you are
migrating.

Using a federated queue to build a shadow environment therefore fails in the
most misleading way available: while blue's consumers are healthy, nothing is
copied and green sits empty, so the mirror looks broken. The moment blue's
consumers stop — which is what a cutover does on purpose — messages start
*leaving* blue for green. You have built an accidental drain that fires exactly
when you were trying to keep a rollback.

A federated **exchange** is the copy. It replays what is published upstream into
its own bound queues, so blue routes the message to blue's queues as usual and
green receives its own copy. That is what
[`mirror`](canary.md) means and it is what
`scripts/blue-green-lab.sh mirror` sets up.

The configuration keeps these as two different words, `drain:` and `mirror:`,
rather than one block with `mechanism: shovel | federation` — both because the
distinction is too consequential to be a field value, and because "federation"
on its own does not name a behaviour.

### What a shovel does to a message

A shovel republishes. Republished is not the same as moved, and four things
change:

- **`x-delivery-count` resets.** A quorum-queue message that had used four of
  its five redelivery attempts on blue arrives on green with zero. It gets five
  more. A poison message that was one delivery from being dead-lettered is back
  to full health, and will spend another five attempts finding that out.
- **Dead-letter history is gone.** The `x-death` header array records where a
  message has been rejected and how often. Republishing produces a message that
  has, as far as green is concerned, never been anywhere.
- **Ordering is not preserved across queues.** Within one shovel on one queue,
  order holds. Across several queues shovelled concurrently, and against
  messages published directly to green during the same window, there is no
  global order and there was never going to be one.
- **The publisher is now the shovel.** Anything downstream that inspects
  `user_id` on the message properties, or reasons about the connection it
  arrived on, sees the shovel's credentials rather than the original producer's.

None of these are fixable. They are properties of republishing. The tool's job
is to put them in the plan output so nobody learns them afterwards.

## Streams, which the tool cannot fully solve

A stream is a log with a retention policy, and consumers hold an **offset** into
it. That offset is the consumer's position, and it is the entire mechanism by
which a stream consumer knows what it has and has not seen.

There is no way to transplant it.

RabbitMQ can store a stream consumer's offset server-side, and a client can read
it. There is no API — none, at any version — for writing a consumer's offset
into a *different* cluster's stream, and even if there were, it would be
meaningless: offsets are positions in a specific log, and green's log is a
different log with different numbers.

So when a stream moves, every consumer restarts at whatever its
`x-stream-offset` says:

- `first` — replays the entire retained log. For a stream with a week of
  retention, that is a week of reprocessing.
- `next` — skips everything published before the move. Whatever was in the
  window is never seen.
- `last`, a timestamp, or an absolute offset — variations on the same two
  failure modes.

And shovelling a stream is worse than not solving it. A shovel consumes the
stream and republishes into green's stream, which converts a log with retention
semantics into a sequence of new messages: the original offsets are gone, the
original timestamps are gone, and anything consuming by timestamp is looking at
the time of the migration.

The honest position: **the tool refuses to plan a stream step silently.** If the
scope contains a stream, `plan` prints what will happen to each consumer given
its configured offset, and requires explicit confirmation in the file:

```yaml
streams:
  acknowledged: true
  restartAt: next        # one answer, for every stream in the scope
  note: "audit.events consumers accept the gap; replay from the warehouse"
```

That is not a solution. It is a refusal to pretend, which is the only thing on
offer here.

The projection is per consumer, not per stream, because one stream's consumers
routinely disagree with each other — and a single sentence about offsets not
travelling is true of all of them and answers the question about none of them:

```
· orders.events · audit-writer (10.0.0.12:51002): `first` — replays the
  target's entire retained log from the beginning...
· orders.events · ledger-tailer (10.0.0.13:51003): `next` — starts after
  whatever the target's log holds when it attaches...
· orders.events · search-indexer (10.0.0.14:51004): no x-stream-offset, which
  RabbitMQ reads as `next`...
```

`restartAt` sets nothing, and nothing here can: the position a consumer resumes
from is the `x-stream-offset` its own client asked for. It is a statement of
what you believe the consumers are configured to do, and its whole job is to be
held up against them — a file that accepts a gap, over an estate whose consumers
replay the entire log, has acknowledged the opposite of what will happen, and
that is refused rather than warned about. A confirmation about the wrong
consequence is not a confirmation.

Which is why it can also be written one stream at a time. An estate where
`orders.events` consumers skip the window and `orders.ledger` consumers replay
the whole log owes two answers at once, and no single value is a true statement
about it — so every honest file for that estate was refused, and the only way
past the refusal was to stop running the tool:

```yaml
streams:
  acknowledged: true
  restartAt:
    orders.events: next
    orders.ledger: first
  note: "audit.events consumers accept the gap; replay from the warehouse"
```

Written that way it has to name **every** stream the scope contains. A stream
with no line is a refusal, not a default: falling back to anything at all —
`next`, or whatever the other streams said — would let `acknowledged: true` sign
for a consequence the file never described, which is the one failure the
confirmation exists to prevent, and it would do it while looking like a finished
file rather than a half-written one. A line naming a stream the drain is not
moving is checked against nothing and is said rather than refused. The scalar
still means what it always meant: one answer, covering the whole scope.

The stream is as fine as the key gets. RabbitMQ does have a notion of a consumer
group — single active consumer groups the consumers that share a stream *and a
name* — but that name belongs to the stream protocol, and what the plan reads is
`/api/consumers`, which carries the queue, the channel and the consumer's
arguments and nothing of the kind. A consumer here is queue plus connection plus
user, and the user is an authentication identity rather than a role: two
processes connecting as `audit-writer` can ask for different offsets. A key that
can hold two answers is not a key, so a single stream whose own consumers
disagree with each other still cannot be written down — that file is refused,
with the disagreement named, and the fix is to make the consumers agree.

And if the consumers cannot be listed at all, the plan refuses for the same
reason the [canary's scope check](canary.md) does: `streams.acknowledged`
confirms a consequence, and a run that cannot say what the consequence is has
nothing to be confirmed.

## Policies that start working before you want them to

An imported definitions document applies its policies immediately. A
`message-ttl`, a `max-length`, a `max-length-bytes` or an overflow policy is
live on green the moment it lands — before any message has arrived, and
therefore well before the drain starts filling the queue.

A backlog drained into a queue enforcing a steady-state limit is a backlog
discarded on arrival, quietly, with the messages counted as dropped in a metric
nobody is watching during a migration.

Hence the topology import is split in two, and the retention-affecting policies
go on **after** the drain completes. This is a step-ordering fix for a data-loss
bug, and it is the single most valuable thing in the default step list.

Operator policies deserve their own mention: they cannot be overridden by an
application, which makes them the right tool for fencing a target cluster during
a migration — an operator policy capping queue length on green during the drain
is a safety net, whereas the same policy as a normal policy can be silently
replaced by an application declaring its own.

## The definitions file is a credential

RabbitMQ's definitions export includes users, and users carry password hashes.
A hash is enough to stand up a broker that the real passwords authenticate
against.

That has three consequences for a tool that reads, writes and backs up these
files:

- It is never logged, never printed, and never included in a plan artifact —
  because plan artifacts go into pull requests.
- `backup.redactCredentials: true` is the default, which strips `users` and
  `permissions` password data from the file written to disk. The backup is then
  not sufficient to restore a cluster on its own, and the docs say so rather
  than leaving somebody to discover it during a restore.
- When it is written unredacted, on purpose, the file is created with
  `0600` and the path is reported.

## Connection close is a blunt instrument

`DELETE /api/connections/:name` closes a TCP connection. That is the entire set
of tools the management API offers for moving a client, and it has no
"reconnect to this other place" parameter — because the broker has no idea where
the client would go.

Where the client reconnects is determined by whatever it resolves its broker
address to, and this tool does not own that. So the contract is stated rather
than implied:

- **You own the endpoint.** DNS, the load balancer, the service mesh, the
  connection string in the config map.
- **The tool owns both sides of it**, and tells you exactly when to flip it — or
  runs a hook that flips it, if you give it one.
- **A closed connection before the endpoint has moved reconnects to blue**, and
  the tool checks this: after `switch-endpoint`, the verify step asserts that
  green has the expected consumers and blue does not. If clients came back to
  blue, the endpoint did not move, and that is a failed cutover caught in
  minutes instead of at the next incident.

## A short list to keep by the plan

- A message may be delivered twice, or stranded. Choose which, in writing.
- Unacked deliveries requeue on the source. Drain consumers before closing them.
- A shovel moves. A federated exchange copies. A federated queue moves only
  when the upstream has no consumers, which makes it useless for both jobs.
- Republishing resets delivery counts and erases dead-letter history.
- Stream offsets do not travel. Nothing can make them.
- Retention policies applied early will eat the backlog you are about to move.
- The definitions file is a credential.
- The endpoint is yours, and the cutover fails silently if it does not move.
