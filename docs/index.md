# AceMQ infrastructure

Blue/green and canary cutovers for message brokers.

The five AceMQ client libraries already handle the client side of a cutover.
They drain, they pause, they shut down gracefully, and they do it the same way
in Java, Go, .NET, Python and Ruby. What none of them can do is **move the
cluster**. That is what this is for.

> **Status: `validate`, `plan` and `apply` work against real RabbitMQ
> clusters.** [Milestone one](roadmap.md) — the configuration model, the
> validator, `probe()`, the default step list and the planner — was released as
> `0.1.0`, and neither of its two commands writes to a broker.
> [Phase 2](roadmap.md) is on `main`: the executor carries every step of the
> default blue/green list out, `apply --dry-run` re-probes both clusters and
> reports what each step would do at this moment, and the rollback is derived
> from what a run actually did and tested by running one. `apply` prints the
> plan and stops to ask before the first write, and the only thing that gets
> past that question is the word `yes` typed at a terminal. All of it is
> described in [the library](library.md).

## The pages

| Page | What it settles |
|---|---|
| [What this is](what-it-is.md) | The operation, why the client libraries are only half of it, and the two halves meeting |
| [Language and shape](shape.md) | Java with a native image, and why it is a library *and* a CLI *and*, later, an operator |
| [Broker-agnostic, honestly](broker-agnostic.md) | The provider seam, and why the capability model earns its keep on one broker |
| [Blue/green](blue-green.md) | What a cutover is for something that holds durable state |
| [Canary](canary.md) | Why a broker canary is not a traffic split, and what it is instead |
| [Message state](message-state.md) | The hard parts, named: in-flight, unacked, durable data, stream offsets, shovels that consume |
| [Configuration](configuration.md) | The proposed format, evolved from the one that already worked |
| [The library](library.md) | What is built so far, the types it exposes, and what has deliberately been left |
| [Roadmap](roadmap.md) | The phased build order and what milestone one actually delivers |
| [Prior art](prior-art.md) | What came out of the C# implementation, and what was deliberately left there |

## The shortest version

A broker cutover is four things in a fixed order, and the order is the whole
trick:

1. **Copy the shape.** Green gets blue's exchanges, queues, bindings, users and
   permissions — but not yet its retention policies, because those would start
   deleting the messages you are about to move.
2. **Stop the producers, then the consumers.** In that order. A consumer closed
   while the producers are still publishing leaves work behind it; a producer
   stopped first lets the consumers finish what they were given.
3. **Move what is left.** A shovel drains blue into green. It *consumes* from
   blue, which is correct here and wrong everywhere else.
4. **Switch the endpoint, and keep blue.** The endpoint is not this tool's to
   own. Blue stays cold for as long as the rollback window says.

Everything difficult about this tool is in the words "what is left" and in the
fact that there is no moment at which both clusters agree. See
[message state](message-state.md), which is the page to read if you only read
one.

## What it will not do

- **Provision brokers.** Terraform, the RabbitMQ Cluster Operator and your own
  pipeline already do this, and do it better. This tool moves between clusters
  that already exist.
- **Own your endpoint.** DNS, load balancers, service meshes and connection
  strings are yours. The tool tells you exactly when to switch, or runs a hook
  that does it, and never pretends to more.
- **Make a cutover atomic.** There is no such thing, for the same reason there
  is no exactly-once delivery. The tool makes the ambiguous window short,
  bounded and observable, and makes you choose which way the ambiguity falls.
- **Move stream consumer offsets between clusters.** RabbitMQ has no mechanism
  for it. The tool will tell you what will happen to your offsets and make you
  confirm it.
- **Preserve `x-delivery-count`, dead-letter history or per-queue ordering
  across a drain.** A shovel republishes. A republished message is a new
  message.
- **Replace the client libraries' drain.** It asks the application to stop
  politely. Whether it does is the application's business.
- **Carry messages.** It talks to management APIs. The message path belongs to
  the client libraries.
- **Support a broker that is not RabbitMQ.** Not today, and probably not for a
  long time. [Broker-agnostic, honestly](broker-agnostic.md) explains what the
  word is doing here.

## Licence

Apache-2.0. See [the licence page](licence.md).
