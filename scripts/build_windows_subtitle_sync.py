#!/usr/bin/env python3
"""Build a private Windows x64 subtitle runtime; no installs on the end user's PC."""
import argparse
import hashlib
import json
import os
import pathlib
import platform
import shutil
import subprocess
import sys
import urllib.request
import venv
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
FFMPEG_URL = 'https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-8.1.2-essentials_build.zip'
FFMPEG_SHA256 = 'db580001caa24ac104c8cb856cd113a87b0a443f7bdf47d8c12b1d740584a2ec'
NAME = 'nuvio-subtitle-sync'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download_ffmpeg(cache):
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / 'ffmpeg-8.1.2-essentials_build.zip'
    if archive.is_file() and digest(archive) == FFMPEG_SHA256:
        return archive
    temporary = archive.with_suffix('.download')
    print('Downloading pinned FFmpeg 8.1.2 (first build only)...', flush=True)
    try:
        with urllib.request.urlopen(FFMPEG_URL, timeout=60) as response, temporary.open('wb') as target:
            shutil.copyfileobj(response, target)
        if digest(temporary) != FFMPEG_SHA256:
            raise RuntimeError('FFmpeg SHA-256 mismatch; refusing to package this archive')
        temporary.replace(archive)
    finally:
        temporary.unlink(missing_ok=True)
    return archive


def extract_ffmpeg(archive, destination):
    """Extract the two static executables and upstream notices, without trusting ZIP paths."""
    destination.mkdir(parents=True, exist_ok=True)
    notices = destination / 'licenses'
    notices.mkdir(exist_ok=True)
    found = set()
    with zipfile.ZipFile(archive) as package:
        for member in package.infolist():
            path = pathlib.PurePosixPath(member.filename)
            if member.is_dir():
                continue
            if path.name in ('ffmpeg.exe', 'ffprobe.exe') and path.parent.name == 'bin':
                output = destination / path.name
                found.add(path.name)
            elif path.name.lower().startswith(('license', 'copying', 'readme')):
                output = notices / path.name
            else:
                continue
            with package.open(member) as source, output.open('wb') as target:
                shutil.copyfileobj(source, target)
    if found != {'ffmpeg.exe', 'ffprobe.exe'}:
        raise RuntimeError('Pinned FFmpeg archive is missing ffmpeg.exe or ffprobe.exe')
    return destination


def run(command, **kwargs):
    print('Running:', subprocess.list2cmdline([str(part) for part in command]), flush=True)
    subprocess.run([str(part) for part in command], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path,
                        default=ROOT / 'composeApp/build/subtitle-sync/app-resources/windows/subtitle-sync')
    args = parser.parse_args()
    if sys.platform != 'win32' or platform.machine().lower() not in ('amd64', 'x86_64'):
        parser.error('Build this bundle on Windows x64 with 64-bit Python (CI uses Python 3.13).')
    if not (3, 12) <= sys.version_info[:2] <= (3, 13):
        parser.error('Use Python 3.12 or 3.13 to build the pinned subtitle runtime.')
    work = ROOT / 'composeApp/build/subtitle-sync/build'
    work.mkdir(parents=True, exist_ok=True)
    environment = work / 'venv'
    python = environment / 'Scripts/python.exe'
    if not python.is_file():
        venv.EnvBuilder(with_pip=True).create(environment)
    run([python, '-m', 'pip', 'install', '--disable-pip-version-check',
         '-r', ROOT / 'scripts/subtitle-sync-requirements.txt'])
    run([python, ROOT / 'scripts/test_subtitle_sync_worker.py'])
    ffmpeg = extract_ffmpeg(download_ffmpeg(work / 'downloads'), work / 'ffmpeg')
    notices = work / 'notices'
    notices.mkdir(exist_ok=True)
    shutil.copytree(ffmpeg / 'licenses', notices / 'ffmpeg', dirs_exist_ok=True)
    python_license = pathlib.Path(sys.base_prefix) / 'LICENSE.txt'
    if not python_license.is_file():
        raise RuntimeError('Python LICENSE.txt is missing from the build interpreter')
    shutil.copy2(python_license, notices / 'PYTHON-LICENSE.txt')
    (notices / 'UPSTREAM-SOURCES.txt').write_text(
        'Python: https://www.python.org/downloads/source/\n'
        'FFsubsync: https://github.com/smacke/ffsubsync/tree/0.5.1\n'
        'PyAV: https://github.com/PyAV-Org/PyAV/tree/v19.0.1\n'
        'FFmpeg Windows build: ' + FFMPEG_URL + '\n'
        'FFmpeg archive SHA256: ' + FFMPEG_SHA256 + '\n'
        'FFmpeg build/source information: https://www.gyan.dev/ffmpeg/builds/\n'
        'FFmpeg source: https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz\n'
        'Python package license texts are retained in _internal/*.dist-info.\n', encoding='utf-8')
    with (notices / 'python-packages.txt').open('w', encoding='utf-8') as report:
        run([python, '-m', 'pip', 'freeze', '--all'], stdout=report)
    worker = ROOT / 'composeApp/src/desktopMain/resources/nuvio/audio_sync_worker.py'
    run([python, '-m', 'PyInstaller', '--noconfirm', '--clean', '--onedir', '--console',
         '--name', NAME, '--distpath', work / 'dist', '--workpath', work / 'pyinstaller',
         '--specpath', work, '--collect-all', 'ffsubsync', '--collect-all', 'av',
         '--recursive-copy-metadata', 'av',
         '--additional-hooks-dir', ROOT / 'scripts/pyinstaller-hooks',
         '--recursive-copy-metadata', 'ffsubsync',
         '--add-binary', str(ffmpeg / 'ffmpeg.exe') + os.pathsep + 'bin',
         '--add-binary', str(ffmpeg / 'ffprobe.exe') + os.pathsep + 'bin',
         '--add-data', str(notices) + os.pathsep + 'licenses', worker])
    bundle = work / 'dist' / NAME
    # Clear Python search paths and PATH to prove the result uses its own dependencies.
    clean = os.environ.copy()
    for name in ('PYTHONHOME', 'PYTHONPATH', 'NUVIO_FFMPEG_BIN'):
        clean.pop(name, None)
    clean['PATH'] = str(pathlib.Path(os.environ['SystemRoot']) / 'System32')
    run([bundle / (NAME + '.exe'), '--self-test'], env=clean, cwd=work, timeout=90)
    output = args.output.resolve()
    if output.exists():
        shutil.rmtree(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(bundle, output)
    manifest = {
        'python': platform.python_version(), 'architecture': 'windows-x64',
        'ffsubsync': '0.5.1', 'ffmpeg': '8.1.2', 'pyav': '19.0.1',
        'ffmpeg_archive_sha256': FFMPEG_SHA256,
        'self_test': 'passed',
    }
    (output / 'bundle-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print('Subtitle runtime ready:', output, flush=True)


if __name__ == '__main__':
    main()
