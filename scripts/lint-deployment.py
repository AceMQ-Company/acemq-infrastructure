#!/usr/bin/env python3
"""Check a deployment file against the format proposed in docs/configuration.md.

    ./scripts/lint-deployment.py examples/blue-green.yaml
    ./scripts/lint-deployment.py examples/*.yaml

Touches no broker. This is the half of `acemq-infra validate` that can exist
before the tool does -- and it exists now because a configuration format that
lives only in a document drifts from the examples that illustrate it within a
week. CI runs it over everything in examples/, which is what keeps the format
honest while it is still only a plan.

The structural rules at the bottom are the interesting part. They are the
mistakes docs/message-state.md is about, expressed as checks:

  * retention policies copied to the target before the drain, which discards
    the backlog on arrival
  * a drain with no guard waiting on unacked, which shovels messages that
    consumers were still holding
  * `percentage` anywhere in a canary, which is the partitioned-queue bug
  * a missing `semantics`, which is the tool choosing at-least-once or
    at-most-once on the user's behalf

Requires PyYAML. Falls back to a clear message if it is absent rather than a
traceback, because the commonest reader of this script has just cloned the
repository.
"""
from __future__ import annotations

import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - environment, not logic
    sys.exit("PyYAML is required:  python3 -m pip install pyyaml")


API_VERSION = "acemq.org/v1alpha1"
OPERATIONS = {"blueGreen", "canary", "mirror"}
SEMANTICS = {"atLeastOnce", "atMostOnce"}
PROVIDERS = {"rabbitmq"}
ON_TIMEOUT = {"abort", "continue", "prompt"}
ENDPOINT_KINDS = {"external", "hook"}

CAPABILITIES = {
    "TOPOLOGY_EXPORT",
    "TOPOLOGY_IMPORT_MERGE",
    "DRAIN_BY_SHOVEL",
    "MIRROR_BY_FEDERATION",
    "CONNECTION_CLOSE",
    "CONSUMER_INSPECT",
    "OPERATOR_POLICY",
    "STREAM_OFFSET_READ",
    "QUEUE_ARGUMENTS",
}

# The action each step may carry. A step has exactly one action, optionally with
# a waitFor beside it -- that pairing is what makes the plan output readable.
ACTIONS = {
    "requires",          # probe: assert capabilities before anything happens
    "copyTopology",
    "announce",
    "closeConnections",
    "drain",
    "mirror",
    "endpoint",
}

TOPOLOGY_PARTS = {
    "exchanges", "queues", "bindings", "users", "permissions",
    "parameters", "policies", "operatorPolicies", "vhosts",
}

# The topology parts that take effect the instant they land and can therefore
# destroy a backlog that has not arrived yet. docs/message-state.md.
RETENTION_PARTS = {"policies", "operatorPolicies"}

VAR = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


class Report:
    def __init__(self, path: str) -> None:
        self.path = path
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, where: str, message: str) -> None:
        self.errors.append(f"{where}: {message}")

    def warn(self, where: str, message: str) -> None:
        self.warnings.append(f"{where}: {message}")

    @property
    def ok(self) -> bool:
        return not self.errors


def as_dict(value, report: Report, where: str):
    if value is None:
        return {}
    if not isinstance(value, dict):
        report.error(where, f"expected a mapping, found {type(value).__name__}")
        return {}
    return value


def check_clusters(doc, report: Report) -> set[str]:
    clusters = as_dict(doc.get("clusters"), report, "clusters")
    if not clusters:
        report.error("clusters", "at least one cluster is required")
        return set()
    for name, body in clusters.items():
        where = f"clusters.{name}"
        body = as_dict(body, report, where)
        for field in ("management", "username", "password"):
            if not body.get(field):
                report.error(where, f"{field} is required")
        if not body.get("amqp"):
            # Needed by drain and mirror, which run broker-side and therefore
            # need an AMQP URI the *broker* can reach, not the management one.
            report.warn(where, "no amqp URI; drain and mirror steps need one")
        tls = as_dict(body.get("tls"), report, f"{where}.tls")
        if tls.get("verify") is False:
            report.warn(where, "tls.verify is false — the management document "
                               "this reads is a credential")
    return set(clusters)


def check_endpoint(doc, report: Report) -> None:
    endpoint = doc.get("endpoint")
    if endpoint is None:
        # Only a warning: a mirror never switches an endpoint.
        report.warn("endpoint", "absent — a cutover that never moves the "
                                "endpoint leaves clients on the source")
        return
    endpoint = as_dict(endpoint, report, "endpoint")
    kind = endpoint.get("kind")
    if kind not in ENDPOINT_KINDS:
        report.error("endpoint.kind", f"{kind!r} is not one of {sorted(ENDPOINT_KINDS)}")
    if kind == "external" and not endpoint.get("description"):
        report.error("endpoint", "kind: external must carry a description — it "
                                 "is what a human is shown when the plan stops")
    if kind == "hook" and not endpoint.get("run"):
        report.error("endpoint", "kind: hook must carry a run command")


def check_step(index: int, step, clusters: set[str], operation: str, report: Report) -> dict:
    where = f"deployment.steps[{index}]"
    step = as_dict(step, report, where)
    step_id = step.get("id")
    if not step_id:
        report.error(where, "every step needs an id — the plan, the status "
                            "output and the errors all refer to it")
    where = f"deployment.steps[{index}]({step_id})" if step_id else where

    actions = [key for key in step if key in ACTIONS]
    if not actions:
        # A step whose only content is a waitFor is legitimate: waiting is what
        # it does. `pause-producers` is exactly that -- it changes nothing and
        # blocks until blue's publish rate reaches zero.
        if "waitFor" not in step:
            report.error(where, "no action; expected a waitFor, or one of "
                                f"{sorted(ACTIONS)}")
    elif len(actions) > 1:
        report.error(where, f"{len(actions)} actions ({', '.join(sorted(actions))}); "
                            "a step does exactly one thing")

    unknown = set(step) - ACTIONS - {"id", "waitFor"}
    if unknown:
        report.error(where, f"unknown keys: {', '.join(sorted(unknown))}")

    if "requires" in step:
        required = step.get("requires") or []
        if not isinstance(required, list):
            report.error(where, "requires must be a list of capability names")
        else:
            for capability in required:
                if capability not in CAPABILITIES:
                    report.error(where, f"unknown capability {capability!r}")

    for key in ("copyTopology", "drain", "mirror"):
        if key in step:
            body = as_dict(step[key], report, f"{where}.{key}")
            for end in ("from", "to"):
                name = body.get(end)
                if name is None:
                    if key != "copyTopology":
                        report.error(where, f"{key}.{end} is required")
                elif name not in clusters:
                    report.error(where, f"{key}.{end}: no cluster named {name!r}")

    if "copyTopology" in step:
        body = as_dict(step["copyTopology"], report, f"{where}.copyTopology")
        for field in ("include", "exclude"):
            for part in body.get(field) or []:
                if part not in TOPOLOGY_PARTS:
                    report.error(where, f"copyTopology.{field}: unknown part {part!r}")

    if "endpoint" in step:
        body = as_dict(step["endpoint"], report, f"{where}.endpoint")
        target = body.get("target")
        if target is not None and target not in clusters:
            report.error(where, f"endpoint.target: no cluster named {target!r}")

    if "waitFor" in step:
        guard = as_dict(step["waitFor"], report, f"{where}.waitFor")
        on = guard.get("on")
        if on is not None and on not in clusters:
            report.error(where, f"waitFor.on: no cluster named {on!r}")
        if not guard.get("timeout"):
            report.error(where, "waitFor without a timeout waits forever")
        policy = guard.get("onTimeout")
        if policy is not None and policy not in ON_TIMEOUT:
            report.error(where, f"waitFor.onTimeout: {policy!r} is not one of "
                                f"{sorted(ON_TIMEOUT)}")

    if operation == "mirror" and "drain" in step:
        report.error(where, "a mirror must not drain — a drain consumes from "
                            "the source and a mirror is an observation")

    if "mirror" in step:
        check_mirror_target(as_dict(step["mirror"], report, f"{where}.mirror"),
                            where, report)

    return step


def check_mirror_target(body: dict, where: str, report: Report) -> None:
    """A mirror federates exchanges. Naming queues asks for the wrong mechanism.

    A federated QUEUE pulls from its upstream only when the upstream has no
    local consumers -- a conditional move, not a copy. A mirror built from it
    sits empty while the source is healthy and starts draining the source the
    moment it is not, which is the opposite of what a mirror is for. A federated
    EXCHANGE replays upstream publishes into its own bound queues, and that is
    the copy. docs/message-state.md.
    """
    if body.get("queues"):
        report.error(
            where,
            "mirror.queues asks for queue federation, which pulls only when the "
            "upstream has no local consumers — a conditional MOVE, not a copy. "
            "A mirror federates exchanges: use mirror.exchanges "
            "(docs/message-state.md)",
        )
    elif not body.get("exchanges"):
        report.error(where, "mirror.exchanges is required — a mirror federates "
                            "exchanges")


def check_ordering(steps: list[dict], report: Report) -> None:
    """The rules that exist because of docs/message-state.md."""
    drain_at = next((i for i, s in enumerate(steps) if "drain" in s), None)
    if drain_at is None:
        return

    # Retention policies landing on the target before the backlog does.
    for i, step in enumerate(steps[:drain_at]):
        # Only steps that actually copy topology. Reading `or {}` here and then
        # testing "no include list means everything" would flag every step in
        # the file, which is what the first version of this check did.
        if "copyTopology" not in step:
            continue
        body = step["copyTopology"]
        if not isinstance(body, dict):
            continue
        included = set(body.get("include") or [])
        excluded = set(body.get("exclude") or [])
        # An include list that names them, or no include list at all (which
        # means everything) without excluding them.
        lands_early = (included & RETENTION_PARTS) or (
            not included and not (RETENTION_PARTS <= excluded)
        )
        if lands_early:
            report.error(
                f"deployment.steps[{i}]({step.get('id')})",
                "copies policies to the target before the drain. A message-ttl "
                "or max-length policy is live the instant it lands and will "
                "discard the backlog on arrival. Exclude policies and "
                "operatorPolicies here, and copy them in a step after the drain "
                "(docs/message-state.md)",
            )

    # A drain with nothing having waited for consumers to settle first.
    settled = any(
        isinstance(s.get("waitFor"), dict)
        and ("unacked" in s["waitFor"] or "publishRate" in s["waitFor"])
        for s in steps[:drain_at]
    )
    if not settled:
        report.error(
            f"deployment.steps[{drain_at}]({steps[drain_at].get('id')})",
            "drains with no preceding waitFor on publishRate or unacked. "
            "Unacked deliveries requeue on the SOURCE when a connection closes, "
            "so draining first shovels messages consumers were still holding "
            "(docs/message-state.md)",
        )

    # A drain with no guard of its own is a fire-and-forget shovel.
    if not isinstance(steps[drain_at].get("waitFor"), dict):
        report.warn(
            f"deployment.steps[{drain_at}]({steps[drain_at].get('id')})",
            "no waitFor on the drain — nothing confirms the source emptied "
            "before the next step runs",
        )


def check_deployment(doc, clusters: set[str], report: Report) -> None:
    deployment = as_dict(doc.get("deployment"), report, "deployment")
    if not deployment:
        report.error("deployment", "is required")
        return

    operation = deployment.get("operation")
    if operation not in OPERATIONS:
        report.error("deployment.operation",
                     f"{operation!r} is not one of {sorted(OPERATIONS)}")

    for end in ("from", "to"):
        name = deployment.get(end)
        if not name:
            report.error(f"deployment.{end}", "is required")
        elif name not in clusters:
            report.error(f"deployment.{end}", f"no cluster named {name!r}")

    semantics = deployment.get("semantics")
    if operation == "mirror":
        if semantics is not None:
            report.warn("deployment.semantics",
                        "a mirror moves nothing; semantics has no meaning here")
    elif semantics is None:
        report.error(
            "deployment.semantics",
            "is required and has no default. atLeastOnce means a message may "
            "be processed on both clusters; atMostOnce means one may be "
            "stranded. The tool will not choose this for you "
            "(docs/message-state.md)",
        )
    elif semantics not in SEMANTICS:
        report.error("deployment.semantics",
                     f"{semantics!r} is not one of {sorted(SEMANTICS)}")

    if operation == "canary":
        scope = as_dict(deployment.get("scope"), report, "deployment.scope")
        if not scope:
            report.error("deployment.scope",
                         "a canary must enumerate its scope — the unit is a "
                         "queue and everything attached to it")
        else:
            if not scope.get("queues"):
                report.error("deployment.scope.queues", "is required for a canary")
            if not scope.get("services"):
                report.error(
                    "deployment.scope.services",
                    "is required for a canary. It is what the consumer check "
                    "matches on, and that check is what stops a canary from "
                    "partitioning the queue (docs/canary.md)",
                )
        for key in ("percentage", "percent", "weight", "split", "trafficSplit"):
            if key in scope or key in deployment:
                report.error(
                    "deployment",
                    f"{key!r} has no meaning for a broker. Splitting producers "
                    "by percentage partitions the queue across two clusters; "
                    "the unit of a broker canary is a whole workload "
                    "(docs/canary.md)",
                )

    if operation == "mirror":
        if not deployment.get("mirror"):
            report.error("deployment.mirror", "is required for a mirror operation")
        else:
            check_mirror_target(
                as_dict(deployment["mirror"], report, "deployment.mirror"),
                "deployment.mirror", report)

    backup = deployment.get("backup")
    if backup is not None:
        backup = as_dict(backup, report, "deployment.backup")
        if backup.get("enabled") and backup.get("redactCredentials") is False:
            report.warn(
                "deployment.backup",
                "redactCredentials is off. A definitions export carries "
                "password hashes; the file it writes is a credential",
            )
    elif operation != "mirror":
        report.warn("deployment.backup",
                    "absent — nothing captures the source's definitions before "
                    "the cutover touches anything")

    steps = deployment.get("steps")
    if steps is None:
        report.warn("deployment.steps",
                    "absent; the default step list for this operation would be "
                    "used and printed. Fine to start with, worth pinning before "
                    "a real cutover")
        return
    if not isinstance(steps, list) or not steps:
        report.error("deployment.steps", "must be a non-empty list")
        return

    seen: set[str] = set()
    parsed = []
    for index, step in enumerate(steps):
        parsed_step = check_step(index, step, clusters, operation, report)
        step_id = parsed_step.get("id")
        if step_id:
            if step_id in seen:
                report.error(f"deployment.steps[{index}]",
                             f"duplicate step id {step_id!r}")
            seen.add(step_id)
        parsed.append(parsed_step)

    check_ordering(parsed, report)


def check_variables(text: str, report: Report) -> None:
    """Report ${VAR} references that are not set in this environment.

    A warning rather than an error: a file is usually linted somewhere the
    production secrets are deliberately absent. The tool itself treats an unset
    variable as fatal at run time, which is the change from the old C#
    behaviour of leaving the literal string in place.
    """
    missing = sorted({name for name in VAR.findall(text) if name not in os.environ})
    if missing:
        report.warn("${} interpolation",
                    f"not set in this environment: {', '.join(missing)}")


def lint(path: str) -> Report:
    report = Report(path)
    try:
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
    except OSError as error:
        report.error(path, str(error))
        return report

    check_variables(text, report)

    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError as error:
        report.error("yaml", str(error).replace("\n", " "))
        return report

    if not isinstance(doc, dict):
        report.error("yaml", "the document must be a mapping")
        return report

    if doc.get("apiVersion") != API_VERSION:
        report.error("apiVersion", f"expected {API_VERSION!r}, "
                                   f"found {doc.get('apiVersion')!r}")
    if doc.get("kind") != "Deployment":
        report.error("kind", f"expected 'Deployment', found {doc.get('kind')!r}")

    metadata = as_dict(doc.get("metadata"), report, "metadata")
    if not metadata.get("name"):
        report.error("metadata.name", "is required — it names the plan, the "
                                      "backup file and every log line")

    provider = doc.get("provider")
    if provider not in PROVIDERS:
        report.error("provider", f"{provider!r} is not one of {sorted(PROVIDERS)}. "
                                 "RabbitMQ is the only provider that exists "
                                 "(docs/broker-agnostic.md)")

    clusters = check_clusters(doc, report)
    check_endpoint(doc, report)
    check_deployment(doc, clusters, report)

    known = {"apiVersion", "kind", "metadata", "clusters", "provider",
             "providerConfig", "endpoint", "deployment", "rollback", "streams"}
    for key in set(doc) - known:
        report.error(key, "unknown top-level key")

    return report


def summarise(doc_path: str) -> str:
    try:
        with open(doc_path, encoding="utf-8") as handle:
            doc = yaml.safe_load(handle) or {}
    except (OSError, yaml.YAMLError):
        return ""
    deployment = doc.get("deployment") or {}
    steps = deployment.get("steps") or []
    return (f"{deployment.get('operation', '?')}, {len(steps)} steps, "
            f"{len(doc.get('clusters') or {})} clusters")


def main(argv: list[str]) -> int:
    paths = argv[1:]
    if not paths:
        print(__doc__.strip().split("\n\n")[1].strip(), file=sys.stderr)
        return 2

    failed = 0
    for path in paths:
        report = lint(path)
        for warning in report.warnings:
            print(f"  warn  {path}: {warning}")
        for error in report.errors:
            print(f"  error {path}: {error}")
        if report.ok:
            print(f"{path}: ok — {summarise(path)}")
        else:
            print(f"{path}: {len(report.errors)} error(s)")
            failed += 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
