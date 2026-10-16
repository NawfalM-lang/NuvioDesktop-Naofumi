# Windows subtitle synchronization bundle

The Windows installer includes a private subtitle executable, Python runtime,
FFsubsync 0.5.1, PyAV 19.0.1 and FFmpeg/FFprobe 8.1.2. End users do not need Python, pip, a PATH
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
`<Compose resources>/subtitle-sync/nuvio-subtitle-sync.exe` directly.
Compose copies the contents of the build-time `app-resources/windows` folder into
the installed resources root; the `windows` selector folder is not retained.
The launcher also accepts the old nested layout for manually staged installations.
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
spaces. The resource preparation task checks the flattened layout and required
runtime files before packaging. MSI verification extracts the actual installer,
requires the executable specifically under `app/resources/subtitle-sync`, and
repeats the helper self-test. Launcher regression tests cover installed and legacy
layouts, explicit overrides, spaces, and incomplete installs.
CI success does not replace playback testing of the
installed application on a separate Windows PC.

## Alignment reliability

The worker uses FFsubsync 0.5.1 with these additional checks:

- Decode and validate text first, normalize the working copies to UTF-8, and
  require every dialogue line to survive correction. Originals are never edited.
- Use the active video’s embedded English text track. English add-on timing is
  unverified and is never used as an automatic fallback: aligning two external
  subtitles can leave both out of sync with the video. If embedded English cannot
  be read (including streams needing custom headers), fail and keep the original.
  Forced-only English tracks are excluded; ordinary dialogue is preferred to SDH.
- Embedded references sample up to one minute each near 10%, 50%, and 90% of
  the timeline. Videos up to three minutes use one window. PyAV demuxes packets
  from all streams to stop on the video/audio clock, decoding only the chosen
  subtitle. This avoids subtitle-only FFmpeg reads extending through long quiet
  scenes while waiting for another subtitle packet. Original timestamps and the
  container start-time adjustment are preserved. No video/audio is decoded.
- Extraction has a 30-second budget per parallel window, bounded network reads,
  and one retry for a failed read within the same budget. Partial failed reads
  are discarded. Unknown durations fail explicitly; the 90-second host limit
  still applies. A provider must support usable seeks for remote sampling.
- Compare subtitle timing overlap in separated regions before accepting an
  output. Reject insufficient agreement, remaining regional drift, worsening
  alignment, altered dialogue, and implausible shifts.
  These are conservative heuristics, not an accuracy guarantee or probability.
- Skip duration-based framerate inference, which can be misleading with sparse
  or incomplete references. If the standard result fails verification, retry
  FFsubsync's continuous framerate search (`--gss`) and apply the same checks.
  Experimental piecewise correction and audio/AI fallbacks are not enabled.
- Treat an already aligned subtitle as a successful comparison with embedded English.
  The embedded track must itself be correctly timed; these checks do not verify
  dialogue against the audio.
- Stop the private worker and FFmpeg on cancellation or the 90-second overall
  deadline, and remove temporary inputs, credentials, and logs afterward. Only
  the verified playback SRT remains until application exit.

Every Windows runtime build runs `scripts/test_subtitle_sync_worker.py` in its
private Python environment before freezing. This includes real FFsubsync tests
for offsets, common and uncommon framerate drift, and unchanged files, plus
failure and reference-selection tests. The frozen helper self-test additionally
checks actual embedded extraction at separated timeline positions, ASS/WebVTT/MP4
text decoding, and a throttled HTTP fixture with sparse subtitles. The HTTP test
rejects reading most of a file for a short sample. This does not guarantee every
provider will permit timely remote extraction.

## Included notices and versions

FFmpeg's ZIP is pinned by URL and SHA-256; a mismatched download fails the build.
The package retains upstream FFmpeg notices, Python's license, Python package
distribution metadata and licenses, resolved package versions, and upstream source
links. `bundle-manifest.json` records the versions and self-test status. Retain these
files when redistributing the runtime, along with Nuvio's existing license and source.

This bundle supports Windows x64. It does not add macOS, Linux or ARM64 subtitle
runtime packaging. FFsubsync remains the alignment engine.
