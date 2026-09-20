# What this is

Somebody has to move the cluster.

Every estate eventually faces the same afternoon: RabbitMQ 3.13 has to become
4.x, or the brokers have to leave the data centre, or the single cluster that
four teams share has to become two. The queues have messages in them. The
consumers are running. The upgrade path in the documentation assumes you can
take a maintenance window, and there is no maintenance window, because the thing
in the queue is orders.

So the estate does what estates do: somebody writes a runbook. The runbook is
forty steps of `rabbitmqctl`, `curl` against the management API, a definitions
export pasted into a text editor to strip the policies out, and a shovel
declared by hand at 2am with a URI that has a password in it. It works, mostly.
It is never written down in a way anyone can review before it runs, and the
parts that went wrong are remembered by one person.

This is a tool for turning that runbook into a file.

## The client half already exists

The AceMQ libraries do the application side of this properly, and have for a
while. A service built on them can be told to stop taking new work while
finishing what it has; it will drain its consumers, settle what is in flight,
and close cleanly rather than dropping unacked deliveries back onto the queue in
a heap. The Go library's [lifecycle
document](https://acemq.org/acemq-go-amqp/lifecycle.html) is the clearest
statement of it, and the same shape exists in the other four.

That is genuinely half the problem, and it is the half most tools skip. A
cutover that yanks connections out from under running consumers converts every
in-flight message into a redelivery and every non-idempotent handler into a
duplicate. Having five libraries that already know how to stop politely is a
real asset.

But a library that drains cannot tell the cluster it is draining *towards*. It
does not know green exists. It cannot copy a topology, cannot declare a shovel,
cannot ask who else is still connected. Those are management-API operations, on
a different port, speaking a different protocol, and nothing in the message path
should depend on them — which is exactly why
[`acemq-java-rabbitmq-admin`](https://acemq.org/acemq-java-rabbitmq-admin/) is a
separate library in the first place.

## The two halves, meeting

The orchestrator's job is to *ask*, and to *wait*, and to move what is left.

```
    acemq-infrastructure                      the application
    ─────────────────────                     ───────────────
    publish an announcement  ────────────▶    stop accepting new work
                                              finish what is in flight
    poll: publish rate, unacked  ◀───────      settle, ack, close
    close what is still attached
    drain the remainder
    switch the endpoint      ────────────▶    reconnect, to green
```

The announcement is the interesting arrow. The old C# implementation had it —
an `envelope` with an exchange, a routing key and a payload, published to tell
clients a deployment had started — and it was the best idea in that repository.
It is advisory, and it has to be: a client that is not listening is not stopped
by it, and the tool cannot know which clients those are. But for the services
that *do* listen, it turns a forced disconnection into a requested drain, and
the difference between those two is the difference between a clean cutover and a
morning of duplicate-order tickets.

So the tool has two levers and uses them in order. Ask first, with the
announcement and a guard that waits for the publish rate to reach zero. Insist
second, by closing connections through the management API, which is the only
instrument the management API has and is a blunt one.

## What "estate level" means

The scope is deliberately above a single broker and below the whole platform.

**Above a single broker,** because the interesting operations involve two. There
is nothing to orchestrate about one cluster; `rabbitmqctl` handles that. The
moment there are two — a blue and a green, a source and a target, an old region
and a new one — you need something that holds both open at once, knows which is
which, and can say what state the pair is in.

**Below the whole platform,** because provisioning is somebody else's job and a
better-solved one. The old implementation started down this road — there is a
`src/Cloud/Docker` and a `src/Cloud/AWS` in it — and stopping was the right
call. Terraform makes clusters. The RabbitMQ Cluster Operator makes clusters.
Your pipeline makes clusters. This tool takes two that exist and moves an estate
from one to the other.

The seam is sharp and worth stating as a rule: **if the operation can be
expressed as desired state, it is not this tool's job.** A cluster is desired
state. A cutover is not — it is a process, with a beginning, a middle where
things are genuinely ambiguous, and an end. That distinction comes back in
[language and shape](shape.md), because it is most of the argument against
building this as a Kubernetes operator first.

## Who this is for

Three people, and they want different things, which is why the tool has more
than one face:

- **The platform engineer** who has to do the migration on Thursday and wants
  to put the plan in a pull request on Monday so that four other people can
  argue with it. They want a file and a `plan` command.
- **The pipeline** that runs the cutover for the twentieth time, in
  staging, unattended, and needs an exit code. It wants a single binary with no
  runtime to install.
- **The estate's own automation**, which already knows things the config file
  does not — which cluster is live, what the current release is, who to page —
  and wants to drive the same machinery from code rather than by shelling out.

Those three wants are a CLI, a distribution decision, and a library,
respectively. [Language and shape](shape.md) is where they get settled.
