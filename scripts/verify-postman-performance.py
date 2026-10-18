#!/usr/bin/env python3
import json
import sys

summary = None
for raw in sys.stdin:
    print(raw, end="", flush=True)
    try:
        event = json.loads(raw)
    except json.JSONDecodeError:
        continue
    if event.get("event") == "summary":
        summary = event

if summary is None:
    raise SystemExit("Postman performance output did not contain a summary")
failures = summary.get("metrics", {}).get("testFailures", {})
if failures.get("requestsWithFailedAssertions", 0) != 0:
    raise SystemExit(f"Postman assertions failed for {failures['requestsWithFailedAssertions']} requests")
