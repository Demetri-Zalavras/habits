"""Aggregate a JFR file into com.habits hot-method tables (docs/PERF.md).

usage: python tools/perf/jfr_hot.py <file.jfr> [thread-substring] [top]
For execution samples on the matching thread (default "Server thread"):
  - share of samples with any com.habits frame on the stack (the mod's inclusive cost)
  - top com.habits methods by inclusive samples (method anywhere on the stack)
  - top com.habits methods by self samples (innermost com.habits frame: where the mod's own time goes)
and for allocation samples (jdk.ObjectAllocationSample, weight = bytes) the bytes per innermost com.habits frame.
Streams `jfr print` text output (the JSON form is far too slow for multi-MB recordings).
"""
import collections
import re
import subprocess
import sys

JFR = r"C:\Program Files\Java\jdk-21\bin\jfr.exe"
FRAME = re.compile(r"^\s+([\w$.]+)\(")
THREAD = re.compile(r'(?:sampledThread|eventThread) = "([^"]*)"')
WEIGHT = re.compile(r"weight = ([\d.,]+) ?([kMG]?B)?")
UNITS = {"B": 1, "kB": 1024, "MB": 1024 ** 2, "GB": 1024 ** 3, None: 1}


def events(path, kind):
    """Yield (thread, frames innermost-first, weight bytes) per event."""
    p = subprocess.Popen([JFR, "print", "--stack-depth", "64", "--events", kind, path],
                         stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    thread, frames, weight = "", [], 0
    for line in p.stdout:
        if line.startswith(kind) or line.startswith("jdk."):
            if frames or thread:
                yield thread, frames, weight
            thread, frames, weight = "", [], 0
            continue
        m = THREAD.search(line)
        if m:
            thread = m.group(1)
            continue
        m = WEIGHT.search(line)
        if m:
            weight = float(m.group(1).replace(",", "")) * UNITS.get(m.group(2), 1)
            continue
        m = FRAME.match(line)
        if m:
            frames.append(m.group(1))
    if frames or thread:
        yield thread, frames, weight


def mod_frames(fs):
    return [f for f in fs if f.startswith("com.habits.") and ".perf." not in f]


def main():
    path = sys.argv[1]
    want = sys.argv[2] if len(sys.argv) > 2 else "Server thread"
    top = int(sys.argv[3]) if len(sys.argv) > 3 else 20
    incl, selfc = collections.Counter(), collections.Counter()
    n = mod = 0
    for thread, fs, _ in events(path, "jdk.ExecutionSample"):
        if want not in thread:
            continue
        n += 1
        hab = mod_frames(fs)
        if not hab:
            continue
        mod += 1
        for f in set(hab):
            incl[f] += 1
        selfc[hab[0]] += 1
    n = max(1, n)
    print(f"{path}: {n} samples on '{want}', {mod} ({100 * mod / n:.1f}%) with a com.habits frame")
    print(f"\ntop {top} com.habits by inclusive samples:")
    for f, c in incl.most_common(top):
        print(f"  {100 * c / n:5.1f}%  {c:6d}  {f}")
    print(f"\ntop {top} com.habits by self samples (innermost com.habits frame):")
    for f, c in selfc.most_common(top):
        print(f"  {100 * c / n:5.1f}%  {c:6d}  {f}")

    by, total, modb, dropped = collections.Counter(), 0, 0, 0
    for thread, fs, w in events(path, "jdk.ObjectAllocationSample"):
        if want not in thread:
            continue
        if w > 1024 ** 3:  # one sample carrying GBs: the first sample after recording start, not a real site
            dropped += 1
            continue
        total += w
        hab = mod_frames(fs)
        if hab:
            modb += w
            by[hab[0]] += w
    print(f"\nallocation samples: {total / 1048576:.1f} MB weighted, {modb / 1048576:.1f} MB ({100 * modb / max(1, total):.1f}%) under com.habits"
          + (f" ({dropped} outlier sample(s) over 1 GB dropped)" if dropped else ""))
    for f, w in by.most_common(top):
        print(f"  {w / 1048576:8.1f} MB  {f}")


if __name__ == "__main__":
    main()
