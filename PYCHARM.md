# AudioLens for PyCharm

This repository contains a PyCharm port of [SimZhou/vscode-audiolens](https://github.com/SimZhou/vscode-audiolens), based on upstream release 1.8.11.

## Install

1. Build the plugin or download the generated `pycharm-audiolens-*.zip` artifact.
2. In PyCharm, open **Settings | Plugins**.
3. Choose the gear menu, **Install Plugin from Disk…**, select the ZIP, and restart PyCharm.
4. Open a supported audio file. AudioLens replaces the normal editor for that file.

The current build supports PyCharm 2025.1 through 2026.1 (`251`–`261.*`). It is a single ZIP for Windows, macOS, and Linux.

## Supported workflows

- WAV, MP3, FLAC, OGG, Opus, M4A and AAC
- raw `.pcm` and `.raw` audio with explicit format parameters
- multichannel waveform and spectrogram views
- adaptive, source PCM sample-value, and symmetric dBFS waveform scales, with contextual help for audio settings
- playback, zoom, channel gain/pan/mute/solo, header inspection and selection metrics
- Kaldi WAV ark entries through **Tools | Open Kaldi WAV from Ark…** or by opening an `.ark` file and entering the RIFF/WAVE byte offset
- Ctrl-click (Command-click on macOS) audio paths in Python, JSON, text, logs, and `wav.scp`
- absolute paths, paths relative to the current text file or project root, and `audio.ark:offset` text references
- ordinary remote audio downloaded through an existing PyCharm Deployment/SFTP configuration while the IDE remains local
- selection export to WAV

JetBrains Remote Development/Gateway is not supported and is not required for SFTP audio. Remote `.ark` and `.ark:offset` files are not supported over SFTP; download the Ark locally first.

## Open audio paths from text

Hold **Ctrl** (**Command** on macOS) over an existing audio path in any normal code or text editor. AudioLens underlines the path and opens it when clicked. Relative paths are resolved from the current text file first, then from the project root.

Regular audio paths and Kaldi WAV ark references are supported, for example:

```text
audio = "../samples/example.wav"
utterance-001 data/train/audio.flac
utterance-002 data/train/wav.ark:23252
```

## Open remote audio through SFTP

This workflow is for local PyCharm with a remote Python interpreter. It does not require Remote Development or a remote IDE backend. AudioLens reuses an SFTP Deployment configuration already stored by PyCharm and never stores the server password or private key itself.

1. Confirm that the SFTP server works in **Settings | Build, Execution, Deployment | Deployment**. PyCharm normally creates the corresponding Deployment configuration when an SSH remote interpreter is created.
2. Open **Settings | Tools | AudioLens**, enable **SFTP audio paths**, and select the SFTP configuration.
3. Use **Test SFTP**. If paths in source text differ from real server paths, configure a mapping. For example, prefix `/data` and remote base `/mnt/datasets` map `/data/a.wav` to `/mnt/datasets/a.wav`.
4. Ctrl/Command-click a path in code, logs, or `wav.scp`, use **Tools | Open Remote Audio via SFTP…**, or open an ordinary audio file directly from PyCharm's **Remote Host** view.

For paths opened through AudioLens, the plugin streams the whole file into a temporary local download with progress and cancellation, then atomically places the completed file in a bounded local cache. Reopening compares remote size and modification time and reuses an unchanged cache entry. When a file is opened from **Remote Host**, Web Deployment first retrieves its content and AudioLens copies that virtual file into the same bounded local cache before displaying it. The cache limit and clear controls are available in settings, and **Tools | Clear AudioLens Remote Cache** clears it manually.

Remote `.ark` and `.ark:offset` are deliberately unsupported. Efficient Ark offsets need a separate remote range-read design, which is outside this experimental SFTP integration. Local Ark support is unchanged.

## FFmpeg

AudioLens does not bundle or download FFmpeg. Browser-supported files are decoded directly; M4A/AAC and browser decode fallbacks require a system FFmpeg installation.

Open **Settings | Tools | AudioLens** to set an explicit FFmpeg executable or use **Detect / Test FFmpeg**. If the field is blank, AudioLens searches `PATH` and common installation locations.

FFmpeg and SFTP failures provide actionable messages. Use **Copy Diagnostics** on an error notification, **Copy AudioLens Diagnostics** in settings, or **Tools | Copy AudioLens Diagnostics** to copy a report. It includes plugin, IDE, operating system, JCEF, FFmpeg, SFTP feature state, and latest failure details, but excludes audio content, server addresses, accounts, passwords, private keys, full audio paths, and project paths.

## Privacy and trust

Audio processing is local. The plugin has no telemetry and never uploads audio to a third-party service. When SFTP is enabled, selected audio is downloaded from your server into PyCharm's local cache. In an untrusted project it displays file metadata but does not transfer audio content into the embedded browser. Close and reopen the audio tab after trusting a project.

The browser receives audio only through random per-editor tokens. Source requests are range-limited, and calculated binary responses use short-lived, one-time URLs.

## Build

Requirements: Java 21 and Node.js 20 or newer.

```bash
./gradlew clean test buildPlugin verifyPluginStructure
```

The installable ZIP is written to `build/distributions/`.

## License and attribution

Licensed under Apache License 2.0. The TypeScript visualization and signal-analysis code is derived from the upstream AudioLens project; the PyCharm host integration is implemented in Kotlin. See `LICENSE` and `NOTICE`.
