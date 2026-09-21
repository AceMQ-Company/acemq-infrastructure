#!/usr/bin/env python3
"""Fail the build when a test skipped.

Usage:
    check-nothing-skipped.py [root]

A skip reads as success. The AceMQ client libraries learned that the hard way and
Python learned it twice over: seven TLS tests skipped on every run for as long as
that job had existed, reported as "53 passed, 7 skipped", green throughout,
because nothing there started a TLS broker and nothing noticed that nothing had.

Nothing in this repository needs a broker yet -- the validator is a pure function
over a parsed file and the whole suite runs in under a second -- so there is no
ambient configuration whose absence could quietly remove coverage. What is left
is a skip somebody writes: an @Disabled on a test that went flaky, or an
Assumptions.assumeTrue that is false everywhere. Those are exactly the ones worth
failing on, and today there are none.

The guard is installed now rather than when it is first needed, because the first
time it is needed will be the release that adds probe() against live brokers, and
that is the worst moment to discover the gate does not exist.

Why the XML reports rather than the "Tests run: N, ..., Skipped: S" line
---------------------------------------------------------------------

Surefire and Failsafe print that summary and also write
target/{surefire,failsafe}-reports/TEST-*.xml. This reads the XML:

  - The summary counts skips without naming them. "3 skipped" and no names is a
    gate people switch off rather than investigate; the XML carries the class,
    the method and the reason JUnit gave, which is what makes it actionable.
  - The XML is on disk even when the build dies before Maven prints a summary,
    and it survives a reactor that printed its summary several times.

There are no exemptions. If one is ever needed, it goes in EXEMPT below with the
reason it cannot run, which makes adding a second one a visible edit to this file
rather than a flag on a workflow line nobody reads.
"""

import os
import sys

# The standard library parser, deliberately: the input is Surefire's own output
# from the build that just ran on this machine, not anything a stranger supplied,
# and a gate that needs pip install to run is a gate nobody runs locally.
# ElementTree does not resolve external entities.
import xml.etree.ElementTree as ElementTree

# Class name -> why a skip there is expected. Anything not listed fails the run.
EXEMPT = {}

REPORT_DIRECTORIES = ("surefire-reports", "failsafe-reports")


def reports(root):
    """Every TEST-*.xml Surefire or Failsafe wrote under root."""
    found = []
    for directory, subdirectories, files in os.walk(root):
        if os.path.basename(directory) not in REPORT_DIRECTORIES:
            continue
        if os.path.basename(os.path.dirname(directory)) != "target":
            continue
        subdirectories[:] = []
        for name in sorted(files):
            if name.startswith("TEST-") and name.endswith(".xml"):
                found.append(os.path.join(directory, name))
    return found


def read(path):
    """(tests run, [(class, test, reason)]) for one report, or None if unreadable."""
    try:
        suite = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        # A half-written report means the JVM died mid-suite. That is a failure
        # whatever it turns out to be, and guessing here would hide it.
        print("skips: cannot read {}: {}".format(path, error), file=sys.stderr)
        return None

    cases = []
    for case in suite.iter("testcase"):
        for skip in case.findall("skipped"):
            reason = skip.get("message") or (skip.text or "").strip() or "no reason given"
            cases.append((case.get("classname") or "?", case.get("name") or "?", reason))
    return int(suite.get("tests") or 0), cases


def main(argv):
    positional = [argument for argument in argv if not argument.startswith("--")]
    root = positional[0] if positional else "."

    paths = reports(root)
    if not paths:
        # Nothing ran. Reported as a failure because the alternative is a gate
        # that passes loudest when the tests were skipped wholesale, which is the
        # same mistake one level up.
        print("skips: no test reports under {}; nothing ran".format(root), file=sys.stderr)
        return 1

    unreadable = False
    total = 0
    skips = []

    for path in paths:
        report = read(path)
        if report is None:
            unreadable = True
            continue
        tests, cases = report
        total += tests
        skips.extend(cases)

    if unreadable:
        return 1

    unexpected = [case for case in skips if case[0] not in EXEMPT]
    exempted = len(skips) - len(unexpected)

    print("skips: {} tests across {} report files".format(total, len(paths)))
    for name, reason in sorted(EXEMPT.items()):
        print("  exempt: {} -- {}".format(name, reason))
    if exempted:
        print("  {} skipped case(s) matched an exemption".format(exempted))

    if unexpected:
        print("", file=sys.stderr)
        print("::error::a test skipped; a skip reads as success and hides lost coverage",
              file=sys.stderr)
        for classname, test, reason in unexpected:
            print("  {}#{}: {}".format(classname, test, reason), file=sys.stderr)
        print("", file=sys.stderr)
        print("Either make it run, or add the class to EXEMPT in etc/check-nothing-skipped.py",
              file=sys.stderr)
        print("with the reason it cannot.", file=sys.stderr)
        return 1

    print("nothing skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
