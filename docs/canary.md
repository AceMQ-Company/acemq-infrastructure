# Canary

A canary for a stateless web service is a traffic split: send 5% of requests to
the new version, watch the error rate, move the 5% back if it is bad. It works
because a request is over in milliseconds and holds nothing. Nothing is lost by
moving the split back, because by the time you move it, every request that went
the new way has already finished.

Apply that to a broker and you do not get a canary. You get a partitioned queue.

## Why the percentage is the bug

Suppose you route 5% of producers to green and leave 95% on blue. Now:

- `orders.new` exists on both clusters and each holds a fraction of the orders.
- A consumer attached to blue cannot see green's 5%, and vice versa. There is no
  mechanism by which it could.
- Any ordering guarantee you had per key is gone. Two messages for the same
  customer can land on different clusters and be processed in either order, by
  different processes, with no relationship between them.
- Your dead-letter queues are split the same way, so a poison message
  investigation now has two places to look.
- And "roll back the 5%" does not undo anything. Those messages are on green. If
  you then delete green, they are gone; if you do not, you have a cluster you
  cannot decommission holding orders nobody is consuming.

The percentage is not a small version of a cutover. It is a different and worse
operation that happens to be spelled the same way. So the tool has no percentage
field — deliberately, because a percentage field is the thing that invites this.

## What a broker canary actually is

Three techniques get called "canary" in this space. They are genuinely
different, two of them are useful, and the configuration should force you to say
which one you mean.

### 1. Canary by workload — the one the tool implements

Move **one service, completely**. One vhost, or one set of queues, and every
producer and every consumer attached to them, together, in a single small
cutover. Watch it for a day. Move the next one.

The unit of a broker canary is **a queue and everything attached to it** — never
a fraction of traffic. Because producers and consumers move as a set, the queue
is never split, ordering is preserved, and there is exactly one place to look
for a message at any moment.

This is a [blue/green cutover](blue-green.md) at a smaller scope, and that is a
feature rather than an admission. It means the machinery is the same machinery,
the guards are the same guards, and the rollback is the same rollback — the only
difference is what the scope selector matches.

```yaml
deployment:
  operation: canary
  from: blue
  to: green
  scope:
    vhost: /orders
    queues: ["orders.notifications", "orders.notifications.dlq"]
    services: [notification-service]
```

The `services:` list is not decoration. It is what the connection selector in
the drain step matches on, and stating it is how the tool checks the thing that
makes this safe: **that every consumer of the named queues is one of the named
services.** If something else is attached to `orders.notifications` and you did
not list it, the plan says so and refuses, because moving the queue without that
consumer is exactly the partition this whole page is about.

A name in `services:` is matched against **the user a connection authenticated
as**. The broker has no concept of a service, and the user is the only identity
it carries from the client through to the management API — it is also what the
close step's `select.users` matches, so anything else here would mean the check
and the step it protects disagreeing about who the service is.

### The check runs both ways

Stated on its own, "every consumer of a scoped queue is a named service" leaves
the mirror image open, and the mirror image costs the same. Suppose
`notification-service` consumes `orders.notifications`, which is in scope, and
also `orders.new`, which is not. The rule above passes. The cutover then closes
that service's connections and its endpoint resolves to green — so `orders.new`
is left on blue with nothing consuming it, while the service sits on green
watching a copy of `orders.new` that the topology import created and that
nothing will ever publish to. The queue is not split; it is abandoned, which is
worse, and it is silent in the same way.

So what the tool actually enforces is that the scope is **closed**: the scoped
queues and the named services form a group with no edge leaving it. Both
directions refuse. That is what "a queue and everything attached to it" means
when it is taken seriously.

Two things about that are worth knowing before you write a file:

- The check sees one virtual host, because the probe does. A service consuming
  a queue in a *different* vhost is outside what any of this can see, and the
  plan does not pretend otherwise.
- **If the consumer listing cannot be read, the plan refuses.** A management
  account without permission to read `/api/consumers` returns an empty list, and
  an empty list is indistinguishable from a queue with nothing attached — one of
  which says the canary is safe and the other of which says nobody knows. A
  check that passed quietly when it could not see would be worse than no check,
  because somebody would have read its output.

Picking the first workload is a judgement the tool cannot make. The useful
heuristic: it should be low-volume, idempotent, owned by a team that will notice
within the hour, and not on the path to money.

### 2. Canary by mirror — supported, but it is not a cutover

Federate the **exchange** so that everything published to blue is **copied** to
green. Run green's consumers in a mode that does the work and discards the
result, or writes to a shadow store. Compare.

The word "exchange" there is load-bearing, and getting it wrong is the
commonest mistake in this whole area. A federated *queue* is not a copy: it
pulls from the upstream only when the upstream has no local consumers, which
makes it a work-queueing mechanism that *moves* messages — and it does so
precisely when blue's consumers stop, which is what a cutover does deliberately.
Build a mirror out of queue federation and you get an empty green while
everything is healthy and an accidental drain the moment it is not.
[Message state](message-state.md) has the table.

A federated **exchange** replays upstream publishes into its own bound queues,
so blue routes as normal and green gets its own copy. That is a real and
valuable technique: it exercises green under genuine production traffic with no
risk to the real path, because the authoritative processing never leaves blue.

It has two honest limits, and the docs must carry both:

- **It doubles the traffic and the storage.** Every message exists twice. On a
  cluster sized for its normal load, this is the thing that falls over.
- **It only tests what is safe to do twice.** Anything that writes to a shared
  database, calls a payment provider, or sends an email cannot run in shadow
  mode without either a discard path built into the application or a real
  duplicate in the world. In practice this means mirroring tests the transport,
  the deserialisation, the routing and the throughput — which is most of what
  goes wrong in a broker migration — and does not test the business logic.

It gets its own verb, `mirror`, rather than being a mode of `canary`, because it
does not end in a cutover and has no rollback. It is an observation.

```yaml
deployment:
  operation: mirror
  from: blue
  to: green
  mirror:
    exchanges: ["orders"]        # exchanges, not queues — see above
    upstream:
      uri: ${BLUE_AMQP_URL}
      prefetch: 1000
      ackMode: onConfirm
  # Green's consumers must be in shadow mode. The tool cannot check this.
```

`exchanges:` rather than `queues:` is deliberate and the validator enforces it.
A `mirror` block naming queues would be asking for queue federation, which is
the conditional move described above, and the format does not offer a way to
write that by accident.

That last comment is the one thing the tool genuinely cannot verify, and it will
print it as a warning on every `mirror` plan rather than letting it be silent —
on every plan that declares a mirror at all, in fact, including a `blueGreen`
file with a mirror step in it, because that file has built the same federated
exchange and carries the same hazard.

Two more things follow from a mirror having no cutover, and both are refusals
rather than warnings:

- **A `mirror` must not drain.** A drain is a shovel and a shovel consumes. A
  mirror that drains has emptied the cluster it exists to leave authoritative.
- **A `mirror` must not switch the endpoint.** Routing clients at green means
  every message is processed by consumers that are supposed to be throwing their
  results away, and by nothing else.

### 3. Canary by consumer — refused

Leave the messages on blue, federate to green, and move *some* of the consumers
across, so both clusters have consumers working the same stream.

This is a duplicate-delivery machine if you federate the exchange: every message
is processed once on blue and once on green, which is fine if the work is
genuinely idempotent and is a double charge if it is not. It also makes the
split ambiguous rather than controlled — which messages get duplicated depends
on federation lag and consumer speed, not on any setting you chose.

And if you federate the *queue* instead, it is worse rather than better, because
it looks like it is working. Messages flow to green only as blue's consumers
fall behind or stop, so the proportion that moves is set by load rather than by
you, it changes minute to minute, and the messages that move are *gone* from
blue.

The tool will not do this. If a `mirror` configuration is written with green
consumers that are not in shadow mode, this is what it becomes, which is why the
warning above exists.

## Canary and blue/green are separate operations

They share the executor, the guards and the provider seam. They do not share a
configuration shape, and neither is a special case of the other:

| | `blueGreen` | `canary` |
|---|---|---|
| Scope | the whole cluster, or a whole vhost | a named, enumerated set of queues and their services |
| Endpoint | switched once, for everything | switched for the named services only — which is often the harder half |
| Rollback | drain back, switch back | the same, at smaller scope, and usually cheaper |
| Repeated? | once | many times, once per workload, over days |
| Ends with | blue cold | blue still live, holding everything not yet moved |

That last row is the real distinction. A blue/green cutover ends with one
cluster carrying the estate. A canary sequence spends most of its life with the
estate genuinely split across two clusters — which is fine, because it is split
*by workload*, along a line where no queue crosses the boundary.

And the endpoint row is the one that surprises people: a canary needs
per-service routing, so that `notification-service` resolves to green while
everything else still resolves to blue. In a service mesh that is
straightforward. In an estate where every application reads the same
`RABBITMQ_URL` from the same config map, it is the piece of work the canary
actually depends on, and the tool will say so in the plan rather than discover
it at step 9.
