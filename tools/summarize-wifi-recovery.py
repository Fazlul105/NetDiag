"""Summarize a NetDiag recovery CSV using only the phone's monotonic clock."""
import argparse
import csv
import statistics


def summarize(path):
    with open(path, encoding="utf-8-sig", newline="") as source:
        events = list(csv.DictReader(source))
    times = [int(event["elapsed_realtime_ns"]) for event in events]
    if any(later <= earlier for earlier, later in zip(times, times[1:])):
        raise ValueError("Event timestamps must increase strictly")
    recoveries = []
    print("| Trial | TCP attempts | Observed TCP recovery (ms) | Internet validated (ms) |")
    print("| --- | --- | --- | --- |")
    for index, event in enumerate(events):
        if event["event"] != "RECOVERY_START":
            continue
        trial = event["detail"].split("=", 1)[1]
        following = []
        for candidate in events[index + 1:]:
            if candidate["event"] in ("LOST", "SESSION_STOP"):
                break
            following.append(candidate)
        results = [candidate for candidate in following if candidate["event"] == "PROBE_RESULT"]
        success = next((candidate for candidate in results if ";status=OK;" in candidate["detail"]), None)
        validated = next((candidate for candidate in following if candidate["event"] == "CAPABILITIES"
            and candidate["network"] == event["network"]
            and candidate["detail"] == "Wi-Fi | Internet validated"), None)
        start = int(event["elapsed_realtime_ns"])
        recovery_ms = (int(success["elapsed_realtime_ns"]) - start) / 1_000_000 if success else None
        validation_ms = (int(validated["elapsed_realtime_ns"]) - start) / 1_000_000 if validated else None
        observed = f"{recovery_ms:.3f}" if recovery_ms is not None else "No observed success"
        validation = f"{validation_ms:.3f}" if validation_ms is not None else "Not observed"
        print(f"| {trial} | {len(results)} | {observed} | {validation} |")
        if recovery_ms is not None:
            recoveries.append(recovery_ms)
    if recoveries:
        print(f"\nMedian {statistics.median(recoveries):.3f} ms, "
            f"range {min(recoveries):.3f} to {max(recoveries):.3f} ms.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_path")
    summarize(parser.parse_args().csv_path)
