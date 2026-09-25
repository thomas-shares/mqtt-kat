#!/usr/bin/env python3
"""Run the Paho MQTT 5 interoperability suite against a broker on
localhost:1883 and decide pass or fail from its per-test results.

    python3 scripts/paho_v5.py path/to/paho.mqtt.testing/interoperability

The suite is eclipse/paho.mqtt.testing's client_test5.py, run unchanged. It is
invoked with no arguments of its own: its -h and -p never leave sys.argv, and
unittest then reads -h as --help (see thoughts.md). The defaults are already
localhost:1883.

A failure in KNOWN is reported but does not fail the run; anything else that
fails, errors or never runs does. Remove an entry once its cause is fixed, so
a regression in it shows up again.
"""

import os
import runpy
import sys
import unittest

KNOWN = {
    "test_subscribe_failure":
        "by decision: needs a deny policy for test/nosubscribe (thoughts.md, 20260909)",
    "test_subscribe_options":
        "flaky, the suite's race: waits on callback.subscribeds after subscribing bclient",
    "test_request_response":
        "flaky, the same race in the suite",
    "test_unsubscribe":
        "flaky in the full run, passes alone; cause not pinned down yet",
}

results = {}


class Result(unittest.TextTestResult):
    def _record(self, test, outcome):
        results[getattr(test, "_testMethodName", str(test))] = outcome

    def addSuccess(self, test):
        super().addSuccess(test)
        self._record(test, "ok")

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self._record(test, "fail")

    def addError(self, test, err):
        super().addError(test, err)
        self._record(test, "error")

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self._record(test, "skipped")


def main(suite_dir):
    os.chdir(suite_dir)
    sys.path.insert(0, suite_dir)
    sys.argv = ["client_test5.py"]

    expected = []
    run_main = unittest.main

    def main_with_our_result(*args, **kwargs):
        module = sys.modules["__main__"]
        expected.extend(unittest.defaultTestLoader.getTestCaseNames(module.Test))
        kwargs.update(exit=False,
                      testRunner=unittest.TextTestRunner(resultclass=Result, verbosity=2))
        run_main(*args, **kwargs)

    unittest.main = main_with_our_result
    runpy.run_path("client_test5.py", run_name="__main__")

    unexpected = []
    lines = ["| test | result | |", "|---|---|---|"]
    for name in sorted(set(expected) | set(results)):
        outcome = results.get(name, "not run")
        note = ""
        if outcome != "ok":
            if name in KNOWN:
                note = "known: " + KNOWN[name]
            else:
                unexpected.append(name)
                note = "**unexpected**"
        lines.append(f"| {name} | {outcome} | {note} |")

    passed = sum(1 for o in results.values() if o == "ok")
    header = f"### Paho MQTT 5 suite: {passed} of {len(expected)} passed"
    report = "\n".join([header, ""] + lines) + "\n"
    print("\n" + report)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(report)

    if unexpected:
        print("Unexpected failures: " + ", ".join(unexpected))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(os.path.abspath(sys.argv[1])))
