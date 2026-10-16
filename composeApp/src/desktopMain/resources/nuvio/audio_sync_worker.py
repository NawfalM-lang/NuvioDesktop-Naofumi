#!/usr/bin/env python3
"""Bounded, validated subtitle-to-subtitle alignment for Nuvio Windows.

Arabic (or other selected language) aligns against an English subtitle reference.
Reference: embedded English text from the active video, or explicit failure.
External English subtitles are not trusted as evidence of video timing.
Remote embedded references sample the beginning, middle and end. Corrections
must pass independent regional timing checks. No audio analysis or AI model.

Run: py -3 fast_sync_worker.py job.json
The job JSON contains sensitive signed URLs and must be deleted by the host.
"""
import json
import math
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

MAX_SUB_BYTES = 16 * 1024 * 1024
PROBE_TIMEOUT_S = 8
EXTRACT_TIMEOUT_S = 30
MEDIA_READ_TIMEOUT_S = 6
FETCH_TIMEOUT_S = 12
MAX_CUES = 100_000
MAX_TIMELINE_S = 12 * 60 * 60


class SyncError(RuntimeError):
    """Only these fixed messages may be shown by the host; never include URLs/text."""


def fetch_subtitle(url, workspace, stem):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ('http', 'https'):
        raise SyncError('Subtitle URL must use HTTP(S)')
    request = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 (NuvioDesktop Fast Sync)',
        'Accept': 'text/plain, application/x-subrip, text/vtt, */*',
    })
    try:
        with urllib.request.urlopen(request, timeout=FETCH_TIMEOUT_S) as response:
            size = response.headers.get('Content-Length', '')
            if size.isdigit() and int(size) > MAX_SUB_BYTES:
                raise SyncError('Subtitle response is too large')
            raw = response.read(MAX_SUB_BYTES + 1)
    except urllib.error.HTTPError as error:
        raise SyncError('Subtitle download failed (HTTP %d)' % error.code) from None
    except (OSError, TimeoutError):
        raise SyncError('Subtitle download failed; check your connection or choose another subtitle') from None
    if len(raw) > MAX_SUB_BYTES:
        raise SyncError('Subtitle response is too large')
    if raw.startswith((b'\xff\xfe', b'\xfe\xff')):
        try:
            raw = raw.decode('utf-16').encode('utf-8')
        except UnicodeError:
            raise SyncError('Subtitle text encoding is invalid') from None
    head = raw[:4096].lstrip(b'\xef\xbb\xbf \r\n\t')
    if head.startswith(b'WEBVTT'):
        ext = '.vtt'
    elif b'[Script Info]' in head or b'[V4+ Styles]' in head:
        ext = '.ass'
    elif b'-->' in head or re.search(rb'\b\d{2}:\d{2}:\d{2}[,.]\d{3}', head):
        ext = '.srt'
    else:
        raise SyncError('Subtitle response is not a supported text format')
    out = workspace / (stem + ext)
    out.write_bytes(raw)
    return out


def binary_pair():
    """Find both FFmpeg programs only when video access or conversion needs them."""
    paths = []
    # PyInstaller's one-directory bundle keeps FFmpeg beside its private runtime.
    # Prefer it to anything installed on the user's PC.
    if getattr(sys, 'frozen', False):
        paths.append(pathlib.Path(sys._MEIPASS) / 'bin')
    override = os.environ.get('NUVIO_FFMPEG_BIN', '').strip().strip('"')
    if override:
        p = pathlib.Path(override)
        paths.append(p.parent if p.is_file() else p)
    for prog in ('ffmpeg', 'ffprobe'):
        exe = shutil.which(prog)
        if exe:
            paths.append(pathlib.Path(exe).parent)
    local = os.environ.get('LOCALAPPDATA', '')
    program = os.environ.get('ProgramFiles', '')
    if local:
        base = pathlib.Path(local)
        paths.extend((base / 'Microsoft/WinGet/Links', base / 'Programs/ffmpeg/bin'))
    if program:
        paths.append(pathlib.Path(program) / 'ffmpeg/bin')
    paths += [pathlib.Path.home() / 'scoop/apps/ffmpeg/current/bin',
              pathlib.Path('C:/ffmpeg/bin'), pathlib.Path('C:/ffmpeg')]
    roots = [pathlib.Path(local) / 'Microsoft/WinGet/Packages'] if local else []
    roots += [pathlib.Path(program) / 'WinGet/Packages'] if program else []
    for root in roots:
        if root.is_dir():
            try:
                for pkg in root.iterdir():
                    if pkg.is_dir() and 'ffmpeg' in pkg.name.lower():
                        paths.extend(p.parent for p in pkg.rglob('ffmpeg.exe'))
            except OSError:
                pass
    checked = set()
    for directory in paths:
        if str(directory).lower() in checked:
            continue
        checked.add(str(directory).lower())
        for ext in ('.exe', ''):
            ffmpeg = directory / ('ffmpeg' + ext)
            ffprobe = directory / ('ffprobe' + ext)
            if ffmpeg.is_file() and ffprobe.is_file():
                return ffmpeg, ffprobe
    raise SyncError('Cannot locate FFmpeg and FFprobe; required only for embedded subtitle extraction or subtitle conversion')


def to_srt(source, dest, ffmpeg=None):
    if source.suffix.lower() == '.srt':
        return source  # ffsubsync can decode original SRT directly
    if ffmpeg is None:
        ffmpeg, _ = binary_pair()
    try:
        result = subprocess.run([
            str(ffmpeg), '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
            '-i', str(source), '-f', 'srt', str(dest),
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL, timeout=10, check=False)
    except subprocess.TimeoutExpired as e:
        raise SyncError('Subtitle conversion timed out') from e
    if result.returncode or not dest.is_file() or dest.stat().st_size < 20:
        raise SyncError('Could not convert subtitle file to SRT')
    return dest


def normalize_video(value):
    # urlsplit treats a Windows drive letter as a URI scheme (e.g. "c").
    if re.match(r'^[A-Za-z]:[\\/]', value) and pathlib.Path(value).is_file():
        return value
    p = urllib.parse.urlsplit(value)
    if p.scheme in ('http', 'https'):
        return value
    if p.scheme == 'file':
        path = urllib.request.url2pathname(urllib.parse.unquote(p.path))
        if p.netloc and p.netloc not in ('localhost', ''):
            path = '//'+p.netloc+path
        if pathlib.Path(path).is_file():
            return path
    if p.scheme == '' and pathlib.Path(value).is_file():
        return value
    raise SyncError('This stream is not accessible to the embedded subtitle extractor')


def read_cues(path):
    """Canonicalize text encoding once; alignment must never change dialogue."""
    import srt
    raw = path.read_bytes()
    if len(raw) > MAX_SUB_BYTES:
        raise SyncError('Subtitle response is too large')
    try:
        if raw.startswith((b'\xff\xfe', b'\xfe\xff')):
            text = raw.decode('utf-16')
        else:
            try:
                text = raw.decode('utf-8-sig')
            except UnicodeDecodeError:
                from charset_normalizer import from_bytes
                best = from_bytes(raw).best()
                if best is None:
                    raise ValueError('encoding')
                text = str(best)
        cues = list(srt.parse(text))
    except (ValueError, UnicodeError, srt.SRTParseError):
        raise SyncError('Subtitle text or encoding is invalid; choose another subtitle') from None
    if not cues or len(cues) > MAX_CUES:
        raise SyncError('Subtitle has too few or too many dialogue lines')
    for cue in cues:
        start, end = cue.start.total_seconds(), cue.end.total_seconds()
        if start < 0 or end <= start or end > MAX_TIMELINE_S or not cue.content.strip():
            raise SyncError('Subtitle contains invalid timestamps or empty dialogue')
    return sorted(cues, key=lambda cue: cue.start)


def write_cues(path, cues):
    import srt
    path.write_text(srt.compose(cues, reindex=False), encoding='utf-8')


def sample_windows(duration):
    if not math.isfinite(duration) or not 20 <= duration <= MAX_TIMELINE_S:
        raise SyncError('Video duration is unavailable; original subtitle kept')
    if duration <= 180:
        return [(0.0, duration)]
    return [(max(0.0, duration * fraction - 30), min(duration, duration * fraction + 30))
            for fraction in (0.1, 0.5, 0.9)]


def extract_reference_window(video, index, start, end, output, ffmpeg, timeline_origin=0.0):
    """Stop at the media clock, even if the next subtitle packet is much later.

    A subtitle-only FFmpeg output may keep reading past -to while waiting for a
    sparse subtitle stream. Demux every stream to observe the clock, but decode
    only the chosen text subtitle; video/audio packets are never decoded.
    """
    import av
    import srt

    deadline = time.monotonic() + EXTRACT_TIMEOUT_S
    for attempt in range(2):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        cues = []
        try:
            with av.open(video, timeout=(min(8, remaining), min(MEDIA_READ_TIMEOUT_S, remaining))) as media:
                if start > 0:
                    media.seek(int((timeline_origin + start) * av.time_base), backward=True)
                # Do not select only subtitles: a quiet scene can otherwise cause
                # demux to read far past the requested window to find another cue.
                for packet in media.demux():
                    if time.monotonic() >= deadline:
                        raise TimeoutError()
                    stamp = packet.pts if packet.pts is not None else packet.dts
                    if stamp is None or packet.time_base is None:
                        continue
                    stamp = float(stamp * packet.time_base) - timeline_origin
                    if packet.stream.type in ('video', 'audio') and stamp >= end:
                        break
                    if packet.stream.index != index or not packet.size:
                        continue
                    if stamp >= end:
                        break
                    subtitle = packet.stream.decode2(packet)
                    if subtitle is None:
                        continue
                    cue_start = stamp + subtitle.start_display_time / 1000.0
                    cue_end = stamp + subtitle.end_display_time / 1000.0
                    if cue_end <= start or cue_end <= cue_start:
                        continue
                    text = '\n'.join(rect.dialogue.decode('utf-8', errors='replace')
                                     for rect in subtitle.rects if getattr(rect, 'dialogue', b''))
                    text = re.sub(r'\{[^}]*\}', '', text).replace(r'\N', '\n').replace(r'\h', ' ')
                    if text.strip():
                        cues.append(srt.Subtitle(len(cues) + 1, timedelta(seconds=max(0, cue_start)),
                                                 timedelta(seconds=cue_end), text))
                    if len(cues) > MAX_CUES:
                        raise SyncError('Embedded subtitle has too many dialogue lines')
            if not cues:
                raise SyncError('No embedded English dialogue in the sampled section; original subtitle kept')
            write_cues(output, cues)
            return read_cues(output)
        except (av.error.FFmpegError, OSError, TimeoutError):
            # Retry only a failed read, never a partial reference. All attempts
            # share the same deadline, with the host retaining its 90s limit.
            if attempt == 0 and deadline - time.monotonic() > 8:
                print('SYNC_STAGE: Retrying embedded English read', flush=True)
                continue
            break
    raise SyncError('Embedded English could not be read in time; try another stream or a downloaded copy; original subtitle kept')


def embedded_english_reference(video_url, workspace):
    ffmpeg, ffprobe = binary_pair()
    video = normalize_video(video_url)
    try:
        probe = subprocess.run([
            str(ffprobe), '-v', 'error', '-select_streams', 's',
            '-show_entries', 'stream=index,codec_name:stream_tags=language,title:'
                             'stream_disposition=forced,hearing_impaired:format=duration,start_time',
            '-of', 'json', video,
        ], capture_output=True, text=True, encoding='utf-8', errors='replace',
            timeout=PROBE_TIMEOUT_S, check=False)
    except subprocess.TimeoutExpired:
        raise SyncError('Embedded subtitle lookup timed out; original subtitle kept') from None
    if probe.returncode:
        raise SyncError('Cannot read embedded subtitles; original subtitle kept')
    try:
        metadata = json.loads(probe.stdout)
        streams = metadata.get('streams', [])
        duration = float(metadata.get('format', {}).get('duration', 0))
        timeline_origin = float(metadata.get('format', {}).get('start_time', 0))
        if not math.isfinite(timeline_origin):
            raise ValueError('start time')
    except (ValueError, TypeError):
        raise SyncError('Video subtitle metadata was invalid') from None
    english = [stream for stream in streams
               if stream.get('codec_name') in ('subrip', 'webvtt', 'ass', 'ssa', 'mov_text')
               and str(stream.get('tags', {}).get('language', '')).lower().split('-')[0] in ('en', 'eng')
               and not stream.get('disposition', {}).get('forced')
               and not re.search(r'\b(forced|signs(?: only)?)\b',
                                 str(stream.get('tags', {}).get('title', '')), re.I)]
    if not english:
        raise SyncError('No complete embedded English text track found; original subtitle kept')
    english.sort(key=lambda stream: bool(stream.get('disposition', {}).get('hearing_impaired')))
    index = int(english[0]['index'])
    windows = sample_windows(duration)
    # Only subtitle packets in these bounded windows are read; never scan remote media end-to-end.
    with ThreadPoolExecutor(max_workers=len(windows)) as pool:
        futures = [pool.submit(extract_reference_window, video, index, start, end,
                               workspace / ('reference-part-%d.srt' % i), ffmpeg, timeline_origin)
                   for i, (start, end) in enumerate(windows)]
        cues = [cue for future in futures for cue in future.result()]
    unique = {(cue.start, cue.end, cue.content): cue for cue in cues}
    out = workspace / 'reference.srt'
    write_cues(out, sorted(unique.values(), key=lambda cue: cue.start))
    return out, windows


def speech_signal(cues, start, end):
    import numpy as np
    signal = np.zeros(max(1, math.ceil((end - start) * 10)), dtype=bool)
    for cue in cues:
        a = max(0, math.floor((cue.start.total_seconds() - start) * 10))
        b = min(len(signal), math.ceil((cue.end.total_seconds() - start) * 10))
        if a < b:
            signal[a:b] = True
    return signal


def overlap_score(reference, candidate):
    import numpy as np
    denominator = int(np.count_nonzero(reference)) + int(np.count_nonzero(candidate))
    return 2 * int(np.count_nonzero(reference & candidate)) / denominator if denominator else 0.0


def shifted_signal(signal, shift):
    import numpy as np
    result = np.zeros_like(signal)
    if shift > 0:
        result[shift:] = signal[:-shift]
    elif shift < 0:
        result[:shift] = signal[-shift:]
    else:
        result[:] = signal
    return result


def validate_alignment(reference, original, corrected, windows=None):
    """Heuristic guard, not a probability: validate timing in separated regions."""
    if len(original) != len(corrected) or any(a.content != b.content for a, b in zip(original, corrected)):
        raise SyncError('Correction changed subtitle dialogue; keeping the original')
    if len(reference) < 20 or len(original) < 20:
        raise SyncError('Not enough dialogue to verify synchronization; keeping the original')
    if windows is None:
        start = reference[0].start.total_seconds()
        end = max(cue.end.total_seconds() for cue in reference)
        original_span = max(cue.end.total_seconds() for cue in original) - original[0].start.total_seconds()
        if min(end - start, original_span) < 0.75 * max(end - start, original_span):
            raise SyncError('Reference does not cover the same episode; choose a complete matching reference')
        if end - start < 30:
            raise SyncError('Reference is too short to verify synchronization')
        windows = [(start + (end - start) * i / 3, start + (end - start) * (i + 1) / 3)
                   for i in range(3)] if end - start >= 120 else [(start, end)]
    scores = []
    for start, end in windows:
        ref = speech_signal(reference, start, end)
        before = speech_signal(original, start, end)
        after = speech_signal(corrected, start, end)
        # Ignore almost empty regions, but require evidence in separated regions below.
        if sum(ref) < 50 or sum(after) < 50:
            continue
        old_score = overlap_score(ref, before)
        score = overlap_score(ref, after)
        if score < 0.40 or score < old_score - 0.03:
            raise SyncError('Timing does not match across the episode; keeping the original')
        best_shift = max(range(-20, 21), key=lambda shift: overlap_score(ref, shifted_signal(after, shift)))
        best_score = overlap_score(ref, shifted_signal(after, best_shift))
        if abs(best_shift) > 8 and best_score > score + 0.025:
            raise SyncError('Timing still drifts in part of the episode; keeping the original')
        scores.append((old_score, score))
    required = min(2, len(windows))
    if len(scores) < required or sum(score for _, score in scores) / len(scores) < 0.55:
        raise SyncError('Reference has insufficient timing agreement; keeping the original')
    before_mean = sum(score for score, _ in scores) / len(scores)
    after_mean = sum(score for _, score in scores) / len(scores)
    max_change = max(abs((a.start - b.start).total_seconds()) for a, b in zip(original, corrected))
    max_change = max(max_change, max(abs((a.end - b.end).total_seconds()) for a, b in zip(original, corrected)))
    if max_change > 0.12 and after_mean < before_mean + 0.01:
        raise SyncError('Correction does not clearly improve timing; keeping the original')
    return {'before_overlap': round(before_mean, 4), 'after_overlap': round(after_mean, 4),
            'validated_regions': len(scores), 'unchanged': max_change <= 0.12}


def synchronize(reference, source, output, partial=False, windows=None):
    try:
        import ffsubsync
        from ffsubsync.ffsubsync import make_parser
    except ImportError:
        raise SyncError('Subtitle runtime is incomplete; rebuild or reinstall this Nuvio build') from None
    original = read_cues(source)
    reference_cues = read_cues(reference)
    canonical_source = output.parent / 'sync-input.srt'
    canonical_reference = output.parent / 'sync-reference.srt'
    candidate = output.parent / 'sync-candidate.srt'
    write_cues(canonical_source, original)
    write_cues(canonical_reference, reference_cues)
    failure = None
    for mode in ('standard', 'rate-search'):
        print('SYNC_STAGE: Aligning and validating (' + mode + ')', flush=True)
        candidate.unlink(missing_ok=True)
        args = [str(canonical_reference), '-i', str(canonical_source), '-o', str(candidate),
                '--encoding', 'utf-8', '--reference-encoding', 'utf-8', '--output-encoding', 'utf-8',
                '--skip-sync-on-low-quality', '--quality-max-offset-seconds', '25',
                '--max-framerate-deviation', '0.10', '--skip-infer-framerate-ratio']
        if mode == 'rate-search':
            args.append('--gss')
        try:
            result = ffsubsync.run(make_parser().parse_args(args))
            if not isinstance(result, dict) or not result.get('sync_was_successful'):
                raise SyncError('No trustworthy timing match found; keeping the original')
            offset, scale = result.get('offset_seconds'), result.get('framerate_scale_factor')
            if offset is None or scale is None or not math.isfinite(offset) or not math.isfinite(scale):
                raise SyncError('Synchronizer returned invalid timing information')
            if abs(offset) > 25 or abs(scale - 1) > 0.10:
                raise SyncError('Correction exceeds safe timing limits; keeping the original')
            quality = validate_alignment(reference_cues, original, read_cues(candidate), windows)
            if quality['unchanged']:
                write_cues(candidate, original)
            candidate.replace(output)
            quality.update({'status': 'unchanged' if quality['unchanged'] else 'corrected',
                            'mode': mode, 'offset_seconds': round(offset, 4),
                            'scale': round(scale, 6)})
            return quality
        except SyncError as error:
            failure = error
    candidate.unlink(missing_ok=True)
    raise failure


def main(job_file):
    job = json.loads(pathlib.Path(job_file).read_text(encoding='utf-8'))
    work = pathlib.Path(job['workspace']).resolve()
    work.mkdir(parents=True, exist_ok=True)
    selected = fetch_subtitle(job['subtitle_url'], work, 'selected')
    selected = to_srt(selected, work / 'selected.srt')
    if not job.get('allow_video_reference', True):
        raise SyncError('Cannot check embedded English on a stream with custom headers; original subtitle kept')
    # An add-on can match the release yet have a constant timing error. Agreement
    # with it cannot establish video timing, so never use it as a fallback here.
    print('SYNC_STAGE: Checking embedded English across the episode', flush=True)
    reference, windows = embedded_english_reference(job.get('video_url', ''), work)
    report = synchronize(reference, selected, work / 'synced.srt', windows=windows)
    report['reference'] = 'embedded-sampled'
    # Report contains numeric timing diagnostics only, never URLs or subtitle dialogue.
    (work / 'sync-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('SYNC_UNCHANGED' if report['unchanged'] else 'SYNC_CORRECTED', flush=True)
    print('SYNC_OK', flush=True)
    return 0


def self_test_remote_extraction(ffmpeg, work):
    """A sparse subtitle must not make a short request read the entire movie."""
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    subtitle = work / 'sparse.srt'
    subtitle.write_text('1\n00:00:10,000 --> 00:00:12,000\none\n\n'
                        '2\n00:00:15,000 --> 00:00:17,000\ntwo\n\n'
                        '3\n00:01:30,000 --> 00:01:32,000\nthree\n', encoding='utf-8')
    video = work / 'sparse.mkv'
    subprocess.run([str(ffmpeg), '-v', 'error', '-nostdin', '-y', '-f', 'lavfi',
                    '-i', 'testsrc2=size=320x180:rate=10', '-i', str(subtitle),
                    '-map', '0:v', '-map', '1:s', '-c:v', 'ffv1', '-c:s', 'srt',
                    '-metadata:s:s:0', 'language=eng', '-t', '100', str(video)],
                   check=True, timeout=20, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    payload = video.read_bytes()
    transferred = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            start = int(self.headers.get('Range', 'bytes=0-').split('=')[1].split('-')[0])
            count = [0]
            transferred.append(count)
            self.send_response(206 if self.headers.get('Range') else 200)
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Content-Length', str(len(payload) - start))
            self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, len(payload) - 1, len(payload)))
            self.end_headers()
            try:
                for position in range(start, len(payload), 16384):
                    chunk = payload[position:position + 16384]
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    count[0] += len(chunk)
                    time.sleep(0.003)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        result = extract_reference_window('http://127.0.0.1:%d/video' % server.server_port,
                                          1, 0, 20, work / 'remote-reference.srt', ffmpeg)
        if len(result) != 2 or result[0].start.total_seconds() != 10:
            raise SyncError('Self-test lost embedded subtitle timestamps over HTTP')
        if sum(count[0] for count in transferred) >= len(payload) * 0.65:
            raise SyncError('Self-test read too far past the requested media window')
        print('REMOTE_READ_OK bytes=%d total=%d' % (sum(count[0] for count in transferred), len(payload)), flush=True)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def self_test():
    """Offline test run by the packager, including actual native libraries."""
    import random
    import srt
    from datetime import timedelta
    from importlib.metadata import version

    ffmpeg, ffprobe = binary_pair()
    for program in (ffmpeg, ffprobe):
        subprocess.run([str(program), '-version'], check=True, timeout=10,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with tempfile.TemporaryDirectory(prefix='nuvio sync test ') as directory:
        work = pathlib.Path(directory)
        rng = random.Random(932)
        cues = []
        start = 10.0
        for index in range(1, 101):
            duration = rng.uniform(0.7, 3.0)
            cues.append(srt.Subtitle(index, timedelta(seconds=start),
                                    timedelta(seconds=start + duration), 'مرحبا بالعالم'))
            start += duration + rng.uniform(0.8, 5.0)
        reference = work / 'reference.srt'
        source = work / 'arabic.srt'
        output = work / 'synced.srt'
        reference.write_text(srt.compose(cues), encoding='utf-8')
        shifted = [srt.Subtitle(c.index, c.start + timedelta(seconds=2),
                               c.end + timedelta(seconds=2), c.content) for c in cues]
        source.write_text(srt.compose(shifted), encoding='utf-8')
        synchronize(reference, source, output, partial=False)
        corrected = list(srt.parse(output.read_text(encoding='utf-8-sig')))
        if len(corrected) != len(cues) or any(c.content != 'مرحبا بالعالم' for c in corrected):
            raise SyncError('Self-test lost Arabic subtitle text')
        if abs((corrected[0].start - cues[0].start).total_seconds()) > 0.12:
            raise SyncError('Self-test could not correct a two-second timing offset')
        vtt = work / 'sample.vtt'
        vtt.write_text('WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nمرحبا بالعالم\n', encoding='utf-8')
        to_srt(vtt, work / 'converted.srt')
        # Exercise embedded English extraction against local media, without a server.
        video = work / 'reference.mkv'
        subprocess.run([str(ffmpeg), '-v', 'error', '-nostdin', '-y',
                        '-f', 'lavfi', '-i', 'color=size=16x16:rate=1',
                        '-i', str(reference), '-map', '0:v', '-map', '1:s', '-map', '1:s',
                        '-c:v', 'ffv1', '-c:s', 'srt', '-metadata:s:s:0', 'language=eng',
                        '-metadata:s:s:1', 'language=eng', '-disposition:s:0', 'forced',
                        '-t', str(start + 1), str(video)], check=True, timeout=20,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        sampled, windows = embedded_english_reference(str(video), work)
        if len(windows) != 3:
            raise SyncError('Self-test did not sample the complete video timeline')
        sampled_cues = read_cues(sampled)
        for a, b in windows:
            interior = [cue for cue in sampled_cues if a + 3 < cue.start.total_seconds() < b - 3]
            if not interior or any(min(abs((cue.start - original.start).total_seconds())
                                       for original in cues) > 0.01 for cue in interior):
                raise SyncError('Self-test found incorrect timestamps after seeking')
        # Check native decoding of the text codecs offered by the player.
        for codec, extension in (('ass', 'mkv'), ('mov_text', 'mp4'), ('webvtt', 'mkv')):
            converted = work / ('codec-' + codec + '.' + extension)
            subprocess.run([str(ffmpeg), '-v', 'error', '-nostdin', '-y', '-i', str(video),
                            '-map', '0:s:1', '-c:s', codec, str(converted)],
                           check=True, timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            a, b = windows[1]
            converted_cues = extract_reference_window(str(converted), 0, a, b,
                                                       work / (codec + '.srt'), ffmpeg)
            interior = [cue for cue in converted_cues if a + 3 < cue.start.total_seconds() < b - 3]
            if not interior or any(min(abs((cue.start - original.start).total_seconds())
                                       for original in cues) > 0.01 for cue in interior):
                raise SyncError('Self-test found incorrect text-codec subtitle timestamps')
        self_test_remote_extraction(ffmpeg, work)
        no_op = synchronize(reference, reference, work / 'already-aligned.srt')
        if no_op['status'] != 'unchanged':
            raise SyncError('Self-test rejected an already aligned subtitle')
    print('SELF_TEST_OK ffsubsync=' + version('ffsubsync'), flush=True)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(self_test() if sys.argv[1:] == ['--self-test'] else main(sys.argv[1]))
    except Exception as e:
        # Never print a signed URL, addon URL, or custom headers in diagnostics.
        error = str(e) if isinstance(e, SyncError) else 'Subtitle synchronization failed; choose another matching subtitle'
        print('SYNC_ERROR: ' + error, file=sys.stderr)
        sys.exit(1)
