"""Run with the pinned subtitle build environment; exercises the real FFsubsync engine."""
import importlib.util
import io
import json
import pathlib
import random
import tempfile
import threading
import unittest
import urllib.error
from contextlib import redirect_stdout
from datetime import timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import MagicMock, patch
from types import SimpleNamespace
from fractions import Fraction

import srt

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    'nuvio_sync_worker', ROOT / 'composeApp/src/desktopMain/resources/nuvio/audio_sync_worker.py')
worker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(worker)


def cues(seed=932, scale=1.0, offset=0.0, count=120):
    rng = random.Random(seed)
    output = []
    start = 10.0
    for i in range(count):
        duration = rng.uniform(0.7, 2.5)
        output.append(srt.Subtitle(i + 1, timedelta(seconds=start * scale + offset),
                                  timedelta(seconds=(start + duration) * scale + offset), 'مرحبا بالعالم'))
        start += duration + rng.uniform(1.0, 6.0)
    return output


class AlignmentTests(unittest.TestCase):
    def test_full_job_ignores_wrong_addon_and_aligns_to_embedded_english(self):
        payloads = {'/selected': srt.compose(cues(offset=2)).encode('utf-8'),
                    '/reference': srt.compose(cues(offset=5)).encode('utf-8')}
        requested = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requested.append(self.path.split('?')[0])
                body = payloads[requested[-1]]
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                root = pathlib.Path(tmp)
                origin = 'http://127.0.0.1:%d' % server.server_port
                job = root / 'job.json'
                job.write_text(json.dumps({'workspace': tmp, 'subtitle_url': origin + '/selected?token=secret',
                                           'english_reference_url': origin + '/reference?token=secret',
                                           'video_url': 'file:///movie.mkv'}))
                stdout = io.StringIO()
                reference = root / 'embedded.srt'
                worker.write_cues(reference, cues())
                with patch.object(worker, 'embedded_english_reference',
                                  return_value=(reference, [(0, 200), (200, 400), (400, 700)])) as embedded, \
                     redirect_stdout(stdout):
                    self.assertEqual(worker.main(job), 0)
                for actual, expected in zip(worker.read_cues(root / 'synced.srt'), cues()):
                    self.assertLess(abs((actual.start - expected.start).total_seconds()), 0.12)
                embedded.assert_called_once()
                self.assertEqual(requested, ['/selected'])
                self.assertIn('SYNC_OK', stdout.getvalue())
                self.assertNotIn('secret', stdout.getvalue())
                report = (root / 'sync-report.json').read_text()
                self.assertNotIn('secret', report)
                self.assertEqual(json.loads(report)['status'], 'corrected')
                self.assertEqual(json.loads(report)['reference'], 'embedded-sampled')
                self.assertEqual(len(worker.read_cues(root / 'synced.srt')), 120)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_real_engine_corrects_offset_and_preserves_arabic_and_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            reference, source, output = (root / name for name in ('reference.srt', 'input.srt', 'output.srt'))
            worker.write_cues(reference, cues())
            worker.write_cues(source, cues(offset=2))
            original = source.read_bytes()
            report = worker.synchronize(reference, source, output)
            self.assertEqual(report['status'], 'corrected')
            self.assertEqual(report['validated_regions'], 3)
            self.assertGreater(report['after_overlap'], report['before_overlap'])
            self.assertEqual(source.read_bytes(), original)
            for actual, expected in zip(worker.read_cues(output), cues()):
                self.assertLess(abs((actual.start - expected.start).total_seconds()), 0.12)
                self.assertEqual(actual.content, expected.content)

    def test_real_engine_accepts_already_synchronized_subtitle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source, output = root / 'source.srt', root / 'output.srt'
            worker.write_cues(source, cues())
            report = worker.synchronize(source, source, output)
            self.assertEqual(report['status'], 'unchanged')
            self.assertEqual(worker.read_cues(output), worker.read_cues(source))

    def test_real_engine_corrects_common_framerate_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            reference, source, output = (root / name for name in ('reference.srt', 'input.srt', 'output.srt'))
            worker.write_cues(reference, cues())
            worker.write_cues(source, cues(scale=25 / 23.976, offset=2))
            report = worker.synchronize(reference, source, output)
            self.assertEqual(report['status'], 'corrected')
            actual = worker.read_cues(output)
            self.assertLess(abs((actual[-1].start - cues()[-1].start).total_seconds()), 0.2)

    def test_nonstandard_drift_uses_rate_search_when_needed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            reference, source, output = (root / name for name in ('reference.srt', 'input.srt', 'output.srt'))
            worker.write_cues(reference, cues())
            worker.write_cues(source, cues(scale=1.015, offset=2))
            report = worker.synchronize(reference, source, output)
            self.assertEqual(report['mode'], 'rate-search')
            self.assertLess(abs((worker.read_cues(output)[-1].start - cues()[-1].start).total_seconds()), 0.3)

    def test_late_episode_mismatch_is_rejected(self):
        original = cues()
        bad = cues()
        for cue in bad[len(bad) // 2:]:
            cue.start += timedelta(seconds=5)
            cue.end += timedelta(seconds=5)
        with self.assertRaises(worker.SyncError):
            worker.validate_alignment(original, original, bad)

    def test_worse_correction_is_rejected(self):
        with self.assertRaises(worker.SyncError):
            worker.validate_alignment(cues(), cues(), cues(offset=1))

    def test_different_dialogue_is_never_accepted(self):
        changed = cues()
        changed[20].content = 'changed'
        with self.assertRaisesRegex(worker.SyncError, 'dialogue'):
            worker.validate_alignment(cues(), cues(), changed)

    def test_sparse_reference_is_rejected(self):
        with self.assertRaises(worker.SyncError):
            worker.validate_alignment(cues(count=3), cues(), cues())

    def test_intro_only_addon_cannot_validate_a_full_episode(self):
        with self.assertRaisesRegex(worker.SyncError, 'same episode'):
            worker.validate_alignment(cues()[:30], cues(offset=2), cues())

    def test_unrelated_reference_is_rejected(self):
        with self.assertRaises(worker.SyncError):
            worker.validate_alignment(cues(seed=431), cues(offset=2), cues())

    def test_sample_windows_are_separated_and_cover_early_middle_and_late(self):
        windows = worker.sample_windows(2400)
        self.assertEqual(len(windows), 3)
        self.assertLess(windows[0][1], windows[1][0])
        self.assertGreater(windows[-1][0], 2000)
        self.assertTrue(all(b - a <= 60 for a, b in windows))
        self.assertEqual(worker.sample_windows(90), [(0.0, 90)])

    def test_unknown_or_unbounded_duration_cannot_silently_use_only_the_intro(self):
        for duration in (0, float('nan'), float('inf'), 100_000):
            with self.assertRaises(worker.SyncError):
                worker.sample_windows(duration)

    def test_subtitle_encoding_and_timestamps_are_validated(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = pathlib.Path(tmp) / 'source.srt'
            source.write_bytes(srt.compose(cues(count=2)).encode('utf-16'))
            self.assertEqual(worker.read_cues(source)[0].content, 'مرحبا بالعالم')
            source.write_text('1\n00:00:03,000 --> 00:00:01,000\nbad\n', encoding='utf-8')
            with self.assertRaises(worker.SyncError):
                worker.read_cues(source)

    def test_utf16_subtitle_download_is_detected_before_format_sniffing(self):
        payload = srt.compose(cues(count=2)).encode('utf-16')
        response = io.BytesIO(payload)
        response.headers = {'Content-Length': str(len(payload))}
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(worker.urllib.request, 'urlopen', return_value=response):
                path = worker.fetch_subtitle('https://provider/subtitle', pathlib.Path(tmp), 'source')
            self.assertEqual(path.suffix, '.srt')
            self.assertEqual(worker.read_cues(path)[0].content, 'مرحبا بالعالم')

    def test_rejected_candidate_never_becomes_final_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / 'source.srt'
            worker.write_cues(source, cues())
            with patch('ffsubsync.run', return_value={'sync_was_successful': False}):
                with self.assertRaises(worker.SyncError):
                    worker.synchronize(source, source, root / 'output.srt')
            self.assertFalse((root / 'output.srt').exists())

    def test_embedded_reference_is_used_without_fetching_addon(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / 'source.srt'
            worker.write_cues(source, cues())
            job = root / 'job.json'
            job.write_text(json.dumps({'workspace': tmp, 'subtitle_url': 'https://private/input',
                                       'english_reference_url': 'https://private/reference', 'video_url': 'https://private/video'}))
            expected = {'unchanged': True, 'status': 'unchanged'}
            with patch.object(worker, 'fetch_subtitle', return_value=source) as fetch, \
                 patch.object(worker, 'embedded_english_reference', return_value=(source, [(0, 100)])) as embedded, \
                 patch.object(worker, 'synchronize', return_value=expected), redirect_stdout(io.StringIO()):
                self.assertEqual(worker.main(job), 0)
            embedded.assert_called_once()
            fetch.assert_called_once()
            report = (root / 'sync-report.json').read_text()
            self.assertNotIn('private', report)
            self.assertEqual(json.loads(report)['reference'], 'embedded-sampled')

    def test_custom_header_stream_never_falls_back_without_its_credentials(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / 'source.srt'
            worker.write_cues(source, cues())
            job = root / 'job.json'
            job.write_text(json.dumps({'workspace': tmp, 'subtitle_url': 'https://private/input',
                                       'english_reference_url': 'https://private/reference', 'allow_video_reference': False}))
            with patch.object(worker, 'fetch_subtitle', return_value=source) as fetch, \
                 patch.object(worker, 'embedded_english_reference') as embedded, redirect_stdout(io.StringIO()):
                with self.assertRaises(worker.SyncError):
                    worker.main(job)
            embedded.assert_not_called()

    def test_missing_embedded_reference_never_trusts_addon_or_reports_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / 'source.srt'
            worker.write_cues(source, cues(offset=2))
            original = source.read_bytes()
            job = root / 'job.json'
            job.write_text(json.dumps({'workspace': tmp, 'subtitle_url': 'https://private/input',
                                       'english_reference_url': 'https://private/wrong-reference',
                                       'video_url': 'https://private/video'}))
            stdout = io.StringIO()
            with patch.object(worker, 'fetch_subtitle', return_value=source) as fetch, \
                 patch.object(worker, 'embedded_english_reference',
                              side_effect=worker.SyncError('No complete embedded English text track found')), \
                 patch.object(worker, 'synchronize') as sync, redirect_stdout(stdout):
                with self.assertRaisesRegex(worker.SyncError, 'complete embedded English'):
                    worker.main(job)
            fetch.assert_called_once()
            sync.assert_not_called()
            self.assertNotIn('SYNC_OK', stdout.getvalue())
            self.assertFalse((root / 'synced.srt').exists())
            self.assertFalse((root / 'sync-report.json').exists())
            self.assertEqual(source.read_bytes(), original)

    def test_already_aligned_arabic_is_not_shifted_to_wrong_addon(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source, reference = root / 'source.srt', root / 'embedded.srt'
            worker.write_cues(source, cues())
            worker.write_cues(reference, cues())
            job = root / 'job.json'
            job.write_text(json.dumps({'workspace': tmp, 'subtitle_url': 'https://private/input',
                                       'english_reference_url': 'https://private/wrong-reference',
                                       'video_url': 'file:///movie.mkv'}))
            with patch.object(worker, 'fetch_subtitle', return_value=source) as fetch, \
                 patch.object(worker, 'embedded_english_reference',
                              return_value=(reference, [(0, 200), (200, 400), (400, 700)])), \
                 redirect_stdout(io.StringIO()):
                self.assertEqual(worker.main(job), 0)
            fetch.assert_called_once()
            self.assertEqual(json.loads((root / 'sync-report.json').read_text())['status'], 'unchanged')
            for actual, expected in zip(worker.read_cues(root / 'synced.srt'), worker.read_cues(source)):
                self.assertEqual(actual.start, expected.start)
                self.assertEqual(actual.content, expected.content)

    def media_with_sparse_subtitles(self):
        subtitle = SimpleNamespace(start_display_time=0, end_display_time=2000,
                                   rects=[SimpleNamespace(dialogue=b'English dialogue')])
        stream = SimpleNamespace(index=2, type='subtitle', decode2=lambda packet: subtitle)
        video = SimpleNamespace(index=0, type='video')
        media = MagicMock()

        def packets():
            yield SimpleNamespace(stream=stream, pts=10000, dts=None, duration=2000,
                                  time_base=Fraction(1, 1000), size=12)
            yield SimpleNamespace(stream=video, pts=20000, dts=None,
                                  time_base=Fraction(1, 1000), size=1000)
            self.fail('Read beyond the requested window while waiting for a later subtitle')

        media.__enter__.return_value.demux.side_effect = packets
        return media

    def test_extraction_stops_on_video_clock_without_decoding_video(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = pathlib.Path(tmp) / 'reference.srt'
            with patch('av.open', return_value=self.media_with_sparse_subtitles()):
                actual = worker.extract_reference_window('https://private/video', 2, 0, 20, output, None)
            self.assertEqual(len(actual), 1)
            self.assertEqual(actual[0].start.total_seconds(), 10)
            self.assertEqual(actual[0].end.total_seconds(), 12)

    def test_transient_media_failure_retries_and_discards_failed_attempt(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = pathlib.Path(tmp) / 'reference.srt'
            with patch('av.open', side_effect=[OSError('private signed URL'), self.media_with_sparse_subtitles()]) as opened, \
                 redirect_stdout(io.StringIO()):
                actual = worker.extract_reference_window('https://private/video', 2, 0, 20, output, None)
            self.assertEqual(opened.call_count, 2)
            self.assertEqual(len(actual), 1)

    def test_exhausted_media_reads_fail_without_output_or_private_errors(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = pathlib.Path(tmp) / 'reference.srt'
            with patch('av.open', side_effect=OSError('https://private/?token=secret')) as opened, \
                 redirect_stdout(io.StringIO()):
                with self.assertRaises(worker.SyncError) as caught:
                    worker.extract_reference_window('https://private/video', 2, 0, 20, output, None)
            self.assertEqual(opened.call_count, 2)
            self.assertNotIn('secret', str(caught.exception))
            self.assertFalse(output.exists())

    def test_expired_shared_deadline_does_not_start_another_attempt(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = pathlib.Path(tmp) / 'reference.srt'
            with patch('av.open', side_effect=OSError('private')) as opened, \
                 patch.object(worker.time, 'monotonic', side_effect=[0, 0, 31]), \
                 redirect_stdout(io.StringIO()):
                with self.assertRaises(worker.SyncError):
                    worker.extract_reference_window('https://private/video', 2, 0, 20, output, None)
            self.assertEqual(opened.call_count, 1)
            self.assertFalse(output.exists())

    def test_download_errors_do_not_expose_urls(self):
        with tempfile.TemporaryDirectory() as tmp:
            error = urllib.error.HTTPError('https://private/?token=secret', 403, 'secret', {}, None)
            with patch.object(worker.urllib.request, 'urlopen', side_effect=error):
                with self.assertRaises(worker.SyncError) as caught:
                    worker.fetch_subtitle('https://private/?token=secret', pathlib.Path(tmp), 'source')
            self.assertNotIn('secret', str(caught.exception))
            self.assertIn('403', str(caught.exception))

    def test_forced_english_track_is_not_used_as_a_full_dialogue_reference(self):
        metadata = {'format': {'duration': '90'}, 'streams': [
            {'index': 1, 'codec_name': 'subrip', 'tags': {'language': 'eng'}, 'disposition': {'forced': 1}},
            {'index': 2, 'codec_name': 'subrip', 'tags': {'language': 'eng'}, 'disposition': {'forced': 0}},
        ]}
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            video = root / 'movie.mkv'
            video.touch()
            result = type('Probe', (), {'returncode': 0, 'stdout': json.dumps(metadata)})()
            with patch.object(worker, 'binary_pair', return_value=('ffmpeg', 'ffprobe')), \
                 patch.object(worker.subprocess, 'run', return_value=result), \
                 patch.object(worker, 'extract_reference_window', return_value=cues()) as extract:
                worker.embedded_english_reference(str(video), root)
            self.assertEqual(extract.call_args.args[1], 2)

    def test_forced_only_video_cannot_verify_alignment(self):
        metadata = {'format': {'duration': '90'}, 'streams': [
            {'index': 1, 'codec_name': 'subrip', 'tags': {'language': 'eng'}, 'disposition': {'forced': 1}},
        ]}
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            video = root / 'movie.mkv'
            video.touch()
            result = type('Probe', (), {'returncode': 0, 'stdout': json.dumps(metadata)})()
            with patch.object(worker, 'binary_pair', return_value=('ffmpeg', 'ffprobe')), \
                 patch.object(worker.subprocess, 'run', return_value=result):
                with self.assertRaisesRegex(worker.SyncError, 'complete embedded English'):
                    worker.embedded_english_reference(str(video), root)

    def test_nonfinite_engine_parameters_cannot_be_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            source = root / 'source.srt'
            worker.write_cues(source, cues())
            with patch('ffsubsync.run', return_value={'sync_was_successful': True,
                       'offset_seconds': float('nan'), 'framerate_scale_factor': 1.0}):
                with self.assertRaisesRegex(worker.SyncError, 'invalid timing'):
                    worker.synchronize(source, source, root / 'output.srt')
            self.assertFalse((root / 'output.srt').exists())


if __name__ == '__main__':
    unittest.main()
