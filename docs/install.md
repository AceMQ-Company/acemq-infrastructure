# Installing it

`acemq-infra` is a single executable with no JVM to install and nothing to
unpack. It is built with GraalVM, it starts in about ten milliseconds, and it is
about 30MB — which is the trade [language and shape](shape.md) named when it
chose Java, and it is worth restating that the size is real rather than
pretending otherwise.

There are three of them, one per platform, and the library is published
alongside as Maven artifacts for anybody embedding it. Everything is
GitHub-hosted, which is the standing policy across this family of repositories
and not a gap.

## The binary

```bash
VERSION=0.4.0
PLATFORM=linux-amd64        # or linux-arm64, or darwin-arm64
BASE=https://github.com/AceMQ-Company/acemq-infrastructure/releases/download/v$VERSION

curl -fsSLO "$BASE/acemq-infra-$VERSION-$PLATFORM"
curl -fsSLO "$BASE/SHA256SUMS"
grep " acemq-infra-$VERSION-$PLATFORM$" SHA256SUMS | sha256sum -c -

chmod +x "acemq-infra-$VERSION-$PLATFORM"
sudo mv "acemq-infra-$VERSION-$PLATFORM" /usr/local/bin/acemq-infra
acemq-infra --version
```

On macOS the checksum tool is spelled `shasum -a 256 -c -`. If the file came
down through a browser rather than through `curl`, Gatekeeper will have attached
a quarantine flag and refuse to run it; `xattr -d com.apple.quarantine
acemq-infra` removes it. The binaries are not code-signed or notarised, and that
is a gap rather than a decision — it needs an Apple Developer identity, and one
has not been set up.

### Which platforms there are, and why only three

| Platform | Built on | What is run against it |
|---|---|---|
| `linux-amd64` | `ubuntu-latest` | the whole suite, including both cutovers against real brokers |
| `linux-arm64` | `ubuntu-24.04-arm` | the whole suite, including both cutovers against real brokers |
| `darwin-arm64` | `macos-14` | the command surface, the configuration format and a TLS handshake |

GraalVM builds for the host. There is no cross-compilation, so three targets
means three runners, which [language and shape](shape.md) called a permanent tax
on every release and which is exactly what it turned out to be. The consequence
worth knowing is the good one: every binary on a release has been executed on the
architecture it was built for, and none of them is something nobody has run.

`darwin-amd64` is not published. Nothing rules it out — GitHub's Intel macOS
runners still exist — and it will be added when somebody says they are on one.
Windows is not published and is not planned.

The darwin-arm64 row is shorter than the other two and the reason is specific:
GitHub's macOS runners have no Docker daemon, and the cutover tests are two
RabbitMQ containers on a shared network. So the macOS binary is checked for
everything that does not need a broker and the cutover itself is proved on Linux.
`ci.yml` carries a step that **fails** if a macOS runner ever turns out to have
Docker, so that this paragraph cannot quietly stop being true.

## Checking what you downloaded

Two different questions, and they are worth keeping apart.

**Did it arrive intact?** That is the checksum, and it is the `sha256sum -c`
line above. `SHA256SUMS` is published next to the binaries, so it catches a
truncated download, a proxy that returned an error page with a 200, and a
corrupted cache. It says nothing about who built the binary, because it comes
from the same place the binary did.

**Did it come out of this repository?** That is
[build provenance](https://docs.github.com/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds).
Every binary is signed at release time with a statement naming the workflow, the
repository and the commit it was built from, recorded in a public transparency
log:

```bash
gh attestation verify acemq-infra-0.4.0-linux-amd64 \
  --repo AceMQ-Company/acemq-infrastructure
```

That is the check worth running for something you are about to point at a
production broker. It needs the `gh` CLI and a token; it does not need anything
installed on the release side.

In [the GitHub Action](#the-github-action) the checksum is always verified and
the attestation is verified when you ask for it — `verify-attestation: true`
with a `github-token`.

## The library

For an estate's own automation — the pipeline that already knows which cluster is
live, the runbook tool, the integration test that wants to assert a cutover
happened. [The library](library.md) is the page about what it exposes.

```xml
<repositories>
  <repository>
    <id>acemq</id>
    <url>https://acemq-company.github.io/maven/</url>
  </repository>
</repositories>

<dependency>
  <groupId>org.acemq</groupId>
  <artifactId>acemq-infra-cli</artifactId>
  <version>0.4.0</version>
</dependency>
```

`acemq-infra-cli` pulls `acemq-infra-core`, `acemq-infra-rabbitmq` and
`acemq-infra-execute` with it. Depend on `acemq-infra-core` alone for the model,
the validator and the planner with no broker client anywhere on the classpath,
which is a property [the library](library.md) explains rather than an accident.

## The GitHub Action

A plan or an apply from a pipeline, with nothing installed. The action downloads
the binary for whatever runner it is on, checks it, and runs it.

```yaml
- uses: AceMQ-Company/acemq-infrastructure@v0.4.0
  with:
    command: plan
    file: deployment.yaml
```

What the tool prints goes into the job summary, which is where a reviewer will
actually read it — a plan in a pull request is the artifact that makes the rest
of this tool trustworthy, and a fold in a log is where that goes to die. Pass
`summary: false` to turn it off.

| Input | What it does |
|---|---|
| `command` | `validate`, `plan` or `apply` |
| `file` | the deployment file |
| `version` | which release to run; defaults to the tag the action was used at, so `@v0.4.0` runs `0.4.0` |
| `dry-run` | `apply` only: rehearse every step against both clusters and write nothing |
| `assume-yes` | `apply` only: consent to the run starting, which is `--yes` |
| `require-variables` | `validate` only: an unset `${VAR}` is an error |
| `args` | anything else, passed straight through |
| `working-directory` | where to run, for relative paths in the file |
| `verify-attestation` | check build provenance as well as the checksum; needs `github-token` |
| `github-token` | a token, for that check |
| `summary` | put the output in the job summary; on by default |

It outputs `exit-code` and `output-file`, so a later step can read what happened
without re-running anything.

There is deliberately no second set of names for the flags. A pipeline where
`--dry-run` is spelled one way in the shell and another way in the Action is a
pipeline where somebody eventually writes the wrong one.

The one exception is `assume-yes`, and it is YAML's fault rather than a
preference. `yes` is a boolean in YAML 1.1, so an input by that name is a key
that a good deal of the tooling around workflows — linters, editors, anything
built on libyaml — rewrites as `true:` on the way past. An input nobody can
write reliably is worse than an input with a second name, and `--assume-yes` is
what every package manager calls the same thing.

### Applying from a pipeline

`apply` stops and asks before it writes anything, and a runner is nobody. So:

```yaml
- uses: AceMQ-Company/acemq-infrastructure@v0.4.0
  with:
    command: apply
    file: deployment.yaml
    assume-yes: true
```

`assume-yes: true` consents to the run **starting** and to nothing after it. A file with
`endpoint: kind: external` still refuses, in preflight, before the first write —
an external switch waits for a human by definition, and discovering that at step
eight would mean discovering it with the drain already done. A deployment meant
to run end to end without a person needs `endpoint: kind: hook`, which is
[the configuration page](configuration.md)'s `run:` and `args:`.

The honest recommendation is `dry-run: true` on pull requests and `assume-yes: true`
only on a workflow somebody triggers deliberately.

## Building it yourself

```bash
mvn -Pnative -pl acemq-infra-native clean verify
```

with a GraalVM JDK on the path. That builds the image and then runs the suite
against it, which takes several minutes and needs a Docker daemon for the two
cutover tests. The binary lands at `acemq-infra-native/target/acemq-infra`.

`mvn verify` without the profile does not build it and does not need GraalVM.

## What is registered for reflection, and why

Worth reading if you embed the library in your own native image, and worth
reading anyway because it is the one thing that makes this artifact different
from the jar.

Reflection is what a native image takes away. A missing registration does not
fail the build; it fails at run time, in the field, with a message about a class
that is not there. This repository ships exactly one registration file,
`acemq-infra-rabbitmq`'s, and it covers one thing: **the twenty types
`acemq-java-rabbitmq-admin` binds management API responses into.** Jackson
reaches those through an annotated constructor that no call site names.

Without it the image builds silently and then reports every cluster as
unreachable:

```
acemq-infra: could not probe cluster 'blue' at http://…: could not read the
management API's answer for whoami. This is usually something other than
RabbitMQ answering on that port.
```

It is not something else answering. It is Jackson being handed a class it cannot
construct, and the message is the probe being tactful about a failure it has no
way to diagnose. That file's proper home is the admin client's own jars; it sits
here until it is there.

Nothing else needs one, and that is a result rather than an assumption:

| | |
|---|---|
| The deployment file model | SnakeYAML is used through `compose()` — the node tree, with marks — and the records are built from it by hand. Nothing is constructed reflectively, so nothing needs registering. |
| The enums parsed from strings | `EndpointKind.of`, `Operation`, `Semantics`, `OnTimeout` and the rest match on a string and return a constant. No `valueOf` by reflection anywhere. |
| The step types | A sealed interface and pattern matching, resolved at compile time. |
| The management API's JSON that the executor reads | Maps and trees rather than beans, which the parent pom chose for this reason before there was a native image to prove it right. |
| `ServiceLoader` | Nothing here uses one. |
| `java.io.Console.isTerminal` | The one piece of reflection this repository writes itself, in `Terminal`. It needs no registration — it is a JDK method on a JDK class — and both of its branches are exercised against the real binary, at a pseudo-terminal and through a pipe. |
| TLS | Works unchanged. `NativeHttpsIT` completes a handshake against a certificate the test generates, with the truststore passed as `-Djavax.net.ssl.trustStore`, which is also how an estate points this tool at a private certificate authority. |

The GraalVM shared reachability-metadata repository is **off** in this build. It
would supply some of the above, and letting it would mean a release build whose
correctness depends on a network fetch and metadata that lives in somebody else's
repository. The dependency set here is four libraries; the list is short enough
to read.

## Keeping all of that true

`ci.yml` builds the binary on every push and every pull request, on both Linux
architectures, and runs the cutover suite against it. Not a nightly. The rule
from [the roadmap](roadmap.md) and [language and shape](shape.md) is that the
test suite runs against the binary rather than the jar, and a nightly is a rule
that a pull request can walk past.

The release workflow does the same thing before anything is published: the three
binaries are built and tested first, and the Maven artifacts only go to the feed
once the thing people download has moved messages between two real brokers.

## Related

- [Language and shape](shape.md) — why Java and a native image, and what it costs
- [The library](library.md) — what the library exposes, for embedding
- [Configuration](configuration.md) — the deployment file the binary reads
- [Roadmap](roadmap.md) — what each phase delivered
