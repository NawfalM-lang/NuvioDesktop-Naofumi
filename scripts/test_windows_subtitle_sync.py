import io
import pathlib
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import build_windows_subtitle_sync as bundle


class BundleArchiveTests(unittest.TestCase):
    def test_extracts_binaries_and_license_without_zip_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            archive = root / 'ffmpeg.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('release/bin/ffmpeg.exe', b'ffmpeg')
                z.writestr('release/bin/ffprobe.exe', b'ffprobe')
                z.writestr('release/LICENSE', b'upstream license')
                z.writestr('../escaped.txt', b'unwanted')
            result = bundle.extract_ffmpeg(archive, root / 'runtime')
            self.assertEqual((result / 'ffmpeg.exe').read_bytes(), b'ffmpeg')
            self.assertEqual((result / 'licenses/LICENSE').read_bytes(), b'upstream license')
            self.assertFalse((root / 'escaped.txt').exists())

    def test_missing_ffprobe_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            archive = root / 'ffmpeg.zip'
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr('release/bin/ffmpeg.exe', b'ffmpeg')
            with self.assertRaisesRegex(RuntimeError, 'missing'):
                bundle.extract_ffmpeg(archive, root / 'runtime')

    def test_mismatched_download_is_removed_and_never_packaged(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            with patch.object(bundle.urllib.request, 'urlopen', return_value=io.BytesIO(b'corrupt')):
                with self.assertRaisesRegex(RuntimeError, 'SHA-256 mismatch'):
                    bundle.download_ffmpeg(root)
            self.assertEqual(list(root.iterdir()), [])

    def test_valid_cached_archive_does_not_use_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            archive = root / 'ffmpeg-8.1.2-essentials_build.zip'
            archive.write_bytes(b'cached')
            with patch.object(bundle, 'FFMPEG_SHA256', bundle.digest(archive)):
                with patch.object(bundle.urllib.request, 'urlopen') as request:
                    self.assertEqual(bundle.download_ffmpeg(root), archive)
                    request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
