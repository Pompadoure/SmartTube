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
Steps: 2a toolchain upgrade (Decisions 3; CI green with the old player), 2b `media3-sabr` module compiles on its
own (SABR_PORT.md order), 2c on `m3/stage2`: swap the dependencies and port the minimal player path until CI is green,
then merge into `media3`.
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

## Decisions (step 1d, 2026-10-10)
Evidence: three CI probes on `m3/probe-*` branches (Media3 in `common`, minSdk 23, a class using `ExoPlayer`,
`DefaultPreloadManager`, `ChunkSampleStream`, `DashMediaSource`), current toolchain AGP 7.4.2 / Gradle 7.5 /
compileSdk 34 / Kotlin 1.8.10 / JDK 17:
- Media3 **1.11.1** (latest stable, 2026-09-11): fails `checkAarMetadata`, every artifact needs **compileSdk 36**
  (run 38067769977).
- Media3 **1.5.1**: fails the same way, needs **compileSdk 35** (run 38068173305).
- Media3 **1.4.1**: **builds** (run 38068171473), `DefaultPreloadManager` included.

1. **Media3 version: 1.11.1** (latest stable). Reasons: `DefaultPreloadManager` matured a lot after 1.4 (batch
   add/remove and auto-invalidate in 1.9, custom `DataSource.Factory` for preloading in 1.10, ranking comparator in
   1.11), and Shorts preloading is the main goal. Cost: a toolchain upgrade first (decision 3).
   **Fallback: 1.4.1**, proven to build with today's toolchain, if the toolchain upgrade can't be made stable in
   about two runs (then record it here and in PROGRESS.md).
2. **minSdk 23** for the `stjohan` flavor and `common` (Media3 needs 23 since 1.9.0). Streamer (Android 14, API 34)
   and Mi Box (Android 9, API 28) are both above it. **targetSdk stays 34**: `stjohan` currently uses
   `targetSdkVersion = compileSdkVersion`; it gets pinned to 34 so raising compileSdk doesn't switch on new runtime
   behaviour (edge-to-edge, foreground service rules of API 35/36).
3. **Toolchain (stage 2, first step, before any Media3 code): AGP 8.x with compileSdk 36 support (latest 8.x,
   e.g. 8.13), its Gradle 8.x, compileSdk 36, Kotlin 2.x, JDK 17 (CI already).** Not AGP 9 (bigger DSL/Kotlin
   changes, nothing we need). Known AGP 8 work: `namespace` for every Android module, `buildFeatures.buildConfig`,
   `android.nonTransitiveRClass=false`, `android.defaults.buildfeatures.buildconfig`, Kotlin stdlib forcing in the
   root `build.gradle` lifted to the new version. The submodules (`MediaServiceCore`, `SharedModules`) stay
   untouched if possible: set `namespace` (from each module's manifest `package`) and the build features from the
   root `build.gradle` (`subprojects { plugins.withId('com.android.library') … }`); otherwise patch files
   (`patches/MediaServiceCore/`, and a new `patches/SharedModules/` applied by CI the same way). Done = CI green
   with the old ExoPlayer still in place, then Media3 goes in.
4. **Media3 modules:** `media3-exoplayer`, `media3-exoplayer-dash` (DashManifestParser2 path, 22 dash imports),
   `media3-exoplayer-hls` and `media3-exoplayer-smoothstreaming` (one/two app imports each: live streams; drop SS if
   it turns out unused), `media3-datasource-okhttp`, `media3-datasource-cronet` (app's own Cronet engine),
   `media3-session` (replaces `MediaSessionConnector`, see INVENTORY), `media3-ui` (PlayerView/SubtitleView, also for
   `doubletapplayerview`), `media3-ui-leanback` (LeanbackPlayerAdapter; port the JoTube no-surface fix),
   `media3-extractor`/`media3-datasource`/`media3-common` come transitively. **No decoder extensions** (ffmpeg, vp9,
   opus, flac, av1: unused by the app, step 1a).
5. **SABR:** new module `media3-sabr` ported from `library/sabr` as in `SABR_PORT.md` (drop live-only emsg/event code,
   `isDrc`/`lastModified` in `SabrFormatMetadata`, `shouldCancelLoad` never cancels).
6. **Old modules out of the build:** in stage 2 the `exoplayer-amzn-2.10.6` include in `settings.gradle` and the
   `:exoplayer-*` project dependencies in `common`, `smarttubetv` and `doubletapplayerview` are replaced by the Media3
   artifacts in one step (the app can't compile against both `Player` APIs at once without a long dual phase). The
   directory itself stays in the repo until stage 6 (reference for porting), only unreferenced. Because that step
   breaks compilation of every player file at once, stage 2 is done on a work branch `m3/stage2` (CI builds `m3/**`
   without publishing) and merged into `media3` when CI is green.
