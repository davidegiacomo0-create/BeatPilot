#!/usr/bin/env python3
"""Run the real Java detector/tracker on an MP4, with its original presentation timestamps."""
import argparse
import json
import pathlib
import struct
import subprocess
import tempfile
import numpy as np

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('video', type=pathlib.Path)
parser.add_argument('output', type=pathlib.Path)
parser.add_argument('--fixtures', action='store_true')
parser.add_argument('--timing-report', type=pathlib.Path, help='Use capture/analysis times from a BeatPilot JSON report')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
probe = subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
                                '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(args.video)])
times = [float(f['best_effort_timestamp_time']) for f in json.loads(probe)['frames']]
timing = {}
if args.timing_report:
    report = json.loads(args.timing_report.read_text())
    zero = report['video_zero_uptime_ms']
    vision = {t: dict(part.split('=', 1) for part in data.split(';'))
              for t, kind, data in report['events'] if kind == 'vision'}
    for t, kind, data in report['events']:
        if kind != 'video_frame':
            continue
        fields = dict(part.split('=', 1) for part in data.split(';'))
        source = round(int(fields['source_image_ns']) / 1e6 - zero)
        # Only use the source clock when this recording shows that it agrees with uptime.
        captured = min(t, source) if -2 <= t - source <= 200 else t
        observed = vision.get(t, {})
        if 'capture_uptime_ms' in observed:
            captured = int(observed['capture_uptime_ms']) - zero
        timing[round(int(fields['pts_us']) / 1000)] = (captured, t + int(observed.get('analysis_ms', 0)))
fixture_times = [0, 6.5, 12, 16.412, 23.75, 28.95, 32.45, 36, 37.95, 42.75, 49.45, 60.25, 66.85, 70.5, 72.25]
indices = {min(range(len(times)), key=lambda i: abs(times[i] - t)): t for t in fixture_times}
w, h = 480, 1040
with tempfile.TemporaryDirectory(prefix='beatpilot-replay-') as classes:
    sources = sorted((root / 'app/src/main/java/it/dave/beatpilot/core').glob('*.java'))
    subprocess.run(['java', 'com.sun.tools.javac.Main', '--release', '17', '-d', classes,
                    *map(str, sources), str(root / 'tests/VideoReplay.java')], check=True)
    engine = subprocess.Popen(['java', '-cp', classes, 'VideoReplay', str(args.output)], stdin=subprocess.PIPE)
    decoder = subprocess.Popen(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', str(args.video),
                                '-an', '-vf', f'scale={w}:{h}', '-fps_mode', 'passthrough',
                                '-pix_fmt', 'rgb24', '-enc_time_base', '1:90000', '-f', 'rawvideo', 'pipe:1'], stdout=subprocess.PIPE)
    engine.stdin.write(struct.pack('>ii', w, h))
    count = 0
    try:
        while True:
            data = decoder.stdout.read(w * h * 3)
            if not data:
                break
            if len(data) != w * h * 3 or count >= len(times):
                raise RuntimeError('Video frame/timestamp mismatch')
            rgb = np.frombuffer(data, dtype=np.uint8).reshape(h, w, 3)
            gray = ((rgb[:, :, 0].astype(np.uint16) * 77 + rgb[:, :, 1].astype(np.uint16) * 150
                     + rgb[:, :, 2].astype(np.uint16) * 29) >> 8).astype(np.uint8)
            video_time = round(times[count] * 1000)
            captured, ready = timing.get(video_time, (video_time, video_time))
            engine.stdin.write(struct.pack('>qqq', video_time, captured, ready))
            engine.stdin.write(gray.tobytes())
            if args.fixtures and count in indices:
                path = root / 'tests/fixtures'; path.mkdir(exist_ok=True)
                name = f'{round(indices[count] * 1000):06d}'
                (path / (name + '.gray')).write_bytes(gray.tobytes())
                from PIL import Image
                Image.fromarray(rgb).save(args.output / (name + '.png'))
            count += 1
    finally:
        engine.stdin.close()
        decoder.stdout.close()
    if decoder.wait() != 0 or engine.wait() != 0:
        raise SystemExit('Replay process failed')
    if count != len(times):
        raise SystemExit(f'Decoded {count} frames but found {len(times)} timestamps')
    (args.output / 'metadata.json').write_text(json.dumps({'frames': count, 'width': w, 'height': h,
        'first_timestamp': times[0], 'last_timestamp': times[-1], 'source': args.video.name,
        'timing_report': args.timing_report.name if args.timing_report else None,
        'frames_with_recorded_timing': len(timing)}, indent=2))
    print(f'Original timestamps verified for all {count} frames.', flush=True)
