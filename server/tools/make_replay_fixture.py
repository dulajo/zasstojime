#!/usr/bin/env python3
"""
Sestaví fixture pro přehrávací test (AC-05) ze záznamu snímků Golemia a PID GTFS.

  tools/make_replay_fixture.py <adresář se snap_YYYYMMDDTHHMMSSZ.json.gz> <rozbalené PID GTFS> <cíl>

Výstup v <cíl>:
  snapshots.tsv.xz  – na řádek jeden snímek: čas stažení (ISO, UTC) TAB JSON
  gtfs.zip          – výřez PID GTFS: všechny linky, jen zastávky a spoje ze záznamu
"""
import csv
import glob
import gzip
import io
import json
import lzma
import os
import sys
import zipfile
from datetime import datetime, timezone


def main(rec_dir, gtfs_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    files = sorted(glob.glob(os.path.join(rec_dir, "snap_*.json.gz")))
    trip_ids, stop_ids = set(), set()
    with lzma.open(os.path.join(out_dir, "snapshots.tsv.xz"), "wt", encoding="utf-8", preset=9 | lzma.PRESET_EXTREME) as out:
        for path in files:
            stamp = os.path.basename(path)[len("snap_"):-len(".json.gz")]
            fetched = datetime.strptime(stamp, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
            with gzip.open(path, "rt", encoding="utf-8") as f:
                data = json.load(f)
            for feat in data["features"]:
                props = feat.get("properties") or {}
                gtfs = (props.get("trip") or {}).get("gtfs") or {}
                if gtfs.get("trip_id"):
                    trip_ids.add(gtfs["trip_id"])
                stop = (props.get("last_position") or {}).get("last_stop") or {}
                if stop.get("id"):
                    stop_ids.add(stop["id"])
            out.write(fetched.strftime("%Y-%m-%dT%H:%M:%SZ") + "\t" + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n")

    def filtered(name, keep):
        with open(os.path.join(gtfs_dir, name), encoding="utf-8-sig", newline="") as f:
            reader = csv.reader(f)
            header = next(reader)
            buf = io.StringIO()
            w = csv.writer(buf, lineterminator="\n")
            w.writerow(header)
            idx = {h: i for i, h in enumerate(header)}
            for row in reader:
                if keep(row, idx):
                    w.writerow(row)
            return buf.getvalue()

    first_seen = set()

    def first_stop_time(row, idx):
        t = row[idx["trip_id"]]
        if t in trip_ids and t not in first_seen and row[idx["stop_sequence"]] == "1":
            first_seen.add(t)
            return True
        return False

    with zipfile.ZipFile(os.path.join(out_dir, "gtfs.zip"), "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr("routes.txt", filtered("routes.txt", lambda r, i: True))
        z.writestr("stops.txt", filtered("stops.txt", lambda r, i: r[i["stop_id"]] in stop_ids))
        z.writestr("trips.txt", filtered("trips.txt", lambda r, i: r[i["trip_id"]] in trip_ids))
        z.writestr("stop_times.txt", filtered("stop_times.txt", first_stop_time))
    print(f"{len(files)} snímků, {len(trip_ids)} spojů, {len(stop_ids)} zastávek")


if __name__ == "__main__":
    main(*sys.argv[1:4])
