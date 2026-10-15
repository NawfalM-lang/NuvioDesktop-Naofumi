#!/usr/bin/env python3
"""NUVIO_FAST_SUBSYNC_V5: fast subtitle-to-subtitle alignment for Nuvio Windows.

Arabic (or other selected language) aligns against an English subtitle reference.
Order: English addon subtitle (no media/ffmpeg access for SRT) -> embedded English
text subtitle (bounded FFprobe+FFmpeg extraction) -> explicit failure.
NO audio speech analysis: do not introduce a long, opaque fallback.

Run: py -3 fast_sync_worker.py job.json
The job JSON contains sensitive signed URLs and must be deleted by the host.
"""
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

MAX_SUB_BYTES = 16 * 1024 * 1024
PROBE_TIMEOUT_S = 8
EXTRACT_TIMEOUT_S = 18
FETCH_TIMEOUT_S = 12


def fetch_subtitle(url, workspace, stem):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ('http', 'https'):
        raise RuntimeError('Subtitle URL must use HTTP(S)')
    request = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 (NuvioDesktop Fast Sync)',
        'Accept': 'text/plain, application/x-subrip, text/vtt, */*',
    })
    with urllib.request.urlopen(request, timeout=FETCH_TIMEOUT_S) as response:
        size = response.headers.get('Content-Length', '')
        if size.isdigit() and int(size) > MAX_SUB_BYTES:
            raise RuntimeError('Subtitle response is too large')
        raw = response.read(MAX_SUB_BYTES + 1)
    if len(raw) > MAX_SUB_BYTES:
        raise RuntimeError('Subtitle response is too large')
    head = raw[:4096].lstrip(b'\xef\xbb\xbf \r\n\t')
    if head.startswith(b'WEBVTT'):
        ext = '.vtt'
    elif b'[Script Info]' in head or b'[V4+ Styles]' in head:
        ext = '.ass'
    elif b'-->' in head or re.search(rb'\b\d{2}:\d{2}:\d{2}[,.]\d{3}', head):
        ext = '.srt'
    else:
        raise RuntimeError('Subtitle response is not a supported text format')
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
    raise RuntimeError('Cannot locate FFmpeg and FFprobe; required only for embedded subtitle extraction or subtitle conversion')


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
        raise RuntimeError('Subtitle conversion timed out') from e
    if result.returncode or not dest.is_file() or dest.stat().st_size < 20:
        raise RuntimeError('Could not convert subtitle file to SRT')
    return dest


def normalize_video(value):
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
    raise RuntimeError('This stream is not accessible to the embedded subtitle extractor')


def embedded_english_reference(video_url, workspace):
    """No full-duration media scan: probe metadata and extract first 7min only."""
    ffmpeg, ffprobe = binary_pair()
    video = normalize_video(video_url)
    try:
        probe = subprocess.run([
            str(ffprobe), '-v', 'error', '-select_streams', 's',
            '-show_entries', 'stream=index,codec_name:stream_tags=language',
            '-of', 'json', video,
        ], capture_output=True, text=True, encoding='utf-8', errors='replace',
            timeout=PROBE_TIMEOUT_S, check=False)
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError('Embedded subtitle lookup took too long; add an English addon subtitle for instant sync') from exc
    if probe.returncode:
        raise RuntimeError('Cannot read embedded subtitle metadata; an English addon subtitle is needed')
    try:
        streams = json.loads(probe.stdout).get('streams', [])
    except ValueError as exc:
        raise RuntimeError('Video subtitle metadata was invalid') from exc
    usable = [s for s in streams if s.get('codec_name') in ('subrip','webvtt','ass','ssa','mov_text')]
    english = [s for s in usable if str(s.get('tags', {}).get('language', '')).lower().split('-')[0]
               in ('en','eng')]
    if not english:
        raise RuntimeError('No embedded English text subtitle found. Make English subtitles available from an addon')
    idx = int(english[0]['index'])
    out = workspace / 'reference.srt'
    # Input time limit prevents downloading the entire video. This is for a
    # reference offset near the beginning; it may not fix later drift.
    try:
        conversion = subprocess.run([
            str(ffmpeg), '-hide_banner', '-loglevel', 'error', '-nostdin', '-y',
            '-t', '420', '-i', video, '-map', '0:'+str(idx), '-vn', '-an',
            '-c:s', 'srt', '-f', 'srt', str(out),
        ], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=EXTRACT_TIMEOUT_S, check=False)
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError('Embedded subtitle extraction exceeded 18s; add English addon subtitles for faster sync') from exc
    if conversion.returncode or not out.is_file() or out.stat().st_size < 60:
        raise RuntimeError('Embedded English subtitle could not be extracted. Add an English subtitle addon')
    if out.stat().st_size > MAX_SUB_BYTES:
        raise RuntimeError('Embedded reference subtitle too large')
    return out


def synchronize(reference, source, output, partial):
    try:
        import ffsubsync
        from ffsubsync.ffsubsync import make_parser
    except ImportError as exc:
        message = ('Bundled subtitle runtime is incomplete; reinstall this Nuvio build'
                   if getattr(sys, 'frozen', False)
                   else 'FFsubsync missing: py -3 -m pip install ffsubsync')
        raise RuntimeError(message) from exc
    args = [str(reference), '-i', str(source), '-o', str(output),
            '--skip-sync-on-low-quality', '--quality-max-offset-seconds', '25']
    if partial:
        args.append('--skip-infer-framerate-ratio')
    result = ffsubsync.run(make_parser().parse_args(args))
    if not isinstance(result, dict) or not result.get('sync_was_successful'):
        raise RuntimeError('No confident subtitle-to-subtitle timing match was found')
    if not output.is_file() or output.stat().st_size < 20:
        raise RuntimeError('Alignment did not produce a valid SRT file')
    if output.read_bytes() == source.read_bytes():
        raise RuntimeError('Subtitle timing did not need adjustment or match was uncertain')


def main(job_file):
    job = json.loads(pathlib.Path(job_file).read_text(encoding='utf-8'))
    work = pathlib.Path(job['workspace']).resolve()
    work.mkdir(parents=True, exist_ok=True)
    selected = fetch_subtitle(job['subtitle_url'], work, 'selected')
    selected = to_srt(selected, work / 'selected.srt')
    english_url = job.get('english_reference_url') or ''
    if english_url:
        reference = fetch_subtitle(english_url, work, 'reference')
        reference = to_srt(reference, work / 'reference-converted.srt')
        partial = False
        print('SYNC_STAGE: English addon subtitle reference', flush=True)
    else:
        reference = embedded_english_reference(job.get('video_url', ''), work)
        partial = True
        print('SYNC_STAGE: Embedded English reference', flush=True)
    synchronize(reference, selected, work / 'synced.srt', partial)
    print('SYNC_OK', flush=True)
    return 0


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
            raise RuntimeError('Self-test lost Arabic subtitle text')
        if abs((corrected[0].start - cues[0].start).total_seconds()) > 0.12:
            raise RuntimeError('Self-test could not correct a two-second timing offset')
        vtt = work / 'sample.vtt'
        vtt.write_text('WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nمرحبا بالعالم\n', encoding='utf-8')
        to_srt(vtt, work / 'converted.srt')
        # Exercise the embedded-English fallback against local media, without a server.
        video = work / 'reference.mkv'
        subprocess.run([str(ffmpeg), '-v', 'error', '-nostdin', '-y',
                        '-f', 'lavfi', '-i', 'color=size=16x16:rate=1',
                        '-i', str(reference), '-map', '0:v', '-map', '1:s',
                        '-c:v', 'ffv1', '-c:s', 'srt', '-metadata:s:s:0', 'language=eng',
                        '-t', '30', str(video)], check=True, timeout=20,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        embedded_english_reference(str(video), work)
    print('SELF_TEST_OK ffsubsync=' + version('ffsubsync'), flush=True)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(self_test() if sys.argv[1:] == ['--self-test'] else main(sys.argv[1]))
    except Exception as e:
        # Never print a signed URL, addon URL, or custom headers in diagnostics.
        error = str(e).split('http')[0].split('file:')[0][:200]
        print('SYNC_ERROR: ' + error, file=sys.stderr)
        sys.exit(1)
