# Reporting a vulnerability

Email **security@acemq.com** with what you found and how to reproduce it. Please
do not open a public issue for anything exploitable.

You should get an acknowledgement within two working days, and an assessment of
whether it is a vulnerability, what is affected, and a rough timeline within a
week. If a fix is warranted, we will tell you when it is released and credit you
unless you would rather we did not.

## What is in scope

This repository currently contains documentation, shell and Python scripts, and
example configuration — there is no product code yet. That narrows the surface
considerably, and it does not narrow it to nothing.

Things worth reporting:

- **A credential leaking out of `scripts/blue-green-lab.sh`.** It handles
  broker passwords and fetches definitions documents, which contain password
  hashes. A password on a command line visible in `ps`, a credential written to
  a file with permissive modes, or a definitions document echoed into a log
  would all be real.
- **Command injection in either script.** Both take arguments — a cluster name,
  a queue name, a file path — and interpolate them. A queue name that escapes a
  shell quote or a JSON string, or a YAML value in `lint-deployment.py` that
  escapes its context, is a finding.
- **Unsafe YAML deserialisation.** `lint-deployment.py` uses `yaml.safe_load`
  on purpose. Anything that reaches `yaml.load` or otherwise constructs
  arbitrary Python objects from a configuration file is a vulnerability, and
  the whole point of a linter is that people run it on files they were sent.
- **Anything in the documented design that would be insecure if built as
  described.** This is the unusual one, and it is the most valuable report we
  could receive right now. The design is the product at this stage. If the
  configuration format in [`docs/configuration.md`](docs/configuration.md) has a
  hole in it — a hook invocation that is really a shell injection, an
  interpolation that leaks a secret into a plan artifact, a backup path that can
  be made to overwrite something — that is far cheaper to fix now than after it
  is implemented.

## What is not

- **`${VAR}` interpolation resolving secrets from the environment.** That is the
  mechanism, and it exists so secrets stay out of the file.
- **A definitions document containing password hashes.** That is how RabbitMQ's
  definitions export works. The tool's obligations around it — redact by
  default, never log it, never put it in a plan artifact, `0600` when written
  unredacted — are documented in
  [`docs/message-state.md`](docs/message-state.md), and a gap in *those* is in
  scope.
- **The lab script's default `admin`/`admin` credentials.** It stands up two
  throwaway brokers on localhost for development. Do not point it at anything
  real; it will not stop you, and that is not a vulnerability.
- **Vulnerabilities in RabbitMQ itself** — report those to Broadcom.
- **Vulnerabilities in Docker, pandoc, or PyYAML** — report those upstream.
- Findings from a scanner with no demonstrated impact.

## Supported versions

Nothing has been released and nothing is tagged. The `main` branch is what
exists, and a fix means a commit on it. Once versions are tagged this section
will say which of them get fixes.

## A standing note on what this tool will be

When it is built, it will hold administrative credentials for two brokers at
once and be able to close every connection on either of them. It is, by design,
one of the most dangerous things in an estate that runs it.

Two consequences shape the design and are worth stating here rather than
discovering later:

- **It should never need more privilege than the operation requires.** The
  capability probe described in
  [`docs/broker-agnostic.md`](docs/broker-agnostic.md) checks what the
  configured credentials can actually do and refuses to plan a step it cannot
  perform — rather than being handed an administrator account by default because
  that is simpler.
- **`plan` writes nothing.** That is a security property as much as a usability
  one: the command that gets run casually, in CI, against production, on a
  branch, is the one that cannot change anything.
