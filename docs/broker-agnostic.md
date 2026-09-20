# Broker-agnostic, honestly

RabbitMQ is the only broker this will support, and it will be the only one for a
long time. Possibly forever. Saying that first is the point of this page.

"Broker-agnostic" is a word that usually means one of two things. Sometimes it
means a seam exists and has been designed by someone who thought about a second
implementation. More often it means an interface was extracted from one
implementation, given a generic-sounding name, and shipped — and the second
implementation, when it eventually arrives, either does not fit or fits by
lying. This page is an attempt to be the first kind and to be checkable about
it.

## The seam

A provider implements a small set of verbs, and the verbs are at the level of
**intent**, not mechanism:

| Verb | What it means |
|---|---|
| `probe()` | What can this cluster actually do? Returns capabilities. |
| `snapshotTopology(scope)` | Read the configuration of this cluster |
| `applyTopology(snapshot, scope)` | Write that configuration onto this one, merging |
| `listAttachments(selector)` | Who is connected, publishing, consuming |
| `detach(selector)` | Make them reconnect |
| `drain(from, to, selector)` | Move what is left. Messages **leave** the source. |
| `mirror(from, to, selector)` | Copy what arrives. Messages **stay** on the source. |

| `measure(selector)` | Depth, consumer count, publish rate, unacked |
| `announce(envelope)` | Tell the clients something is happening |

That is nine verbs, and the deliberate choice is the level they sit at. `drain`
does not say "declare a shovel". It says "move what is left", and the RabbitMQ
provider is free to be as RabbitMQ-specific as it likes underneath — because a
shovel *is* how you do that on RabbitMQ, and a cutover tool that refuses to use
shovels because they are not portable is a cutover tool that copies messages by
hand.

The anti-pattern this is steering around has a shape you can recognise. It
starts with a `Broker` interface carrying eighty methods, every one of them
derived from RabbitMQ's management API, and it ends with a Kafka implementation
where sixty of them throw `UnsupportedOperationException`. That is not a seam.
That is RabbitMQ's API with a coat on.

## The capability model, and why it earns its place on one broker

Every provider declares what it can do, and the planner refuses to plan a step
whose capability is missing — naming the capability and the reason, before
touching anything, rather than failing at step four with half an estate moved.

```
TOPOLOGY_EXPORT          read a cluster's configuration
TOPOLOGY_IMPORT_MERGE    write it onto another without deleting what is there
DRAIN_BY_SHOVEL          move messages, source-destructive
MIRROR_BY_FEDERATION     copy messages, source-preserving (exchange federation)
CONNECTION_CLOSE         force a client to reconnect
CONSUMER_INSPECT         see who is consuming what
OPERATOR_POLICY          apply a policy an application cannot override
STREAM_OFFSET_READ       read a stream consumer's position
QUEUE_ARGUMENTS          read the arguments a queue was declared with
```

This is the same precedent the AceMQ client libraries set with their
`Capability` enum, and it is there for the same reason: it is better to say what
a thing cannot do than to pretend, and better still to say it before the
operation starts.

Here is the part that makes it more than future-proofing theatre. **RabbitMQ
does not have all of these on every cluster.** The capability set is not a
property of "RabbitMQ", it is a property of *this cluster, at this version, with
these plugins enabled*:

- `DRAIN_BY_SHOVEL` needs `rabbitmq_shovel` and `rabbitmq_shovel_management`
  enabled. Plenty of production clusters do not have them.
- `MIRROR_BY_FEDERATION` needs `rabbitmq_federation`. Likewise. And note that
  the capability is specifically *exchange* federation: the plugin also offers
  queue federation, which is a conditional move rather than a copy and is not
  what this verb means. [Message state](message-state.md) has the distinction,
  which is the single easiest thing to get wrong in this domain.
- `OPERATOR_POLICY` needs 3.7 or later.
- `STREAM_OFFSET_READ` needs 3.9 or later and the stream plugin, and even then
  it is a read, never a write — see [message state](message-state.md).
- `CONNECTION_CLOSE` needs the management user to hold a role that permits it,
  which a read-only monitoring account does not.

So `probe()` is a live check against the two real clusters, and `plan` runs it
first. The value of the capability model is that it turns "the cutover failed
halfway" into "blue has `rabbitmq_shovel` disabled, so step `drain-messages`
cannot run; enable the plugin or choose a different drain". That value exists
with exactly one provider written.

A second broker would be a bonus. It is not the justification.

## What a second provider would actually have to answer

If somebody does write one, these are the questions it will fail on, and knowing
them is the difference between a seam and a hope:

- **Does `drain` exist at all?** On RabbitMQ a shovel moves messages between
  clusters as a broker-side operation. Kafka has MirrorMaker, which *copies* —
  so Kafka has `MIRROR_BY_*` and no native `DRAIN_BY_*`, and a Kafka provider
  would have to declare the absence rather than fake a drain with a
  consume-and-republish loop that silently changes the delivery semantics.
- **Is "the shape of a cluster" a thing you can export?** RabbitMQ has a single
  definitions document. Kafka's equivalent is spread across topic configs, ACLs,
  quotas and a schema registry that is a different service entirely.
- **What is a consumer's position?** RabbitMQ's classic and quorum queues do not
  have one — a message is there or it is acked. Kafka's consumer group offsets
  are the central fact of the system. A provider seam that assumes either shape
  is broken for the other, which is why `measure` returns a capability-tagged
  set of observations rather than a fixed record.
- **Can you force a client to reconnect?** RabbitMQ can kill a connection
  through the management API. Not every broker can.

These are not resolved here. They are written down so the first person to try
knows what they are walking into.

## The honest summary

- There is one provider: RabbitMQ.
- The seam is nine intent-level verbs and a capability set.
- The capability set does real work today, on one broker, because a RabbitMQ
  cluster's capabilities depend on its version, its plugins and its credentials.
- No second provider will be written speculatively, and no verb will be added to
  the seam because a hypothetical broker might want it.
- If the seam turns out to be wrong when a second broker arrives, it gets
  changed. The version number is `0.x` for exactly this reason.
