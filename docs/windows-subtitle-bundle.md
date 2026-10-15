# Windows subtitle synchronization bundle

The Windows installer includes a private subtitle executable, Python runtime,
FFsubsync 0.5.1 and FFmpeg/FFprobe 8.1.2. End users do not need Python, pip, a PATH
change, or a separate FFmpeg installation. Subtitle URLs and media still require
network access; no dependencies are downloaded when synchronization starts.

## Build an installer

On a Windows x64 development PC, install 64-bit Python 3.13 with the Python launcher
once. All other Nuvio build prerequisites remain the same. Packaging creates its
own isolated build environment and downloads the pinned dependencies on the first
build. Generated binaries stay under `composeApp/build` and are not committed.

```powershell
$webview2 = Join-Path $env:LOCALAPPDATA "NuvioBuildDeps\Microsoft.Web.WebView2.1.0.4078.44"
.\gradlew.bat :composeApp:packageReleaseMsi `
  "-Pnuvio.webview2.dir=$webview2" `
  --no-configuration-cache --console=plain
```

For an interpreter installed without `py`, pass
`-Pnuvio.subtitleSync.buildPython=C:/path/to/python.exe` (Python 3.12 or 3.13).
The same build runs in the desktop release workflow. The independent **Windows
bundled subtitle runtime** workflow tests the helper and uploads its complete
directory as a CI artifact; that artifact is not the full Nuvio installer.

To build only the subtitle runtime:

```powershell
py -3.13 scripts/build_windows_subtitle_sync.py
```

## Runtime discovery

Installed builds launch
`<Compose resources>/windows/subtitle-sync/nuvio-subtitle-sync.exe` directly.
All files in that directory, including `_internal`, must stay together. FFmpeg is
resolved from `_internal/bin`, before system executables. A missing installed
bundle produces a repair error instead of asking end users to install Python.
Gradle development runs use an existing bundle if present and retain the previous
`py -3` fallback otherwise. Developers can override the runtime directory using
`-Dnuvio.subtitleSync.runtimeDir=C:/path/to/subtitle-sync`.

## Validation

Each bundle build executes `--self-test` with Python-related environment variables
removed and PATH limited to Windows system tools. It checks real FFsubsync imports,
corrects a known two-second offset, preserves Arabic text, converts WebVTT, probes
local media and extracts an embedded English subtitle. Temporary paths include
spaces. MSI verification extracts the actual installer and repeats this test on
the extracted executable. CI success does not replace playback testing of the
installed application on a separate Windows PC.

## Included notices and versions

FFmpeg's ZIP is pinned by URL and SHA-256; a mismatched download fails the build.
The package retains upstream FFmpeg notices, Python's license, Python package
distribution metadata and licenses, resolved package versions, and upstream source
links. `bundle-manifest.json` records the versions and self-test status. Retain these
files when redistributing the runtime, along with Nuvio's existing license and source.

This bundle supports Windows x64. It does not add macOS, Linux or ARM64 subtitle
runtime packaging, or change the subtitle matching/synchronization algorithm.
