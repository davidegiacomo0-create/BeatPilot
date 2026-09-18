#!/usr/bin/env python3
"""Extract scheduling evidence, not game results, from a BeatPilot recording report."""
import csv
import hashlib
import json
import pathlib
import sys

source, target = map(pathlib.Path, sys.argv[1:])
report = json.loads(source.read_text())
target.mkdir(parents=True, exist_ok=True)
zero = report['video_zero_uptime_ms']
hits, batches, segments, callbacks = [], [], [], {}
for ready, kind, data in report['events']:
    if kind in ('hit_predicted', 'hit_refined'):
        f = dict(part.split('=', 1) for part in data.split(';'))
        hits.append([ready, f['note_id'], int(f['lane']) - 1, f['kind'],
                     int(f['due_uptime_ms']) - zero, kind == 'hit_refined', int(f['note_id']) < 0])
    elif kind == 'gesture_request':
        header, body = data.split(';segments=', 1)
        f = dict(part.split('=', 1) for part in header.split(';'))
        assert f['accepted'] == 'true'
        batch = int(f['batch'])
        batches.append([batch, ready, int(f['plan_uptime_ms']) - zero])
        for entry in body.split('[', 1)[1].split(']', 1)[0].split('|'):
            if entry:
                segments.append([batch, *entry.split(':')])
    elif kind == 'gesture_completed':
        callbacks[int(data.split('=')[1])] = ready

def write(name, header, rows):
    with (target / name).open('w', newline='') as output:
        writer = csv.writer(output)
        writer.writerow(header)
        writer.writerows(rows)

write('hits.csv', ['ready_ms', 'note_id', 'lane', 'kind', 'due_ms', 'revision', 'opening'], hits)
write('batches.csv', ['batch', 'request_ms', 'plan_ms', 'callback_ms'],
      [row + [callbacks[row[0]]] for row in batches])
# Schema 1 omitted willContinue. Infer it only when the same note ID appears
# as a continuation in the immediately following recorded gesture.
continued = {(row[0], row[1]) for row in segments if row[-1] == 'true'}
write('segments.csv', ['batch', 'note_id', 'x0', 'y0', 'x1', 'y1', 'offset_ms',
                       'duration_ms', 'continued', 'more_inferred'],
      [row + [(row[0] + 1, row[1]) in continued] for row in segments])
(target / 'source.json').write_text(json.dumps({
    'report': source.name, 'sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'app_version': report['app_version'], 'device': report['device'],
    'android_sdk': report['android_sdk'], 'hit_line': report['hit_line'],
    'lanes': report['lanes'], 'hits_and_revisions': len(hits), 'batches': len(batches),
    'note': 'Relative monotonic milliseconds; request/callback are not measured game input. '
            'Opening is inferred from negative note IDs; more from the next continuation.'
}, indent=2) + '\n')
print(f'Extracted {len(hits)} predictions/revisions and {len(batches)} gesture batches.')
