# The operator

`acemq-infra-operator` runs the same cutover as `acemq-infra apply` and
`acemq-infra rollback`, from a `Cutover` custom resource. It is phase 5 of the
[roadmap](roadmap.md), and it is built around the objection
[language and shape](shape.md) raised against it: **a cutover is a process, not
desired state.** Nothing in it reconciles towards a target. A `Cutover` is
planned, approved by name, run once, and at most rolled back once; a controller
restart never starts a step again.

It is not a second implementation. The operator drives the CLI's own `Cli`
class — the same validation, the same planner, the same refusals, the same
journal — and the configuration is the same deployment file, verbatim, in
`spec.deployment`. A file that works with the binary works in a `Cutover`, and
the other way round.

## Install

The image is on GHCR as `ghcr.io/acemq-company/acemq-infra-operator`, for
linux/amd64 and linux/arm64, from `0.6.0` on. Both manifests are attached to
the release, and the `operator.yaml` there names that release's image:

```console
$ kubectl apply -f https://github.com/AceMQ-Company/acemq-infrastructure/releases/download/v0.6.0/crd.yaml
$ kubectl apply -f https://github.com/AceMQ-Company/acemq-infrastructure/releases/download/v0.6.0/operator.yaml
```

The image carries build provenance; check it before you run it:

```console
$ gh attestation verify oci://ghcr.io/acemq-company/acemq-infra-operator:0.6.0 \
    --repo AceMQ-Company/acemq-infrastructure
```

To run a build of your own instead, build it, put it where your cluster can
pull it, and change `image:` in `deploy/operator.yaml`:

```console
$ mvn -B -pl acemq-infra-operator -am package -DskipTests
$ docker build -t acemq-infra-operator:dev acemq-infra-operator
$ kind load docker-image acemq-infra-operator:dev   # or push to your registry
```

`deploy/operator.yaml` makes the `acemq-infra-system` namespace, a service
account, a ClusterRole, the lease Role, and a one-replica Deployment. The CRD is
generated from the Java model at build time and `deploy/crd.yaml` is checked
against the generated one by a test, so the two cannot drift.

The operator needs network reach to both clusters' management APIs, and the
brokers need reach to each other over AMQP — a drain is a shovel declared inside
one broker that dials the other, exactly as with the CLI.

## A Cutover

```yaml
apiVersion: infra.acemq.org/v1alpha1
kind: Cutover
metadata:
  name: orders-2026-10-04
  namespace: orders
spec:
  variables:
    - name: BLUE_USERNAME
      secretKeyRef: {name: blue-default-user, key: username}
    - name: BLUE_PASSWORD
      secretKeyRef: {name: blue-default-user, key: password}
    - name: GREEN_USERNAME
      secretKeyRef: {name: green-default-user, key: username}
    - name: GREEN_PASSWORD
      secretKeyRef: {name: green-default-user, key: password}
  deployment: |
    apiVersion: acemq.org/v1alpha1
    kind: Deployment
    metadata:
      name: orders-blue-green
    clusters:
      blue:
        management: http://blue.orders.svc:15672
        amqp: amqp://${BLUE_USERNAME}:${BLUE_PASSWORD}@blue.orders.svc:5672
        username: ${BLUE_USERNAME}
        password: ${BLUE_PASSWORD}
      green:
        # ...
    deployment:
      operation: blueGreen
      from: blue
      to: green
      # ...
```

`scripts/operator-e2e/deployment.yaml` is a complete one that runs.

### `spec`

| Field | Meaning |
|---|---|
| `deployment` | The deployment file, verbatim. Required. Blue/green and canary are both just `deployment.operation`, as in the file. |
| `variables` | Values for the file's `${VAR}` references: `{name, value}` for a literal, `{name, secretKeyRef: {name, key}}` for a key of a Secret **in the Cutover's own namespace**. |
| `approve` | The fingerprint of the plan being approved, copied from `status.planFingerprint`. |
| `dryRun` | `true`: plan only, never apply, whatever `approve` says. |
| `action` | `rollback`: undo what the journal records. The only value. |
| `journalFrom` | `{configMapRef: {name}}`: roll back from a journal a deleted Cutover left behind, instead of this resource's own. Only with `action: rollback`; [below](#rolling-back-after-the-cutover-was-deleted). |

### `status`

| Field | Meaning |
|---|---|
| `phase` | Where the state machine is; below. |
| `message` | What a human needs to know now, including the tail of the CLI's output. |
| `plan` | The plan, exactly as `acemq-infra plan` prints it. |
| `planFingerprint` | The plan's name, for `spec.approve`. |
| `journal` | The ConfigMap holding the journal: `<name>-journal`, or the one `journalFrom` names; key `journal.json`. |
| `journalOutcome` | The journal's `outcome`: `running`, `completed`, `aborted`, `stopped`, `refused`. |
| `lastStep` | The last step the journal records, and how it stands — `3 drain-messages started`. |
| `steps` | Every step in the journal, the rollback's too. |
| `rollbackOutcome` | The rollback's outcome, once one has started. |

`kubectl get cutovers` shows the phase, the plan fingerprint, the journal outcome
and the last step.

## Approval replaces the typed `yes`

`apply` at a terminal prints the plan and waits for the word `yes`. A resource
has no terminal, so the operator splits that into two writes by two parties:

1. The operator probes both clusters, plans, and writes the plan and its
   fingerprint into `status`. Phase `Planned`. Nothing has been written to a
   broker.
2. A human reads `status.plan` and sets `spec.approve` to `status.planFingerprint`:

   ```console
   $ kubectl get cutover orders-2026-10-04 -o jsonpath='{.status.plan}'
   $ kubectl patch cutover orders-2026-10-04 --type merge \
       -p '{"spec":{"approve":"3f9c1e0a7b2d4c11"}}'
   ```

3. The operator **probes and plans again**, and runs only if the approval names
   the plan as it is now. An approval of a plan that has since changed is
   refused, the new plan and fingerprint go into status, and nothing is written.

The fingerprint covers what an approval is agreeing to: the deployment file's
bytes, how each variable was given (the Secret reference, never its value), both
management URLs, both brokers' releases and capabilities, and the numbered step
list. It deliberately does not cover the message counts in the plan's text,
which change while you read them and would make every approval stale.

Approval is `apply --yes` and means what `--yes` means: consent to the run
**starting**. A prompt in the middle of a run — an `external` endpoint switch, an
`onTimeout: prompt` — has nobody to answer it, and stops the run, exactly as in a
pipeline. Use `endpoint.kind: hook` for a cutover the operator runs end to end.

## The state machine

```
Pending ──plan──▶ Planned ──approve names plan──▶ Applying ──▶ Completed
   │                 ▲  │                             │      └─▶ Failed
   └──▶ Refused ─────┘  └─ stale approval: re-planned  │
                                                      └─ operator stops ─▶ Interrupted

Completed | Failed | Interrupted ──action: rollback──▶ RollingBack ──▶ RolledBack
                                                            │      └─▶ RollbackFailed
                                                            └─ operator stops ─▶ RollbackInterrupted
```

`Refused` is a plan the planner refused or could not make — an unreachable
cluster, a missing Secret, a validation error — and is planned again on any
change to the resource and once a minute. Every other phase after `Applying` is
terminal for the run: one Cutover is one cutover. Changing `spec.deployment`
after it ran changes nothing except what a rollback will be checked against.

## The journal is the state

`apply` writes a [journal](blue-green.md) and rewrites it after every step, with
each step recorded as `started` before it touches a broker. In a pod the disk
does not outlive the process, so the operator copies **every** journal write,
synchronously, into a ConfigMap owned by the Cutover — `<name>-journal`, key
`journal.json` — before the write returns. A step is in the ConfigMap as
`started` before the step does anything. If the first copy cannot be made, the
run does not start; if a later one fails, the run is not stopped halfway and
the message says the journal is incomplete, exactly as with a file.

The ConfigMap is the same format `acemq-infra rollback --journal` reads, so a
cutover the operator ran can be rolled back by hand:

```console
$ kubectl get configmap orders-2026-10-04-journal \
    -o jsonpath='{.data.journal\.json}' > journal.json
$ acemq-infra rollback --journal journal.json -f orders.yaml
```

### The journal outlives the Cutover

A journal is the only record of how to undo a run, so deleting the Cutover does
not delete it while it is still needed. The operator holds every Cutover with a
finalizer, `infra.acemq.org/journal`, and on deletion decides:

| The journal | On deletion |
|---|---|
| none — planned, refused, dry run | nothing to keep |
| no step started (a preflight refusal) | goes with the Cutover |
| rolled back, `completed` | goes with the Cutover |
| a run that was **not rolled back** | **kept** |
| a rollback that did not complete | **kept**, to finish by hand |
| unreadable | **kept** |

A kept journal is detached — the owner reference that would have the garbage
collector take it is removed — and labelled and annotated:

```console
$ kubectl get configmaps -l infra.acemq.org/retained=true
$ kubectl get configmap orders-2026-10-04-journal \
    -o jsonpath='{.metadata.annotations.infra\.acemq\.org/retained-reason}'
```

The labels are `infra.acemq.org/retained: "true"`, `infra.acemq.org/cutover`
(the deleted Cutover's name) and `infra.acemq.org/cutover-uid`. Beside
`journal.json` the operator copies `deployment.yaml` — the deleted Cutover's
`spec.deployment`, verbatim, which a rollback is checked against byte for
byte — and `variables`, how each variable was given (Secret references, never
values). The finalizer is released only after the ConfigMap is in that shape,
and every step is idempotent, so an operator stopped half way through simply
does it again. A kept ConfigMap is yours to delete once it is no longer needed.

Delete with the default (background) cascade. `kubectl delete
--cascade=foreground` has the garbage collector delete dependents *before* the
finalizer runs, and the journal goes with them.

### Rolling back after the Cutover was deleted

From the cluster, with a new Cutover that adopts the kept journal:

```yaml
apiVersion: infra.acemq.org/v1alpha1
kind: Cutover
metadata:
  name: orders-2026-10-04-undo
  namespace: orders
spec:
  action: rollback
  journalFrom:
    configMapRef: {name: orders-2026-10-04-journal}
  variables: []      # the same variables the run had; see the ConfigMap's `variables`
  deployment: |      # the ConfigMap's deployment.yaml, verbatim
    ...
```

The operator takes the journal by becoming its owner — one journal is rolled
back by one Cutover; a second is refused and named — reports the run it
records in `status`, and rolls it back exactly as for its own: the same
refusals as `acemq-infra rollback`, so a `deployment` that is not
byte-for-byte the file the journal was written from, clusters that resolve
somewhere else now, a journal already rolled back, or a drain still running are
all refused and nothing is written. It refuses a ConfigMap that is not
labelled `infra.acemq.org/retained=true`, and a `journalFrom` without
`action: rollback`; such a Cutover never plans or applies. Once rolled back,
deleting it lets the journal go.

Or by hand, with the binary, from the same two keys:

```console
$ kubectl get configmap orders-2026-10-04-journal \
    -o jsonpath='{.data.journal\.json}' > journal.json
$ kubectl get configmap orders-2026-10-04-journal \
    -o jsonpath='{.data.deployment\.yaml}' > orders.yaml
$ acemq-infra rollback --journal journal.json -f orders.yaml
```

with the variables in the environment. The CLI does not write back to the
ConfigMap, so delete it afterwards, or a later Cutover could adopt a journal
that has in fact been rolled back — and be refused only by the brokers' state.

## Restart safety

The objection in [language and shape](shape.md) is that a controller restart
must not restart a drain. This is how it does not:

- **The run happens inside the reconcile that approved it, and the phase is
  written as `Applying` first.** If that status write fails, nothing runs. The
  SDK never runs two reconciles of one resource at the same time, so a reconcile
  that *finds* `Applying` is not the one running the cutover: that process is
  gone. It marks the Cutover `Interrupted`, reports the journal's outcome and the
  last step it records, and does nothing else — not then and not on any later
  reconcile.
- **A journal ConfigMap that already exists is never run over.** A Cutover in
  `Pending`, `Planned` or `Refused` with a journal beside it — a status that was
  lost, or restored from a backup without it — is marked `Interrupted` rather
  than applied, whatever `spec.approve` says.
- **The status is read from the API server at the top of every reconcile,** not
  from the informer's cache, which can be a write behind the operator's own last
  status update.
- **One controller.** The operator takes a lease (`acemq-infra-operator` in its
  own namespace) before it reconciles anything, and the Deployment uses the
  `Recreate` strategy, so there is never a second process that could believe an
  `Applying` was its own.

There is **no resume**. Continuing from the step after the last `done` one
would mean deciding, for a step left at `started`, whether it finished — a drain
killed halfway has moved some messages and declared a shovel that may still be
moving the rest — and there is no answer to that which is right every time.
`Interrupted` hands the decision to a human, with two ways out:

- `spec.action: rollback`, which counts a step left at `started` as having
  happened (the drain-back moves whatever the drain moved), and refuses — and is
  tried again every thirty seconds — while a shovel the cutover declared is
  still moving messages;
- or finishing it by hand, from the journal, and deleting the Cutover.

A rollback interrupted in the same way is `RollbackInterrupted`: the journal is
marked rolled back before the rollback's first write, so neither the operator
nor the CLI will start a second one, and it is finished by hand.

## Rollback

`spec.action: rollback` on a `Completed`, `Failed` or `Interrupted` Cutover runs
`acemq-infra rollback --journal <the ConfigMap> -f <spec.deployment> --yes`:
the same derivation through `Rollbacks.derive`, the same rehearsal against the
clusters as they are now, and the same refusals — a deployment file changed
since the cutover, clusters that resolve somewhere else now, a journal already
rolled back, a forward drain still running. A refusal writes nothing and leaves
the phase where it was, with the reason in `status.message`; it is asked again
while `spec.action` stays `rollback`. When the cutover did nothing that needs
undoing, the Cutover goes straight to `RolledBack` and says so.

## Credentials

Credentials are only ever Secret references. Values are read at the moment they
are needed, from the Cutover's own namespace and no other, and are never
written to the status, the plan, the fingerprint or the journal — the journal
records management URLs with any credentials stripped, as it always has.

The RabbitMQ Cluster Operator writes a `<cluster>-default-user` Secret beside
every `RabbitmqCluster`, with `username` and `password` keys, and those are the
natural references — once labelled for the operator (below). That is a
convenience, not a dependency: **nothing here reads a RabbitMQ Cluster Operator
resource.** A cluster is a management URL and a Secret, whether it is a
`RabbitmqCluster`, a StatefulSet somebody wrote by hand, or a VM outside
Kubernetes altogether — which keeps the second objection in
[language and shape](shape.md), another project's resource model becoming part
of this tool's contract, from applying.

## Security

The operator can `get` Secrets in every namespace it watches, and a Cutover
names the URLs it presents them to. Left at that, whoever can create a Cutover
in a namespace could have the operator send that namespace's Secrets to a server
of their choosing. Two checks stand in the way, and both are the operator's
own, because RBAC can express neither:

1. **Only labelled Secrets are read.** A Secret a Cutover names is first asked
   for as metadata only (`PartialObjectMetadata`; its data never reaches the
   operator). Unless it carries the label `infra.acemq.org/credentials: "true"`,
   the Cutover is `Refused` with a message naming the Secret and the label, and
   nothing more is read. Label the ones meant for cutovers:

   ```console
   $ kubectl -n orders label secret blue-default-user green-default-user \
       infra.acemq.org/credentials=true
   ```

2. **Credentials go only to allowed hosts.** Every cluster's `management` and
   `amqp` URL, after `${VAR}` substitution, must match the operator's
   allowlist before anything is dialled — before the plan's probe and before a
   rollback, so no request carrying credentials is made to a host off it. A
   Cutover that names one is `Refused` (a rollback keeps its phase) with the
   URL, credentials redacted, and the allowlist in `status.message`. The
   allowlist is the operator's environment, not the resource's:

   | `ACEMQ_INFRA_ALLOWED_URLS` | Allows |
   |---|---|
   | unset (the default) | `*.svc,*.svc.cluster.local`: in-cluster Services by their qualified names |
   | `*.svc,rabbit-*.prod.internal:15672,10.0.4.?` | those too: `host[:port]` globs, comma-separated |
   | `*` | anything — the check is off |

   `*` matches any run of characters, dots included, and `?` one; a pattern
   without a port matches every port, and a URL without one has its scheme's
   default. A short Service name such as `http://blue:15672` is not under
   `*.svc`: write `blue.<namespace>.svc`.

What remains is RBAC's: treat `create` on `cutovers` as `get` on the labelled
Secrets of that namespace, since a Cutover can still present them to any
allowed host. Set `WATCH_NAMESPACES` (comma-separated) on the Deployment to
reconcile only the namespaces that need it.

A deployment file's endpoint hook (`endpoint.kind: hook`) runs as a process
inside the operator's pod, with its service account. Whoever can
create a Cutover can run a command there, and that is not narrowed by either
check above. Until it is, give `create` on `cutovers` only to those you would
give the operator's own permissions.

## What it does not do

- **Ship a native image.** The published image runs the operator on a JVM, and
  that is what is tested; a native one is possible on the same toolchain and
  has not been built.
- **Keep backups.** A `backup:` step writes to the pod's disk, which goes with
  the pod. Disable it, or point a hook at somewhere durable.
- **Resume.** Above.
- **Run a cutover from a change to an existing one.** One Cutover is one run.

## How it is tested

`CutoverReconcilerTest` runs the state machine against fabric8's mock API server
with an engine that records instead of touching a broker: plan then approve, a
stale approval refused, a dry run, a restart that finds `Applying` and runs
nothing however often it is reconciled, a journal with no status to explain it,
rollback from `Interrupted`, a refused rollback asked again, an interrupted
rollback, a deleted Cutover keeping a journal that was not rolled back (twice,
to the same result) and letting go of one that was, a new Cutover rolling back
from a kept journal once and taking it, the refusals of `journalFrom`,
Secret resolution that names a missing Secret and never shows a value, an
unlabelled Secret refused with only its metadata ever requested, and a URL off
the allowlist refused on plan and on rollback while a local HTTP server
standing in for the broker counts zero requests.

`scripts/operator-e2e.sh` runs it for real, on a kind cluster it creates and
deletes: the RabbitMQ Cluster Operator, two `RabbitmqCluster`s, the operator
image, and three runs — a cutover of a backlog with a consumer attached,
counted message by message; a cutover rolled back through `spec.action`; and
the operator pod killed without grace while the drain runs, which has to come
back `Interrupted` with the journal untouched and then roll back; and a
completed Cutover deleted, its journal kept and then rolled back by a new
Cutover through `journalFrom`; and an unlabelled Secret and a URL off the
allowlist, both refused. CI runs it on every push.
