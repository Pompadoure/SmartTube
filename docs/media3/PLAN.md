# JoTube M3: the Media3 rewrite (branch `media3`)

Goal: move JoTube from the forked ExoPlayer 2.10.6 (`exoplayer-amzn-2.10.6/`) to AndroidX Media3, for faster
video starts and real, built-in preloading of Shorts (`DefaultPreloadManager` / `PreloadMediaSource`).

Johan's targets:
1. A regular video starts in under 1.5 s after the click when its info is already prefetched
   (today about 3.3 s format info + about 2.7 s SABR start; audio and video load one after the other).
2. Switching to a preloaded Short feels instant.
3. Everything that works in JoTube today keeps working (see the checklist at the end).

## Ground rules (also in CLAUDE.md)
- Work only on the branch `media3` (and short-lived `m3/*` or `claude-task/m3-*` branches merged back into it).
  Never push to `master`, never merge `media3` into `master`, never touch the `latest-j` release.
- Package `org.smarttube.johan.media3`, app name "JoTube M3", CI `build-media3.yml` publishes the pre-release
  `latest-media3`. It installs next to JoTube, but **nothing is installed on any device** until Johan says the
  rewrite is done (a separate step).
- MediaServiceCore is changed only through new files in `patches/MediaServiceCore/`.
- Never fetch video info (format info) in parallel and never cancel such a fetch half-way: that gives 403 errors
  (see FormatFetchLock, patch 0003/0008).
- An independent review agent checks every stage before it's marked done. Evidence (CI result, code reading,
  later device logs) instead of guesses. If a stage can't be made stable: stop and write why (BLOCKED in PROGRESS.md).
- Short Swedish reports to Johan.

## Stages
Each stage is split into small steps that fit into one scheduled run (see PROGRESS.md for the current step).
Every step ends with a commit to `media3` and a green CI build (or a recorded reason why not yet).

### Stage 1: inventory and decisions (no player code yet)
- 1a. Inventory: every `com.google.android.exoplayer2` use in `common/` and `smarttubetv/` (36 files, ~140 distinct
  imports), grouped by feature (player controller, track selection, renderers/decoders, data sources, UI/leanback
  glue, MediaSession, subtitles, error handling, ShortsQueue). Write `docs/media3/INVENTORY.md`.
- 1b. JoTube/SmartTube changes inside the ExoPlayer fork itself (diff `exoplayer-amzn-2.10.6` against stock 2.10.6
  where possible; at least: ExoPlayerImplInternal, MediaPeriodQueue, PlayedPeriodsPolicy, the Amazon fork bits,
  FastStartLoadControl, decoder reuse, tunneling off) and what each one maps to in Media3 (built in, a public API,
  or still needed as a custom class).
- 1c. The SABR module (`library/sabr`, 68 files, ~9k lines): which ExoPlayer internals it uses (chunk source,
  extractor input, Matroska adapter, data sources, manifest) and their Media3 counterparts.
- 1d. Decisions: Media3 version (the latest stable one; check the release notes), minSdk (Media3 needs 21+; Streamer
  and Mi Box are far above), which extensions are needed (Cronet data source, MediaSession, decoders: are the
  ffmpeg/vp9/opus extensions used? they're not on Maven), how the old modules are taken out of the build.
  Write the result into this file under "Decisions".

### Stage 2: SABR on Media3, a regular video plays
- New module (e.g. `media3-sabr`) ported from `library/sabr` to `androidx.media3.*`.
- Media3 dependencies in `common`/`smarttubetv`, the old ExoPlayer modules out of the build for this branch.
- Minimal player path in the app: open a video, SABR source, video + audio play. CI green.

### Stage 3: the player around it
- Quality/track selection (TrackSelectorManager, RestoreTrackSelector), audio language, subtitles, speed,
  auto frame rate, buttons/controls, background/PIP, error handling (ErrorFixerController), MediaSession,
  live streams (if they use DASH/HLS: Media3 dash/hls modules).

### Stage 4: Shorts on Media3
- Preloading with `DefaultPreloadManager` (replaces ShortsQueue + the ExoPlayerImplInternal/MediaPeriodQueue changes),
  the Shorts queue/lookahead logic in VideoLoaderController, decoder reuse, the swipe transition, side panel,
  9:16 frame, blurred background.

### Stage 5: the rest of JoTube
- Everything on the checklist below, timing logs (JoTubeTiming) to compare start times with JoTube.

### Stage 6: ready for Johan
- Final review, release notes, a list of what to test. Johan decides when it's installed (separately).

## Must keep working (checklist)
Regular playback (SABR), quality/audio/subtitles, SponsorBlock, playback speed, auto frame rate, resume position,
watch history, SmartNext (next video), live streams, PIP/background, Shorts feed with preloading and the swipe
transition, Shorts side panel (title, channel avatar preload, like/dislike/comments/CC), seen-Shorts history,
Shorts metadata prefetch, sidebar warm-up, hover prefetch, FormatFetchLock (no parallel/cancelled format fetches),
tunneling off, no dubbed audio by default (Johan's own audio setting).

## Decisions
(filled in by stage 1)
