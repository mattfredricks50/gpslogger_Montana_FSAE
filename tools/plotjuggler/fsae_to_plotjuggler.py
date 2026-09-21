"""Prepare FSAE logger files for PlotJuggler.

PlotJuggler's CSV loader only opens *.csv, and the phone writes one set of files per 7-minute
chunk (<base>.gps/.acc/.gyr/.ecu/.meta, optionally zipped). This script:

  * reads folders, loose files, chunk .zip files and Google Drive download .zip files (nested zips too)
  * groups chunks into logging sessions by session_start_ns from .meta (same Start press)
  * joins each stream's chunks in order into one <session>_<stream>.csv
  * prints a quality summary from .meta (sample rates, dropped samples, calibration stillness)

Usage:
    python fsae_to_plotjuggler.py <files, folders or zips...> [-o OUTPUT_DIR]

Then in PlotJuggler: File -> Load Data, pick the *_gps.csv / *_acc.csv / *_gyr.csv / *_ecu.csv
of one session, and choose time_s as the time column. They share a clock, so they line up.
"""

import argparse
import io
import json
import os
import re
import sys
import zipfile
from collections import defaultdict

STREAMS = ("gps", "acc", "gyr", "ecu")
CHUNK_FILE = re.compile(r"^(?P<base>.+)\.(?P<ext>gps|acc|gyr|ecu|meta)$")


def iter_inputs(paths):
    """Yields (name, bytes) for every chunk file found in the given paths."""
    for path in paths:
        if os.path.isdir(path):
            for root, _, files in os.walk(path):
                for f in sorted(files):
                    yield from iter_file(os.path.join(root, f))
        else:
            yield from iter_file(path)


def iter_file(path):
    with open(path, "rb") as fh:
        yield from iter_blob(os.path.basename(path), fh.read())


def iter_blob(name, data):
    if name.lower().endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            for entry in zf.infolist():
                if not entry.is_dir():
                    yield from iter_blob(os.path.basename(entry.filename), zf.read(entry))
    elif CHUNK_FILE.match(name):
        yield name, data


def collect_chunks(paths):
    """Returns {base: {ext: bytes}}. Duplicates (same file downloaded twice) keep the larger copy."""
    chunks = defaultdict(dict)
    for name, data in iter_inputs(paths):
        m = CHUNK_FILE.match(name)
        base, ext = m.group("base"), m.group("ext")
        if len(data) > len(chunks[base].get(ext, b"")):
            chunks[base][ext] = data
    return chunks


def load_meta(files):
    if "meta" not in files:
        return None
    try:
        return json.loads(files["meta"].decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as e:
        print(f"  warning: unreadable .meta ({e})")
        return None


def group_sessions(chunks):
    """Groups chunk base names by session_start_ns. Chunks without .meta form their own session."""
    sessions = defaultdict(list)
    for base in sorted(chunks):  # base names are yyyyMMddHHmmss, so this is time order
        meta = load_meta(chunks[base])
        key = meta.get("session_start_ns") if meta else None
        sessions[key if key is not None else f"no-meta:{base}"].append(base)
    return sessions


def join_stream(chunk_texts):
    """Joins chunk CSVs under one header, dropping rows whose time_s doesn't increase.

    Returns (header, rows, dropped). A header change between chunks raises ValueError.
    """
    header, rows, dropped, last_t = None, [], 0, float("-inf")
    for text in chunk_texts:
        lines = text.splitlines()
        if not lines:
            continue
        if header is None:
            header = lines[0]
        elif lines[0] != header:
            raise ValueError(f"header changed between chunks:\n  {header}\n  {lines[0]}")
        for line in lines[1:]:
            if not line:
                continue
            try:
                t = float(line.split(",", 1)[0])
            except ValueError:
                dropped += 1
                continue
            if t <= last_t:
                dropped += 1
                continue
            last_t = t
            rows.append(line)
    return header, rows, dropped


def summarize_meta(metas):
    """Prints the checks worth doing before trusting a session's data."""
    if not metas:
        print("  no .meta files: can't check rates or calibration")
        return
    first, last = metas[0], metas[-1]
    rates = last.get("rates", {})
    req = rates.get("requested_hz")
    acc_hz, gyr_hz = rates.get("acc_actual_hz"), rates.get("gyr_actual_hz")
    print(f"  IMU rate: requested {req} Hz, got acc {acc_hz} Hz / gyr {gyr_hz} Hz"
          + ("  <-- lower than requested" if req and acc_hz and acc_hz < 0.9 * req else ""))

    drops = defaultdict(int)
    for m in metas:
        chunk = m.get("chunk", {})
        for s in ("gps", "acc", "gyr", "ecu"):
            drops[s] += chunk.get(f"{s}_dropped_backwards", 0) or 0
    if any(drops.values()):
        print("  dropped (time went backwards): " + ", ".join(f"{s} {n}" for s, n in drops.items() if n))

    cal = first.get("calibration")
    if cal:
        acc_std = cal.get("accel_std_mps2") or []
        gyr_std = cal.get("gyro_std_rads") or []
        # A parked car with the engine off is well under these; engine idle vibration can approach them
        still = all(v < 0.3 for v in acc_std) and all(v < 0.05 for v in gyr_std)
        print(f"  calibration: gravity {fmt(cal.get('gravity_mps2'))} m/s^2, accel std {fmt(acc_std)}, "
              f"gyro std {fmt(gyr_std)}" + ("" if still else "  <-- car probably moving during calibration"))

    ecu = last.get("ecu")
    if isinstance(ecu, dict):
        totals = defaultdict(int)
        for m in metas:
            chunk = m.get("chunk", {})
            for k in ("ecu_connects", "ecu_parse_errors", "ecu_nodata_events"):
                totals[k] += chunk.get(k, 0) or 0
        print(f"  ECU: host {ecu.get('connected_host', 'never connected')}, "
              f"{totals['ecu_connects']} connect(s), {totals['ecu_parse_errors']} parse error(s), "
              f"{totals['ecu_nodata_events']} no-data event(s)"
              + (f", last error: {ecu['last_error']}" if ecu.get("last_error") else ""))


def fmt(values):
    if not values:
        return "-"
    return "[" + ", ".join(f"{v:.3f}" for v in values) + "]"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("inputs", nargs="+", help="chunk files, folders, or .zip downloads")
    ap.add_argument("-o", "--output", default="plotjuggler", help="output folder (default: ./plotjuggler)")
    args = ap.parse_args()

    chunks = collect_chunks(args.inputs)
    if not chunks:
        sys.exit("No logger files (.gps/.acc/.gyr/.ecu/.meta) found in the inputs.")
    os.makedirs(args.output, exist_ok=True)

    for key, bases in group_sessions(chunks).items():
        session_name = bases[0]
        print(f"\nSession {session_name}: {len(bases)} chunk(s) ({bases[0]} .. {bases[-1]})")
        metas = [m for m in (load_meta(chunks[b]) for b in bases) if m]
        summarize_meta(metas)

        for stream in STREAMS:
            texts = [chunks[b][stream].decode("utf-8") for b in bases if stream in chunks[b]]
            if not texts:
                continue
            try:
                header, rows, dropped = join_stream(texts)
            except ValueError as e:
                print(f"  {stream}: skipped, {e}")
                continue
            if not rows:
                print(f"  {stream}: no data rows")
                continue
            out = os.path.join(args.output, f"{session_name}_{stream}.csv")
            with open(out, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(header + "\n")
                fh.write("\n".join(rows) + "\n")
            t0, t1 = float(rows[0].split(",", 1)[0]), float(rows[-1].split(",", 1)[0])
            print(f"  {stream}: {len(rows)} rows, t = {t0:.1f} .. {t1:.1f} s -> {out}"
                  + (f" ({dropped} overlapping/bad rows dropped)" if dropped else ""))

    print(f"\nOpen the CSVs in {os.path.abspath(args.output)} with PlotJuggler (time column: time_s).")


if __name__ == "__main__":
    main()
