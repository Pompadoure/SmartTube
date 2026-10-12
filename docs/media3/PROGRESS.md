# JoTube M3 progress

Status: ACTIVE
Next step: 4a (stage 4, Shorts on Media3: split PLAN.md stage 4 into steps first, on branch `m3/stage4`)

Status values: ACTIVE (the scheduled runs keep going), WAITING_FOR_JOHAN (a question to Johan, runs only check
for his answer), BLOCKED (a stage can't be made stable; reason below), DONE (stage 6 finished).

## Usage guard (no unexpected stops)
The Claude plan's usage can't be read by a run, so the work goes as fast as possible but in pieces that survive
a cut-off (Johan's decision 2026-10-10):
- The scheduled task fires every hour. Only one run at a time: a run first checks (without cloning) the last `Run:`
  line and the latest commit on `media3` and every `m3/*` branch; STARTED < 25 min ago or a commit < 20 min ago means
  another run is working, so it stops at once.
- Fast pace: a run keeps working step after step until the work is done, its context gets long, or the usage cap
  stops it. Commit and push after every meaningful change (never more than ~15 minutes uncommitted) and keep
  `Doing now: **Stage 3 done** (m3/stage3 merged into media3, CI green run 38235470708, independent review fixed). Model of
the scheduled task is now Sonnet 5.5 (Johan, to save usage). Next action: 4a: read FORK_CHANGES.md rows for
ExoPlayerImplInternal/MediaPeriodQueue, ShortsQueue and VideoLoaderController; write the stage 4 step list into
PLAN.md/PROGRESS.md (DefaultPreloadManager design); then implement on m3/stage4.

Run: FINISHED 2026-10-12T10:25Z

## Stage 3 steps
- 3a. Cronet: the root build.gradle forces `cronet-api` 105 (for cronet-okhttp); check what media3-datasource-cronet
  1.11.1 needs (API calls that don't exist in 105 → NoSuchMethodError at runtime) and fix the force or the data source.
- 3b. Subtitles: the fork's WebVTT fixes (HTML entities, 10 s cue cap) as a wrapping SubtitleParser.Factory for SABR
  and DASH; check that captions show (SubtitleManager, CueGroup).
- 3c. Track selection: TrackSelectorManager/RestoreTrackSelector behaviour vs 2.10 (quality restore, audio language,
  no dubbed audio by default, `getSelectedFormat()` instead of `getFormat(0)` for the quality label).
- 3d. LeanbackPlayerAdapter no-surface fix (JoTube), seek-end event timing (onSeekEnd now at seek start), the dead
  settings (audio sync fix, AmazonQuirks): hide or note them.
- 3e. DashDefaultLoadErrorHandlingPolicy: use Media3's default for cleartext/416 (review NIT 6); OOM path check.
- 3f. Independent review of stage 3, fixes, merge.
Notes from the 2c review: played Shorts are no longer kept in the player (Media3 releases played periods; the back
buffer only covers the current Short) → stage 4 (DefaultPreloadManager). OOM: Media3's Loader turns an
OutOfMemoryError into UnexpectedLoaderException (cause OOM), which ErrorFixerController still catches; an OOM on the
playback thread itself is not caught (same as a crash in 2.10 for that thread).

Run: STARTED` without FINISHED finishes what `Doing now:` describes first and logs "cut off".
- API tasks (claude-task workflow) run in parallel with the plan's usage, at most one at a time; queue the next one
  before a run ends.

Doing now: 2c on m3/stage2 (CI green at 4f8d2cf). API review (claude-task/m3-stage2-review) found no BLOCKER, 3
SHOULD-FIX; claude-task/m3-stage2-fixes has fixes 1+2. Next action: verify those fixes against Media3 sources, merge into
m3/stage2, fix SHOULD-FIX 3 (MediaSession metadata refresh) or defer it to stage 3, CI green, merge m3/stage2 into media3.

Run: STARTED 2026-10-12T09:25Z

## API credits (Claude Console, used only by the claude-task workflow)
Johan had 14.00 USD left on 2026-10-10. Budget for this project: 12.00 USD in total (2.00 USD stays as a reserve).
Each claude-task run has a hard cap in `.claude-task/BUDGET_USD` (at most 1.50 USD); its real cost is in
`.claude-task/COST_USD` on the task branch. Add every run here.

| Date | Task branch | Budget | Cost (USD) | Result |
|------|-------------|--------|------------|--------|
| 2026-10-10 | claude-task/m3-renderers | 1.50 | 0.55 | Renderer group ported (8 files), reviewed against Media3 sources, 2 fixes (getMaxInputSize, unsafe audio sync reflection -> no-op), merged into m3/stage2 |

| 2026-10-12 | claude-task/m3-stage2-review | 1.50 | 1.03 | Independent review of 2c: no BLOCKER, 3 SHOULD-FIX (connect error message, Shorts back buffer, MediaSession metadata), 6 NIT |
| 2026-10-12 | claude-task/m3-stage2-fixes | 1.20 | 0.18 | SHOULD-FIX 1+2 implemented (not built) |
| 2026-10-12 | claude-task/m3-subtitles | 1.50 | 0.31 | 3b: last cue + padded background taken; entity decode + 10 s timer redone at parse time in review (Media3 drops unknown entities) |

Spent so far: 2.07 USD

## Run log
- 2026-10-10: branch `media3` created from master (j66, 997b69a). Package org.smarttube.johan.media3, app
  "JoTube M3", CI build-media3.yml (pre-release latest-media3), claude-task.yml with a budget cap and cost logging,
  PLAN.md, this file, CLAUDE.md. Scheduled runs set up (every 5 hours, at the usage resets).
- 2026-10-10: 1a done: `docs/media3/INVENTORY.md` (36 app files + doubletapplayerview, grouped by feature with
  Media3 counterparts; unsure mappings marked (?) for 1d). Found: doubletapplayerview also depends on the fork; no app
  use of the ffmpeg/vp9/opus/flac extensions; MediaSessionConnector and ControlDispatcher have no direct Media3
  counterpart. Docs only, no CI build needed.
- 2026-10-10: 1b done: `docs/media3/FORK_CHANGES.md`. Diffed the fork against the real base
  (amzn/exoplayer-amazon-port, branch amazon/r2.10.6): 40 files, ~2.1k lines. The Shorts preload changes
  (ExoPlayerImplInternal, MediaPeriodQueue, PlayedPeriodsPolicy) are replaced by DefaultPreloadManager; to port:
  DashManifestParser2, zoom AspectRatioFrameLayout, HDR10+ ColorInfo fix, SABR Format fields (isDrc,
  lastModified); headers in DataSpec are built into Media3; Amazon quirks and offline changes dropped. Docs only.
- 2026-10-10: 1c done: `docs/media3/SABR_PORT.md`. The SABR module is a DASH-module copy with UMP-over-POST; the
  extractor-adapter pattern (SabrExtractorInput inside FragmentedMp4/Matroska extractors) ports 1:1 to Media3's
  BundledChunkExtractor. Hand work: ChunkSource/MediaPeriod/MediaSource signatures (LoadingInfo, ExoTrackSelection,
  DRM dispatchers, MediaItem), error policy. Proposals: drop the live-only emsg/event code (~470 lines), carry
  isDrc/lastModified in SabrFormatMetadata, shouldCancelLoad must never cancel a SABR POST. Docs only.
- 2026-10-10: 1d done: Decisions in PLAN.md. CI probes on m3/probe-* branches: Media3 1.11.1 needs compileSdk 36,
  1.5.1 needs 35, 1.4.1 builds with today's AGP 7.4.2/compileSdk 34. Chosen: 1.11.1 after a toolchain upgrade (AGP 8.x,
  compileSdk 36, Kotlin 2.x, targetSdk pinned to 34), fallback 1.4.1; minSdk 23; no decoder extensions; stage 2 on a
  work branch. CI now also builds `m3/**` branches without publishing. Probe branches stay (runs can't delete
  branches: the proxy blocks it); they are harmless, Johan may delete `m3/probe-*`.
- 2026-10-10: **Stage 1 done.** Independent review agent: no blockers; 5 SHOULD-FIX folded into the docs (non-Java
  exoplayer2 references in layouts/manifest/proguard/dimens, root build.gradle version forces that would downgrade
  Media3 deps, AGP 8 rejects manifest `package=` → submodule patches needed, SABR subtitles need extraction-time
  parsing or legacy decoding in Media3, minSdk 23 scope incl. doubletapplayerview/media3-sabr), 2 NITs (tunneling
  code, @UnstableApi opt-in).
- 2026-10-10: 2a done (branch m3/toolchain, merged): AGP 8.13.0, Gradle 8.14.3, Kotlin 2.2.0, compileSdk 36 for all
  modules, stjohan minSdk 23 / targetSdk 34. Submodules untouched: the root build.gradle derives each module's
  namespace from its manifest and feeds AGP package-free manifest copies (in .gradle/m3-manifests), sets compileSdk in
  finalizeDsl, buildConfig on, Kotlin jvmTarget 1.8; gradle.properties keeps transitive R + final res ids; filepicker-lib
  archiveClassifier. CI green (runs 38069345493, 38070025122); CI now prints the APK's package/SDK levels as notices
  (seen: org.smarttube.johan.media3, targetSdk 34, compileSdk 36). Review agent: no blockers; fixed the SHOULD-FIX
  (manifest copies were under build/, lost on `clean`) and NITs.
- 2026-10-10 (run 16:19-17:40Z): 1c, 1d, stage 1 review, 2a done; 2b batch 1 done: new module `media3-sabr`
  (package com.liskovsoft.media3.sabr, Media3 1.11.1, protobuf-javalite 3.25.5, minSdk 23), built by CI next to the
  app (`:media3-sabr:assembleRelease`). Rename tool `docs/media3/tools/m3_rename.py`. Hand changes: extractor adapters
  delegate to Media3 extractors (MatroskaExtractor.read is final in Media3), Format.Builder in SabrManifestParser,
  isDrc/lastModified via SabrFormatMetadata helpers, ParsableByteArray.getData(), no InterruptedException. CI green.
- 2026-10-10 (run 17:35-18:10Z): **2b done** (branch m3/sabr, merged): batch 2 ported SabrChunkSource,
  DefaultSabrChunkSource (shouldCancelLoad never cancels, Media3 LoadErrorHandlingPolicy track fallback after the CDN
  fallback, DataSpec.Builder, BundledChunkExtractor), SabrMediaPeriod (rewritten: primary track groups only, no
  emsg/event/CEA-608 streams; the 2.10.6 code never created them) and SabrMediaSource (MediaItem, DRM dispatchers,
  plain Factory with createMediaSource(manifest[, mediaItem])). Subtitles decided: parsed during extraction
  (SubtitleExtractor → application/x-media3-cues, like Media3 DASH); the fork's WebVTT fixes (HTML entities, 10 s cue
  cap) still to re-check in stage 3 (a wrapping SubtitleParser.Factory). Caption load errors never switch the SABR CDN.
  The 3 unit tests ported (20 tests) and run in CI with counts as notices. Review agent: no blockers; SHOULD-FIX
  (subtitles, tests) and NITs (EMPTY chunk iterators, crypto type, gradle comment) fixed. CI green. Notes for stage 4
  from the review (unchanged 2.10 behaviour): one SabrStream per manifest shared by old/new loaders on re-selection,
  UMPDecoder turns network IOExceptions into IllegalStateException, SabrExtractorInput.init keeps stale data after a
  cancelled load.
- 2026-10-10 (same run): 2c started on m3/stage2: gradle swap to Media3 1.11.1 + :media3-sabr, m3_rename.py extended
  (app classes, dash/hls/ss packages) and run over the 40 app files, ZoomAspectRatioFrameLayout (fork zoom + no
  vertical stretch) in common, layout/manifest/multidex references moved. First CI: 96+ compile errors (expected),
  CI error reporting improved. Run ended to hand over with a fresh context.
- 2026-10-10 (run 18:18Z): cut off (STARTED without FINISHED; no new commits after the run-started marker). Run 20:15Z
  continues 2c from `Doing now:`. The 20:15Z run could not re-arm the scheduled task (update_trigger denied by the
  permission classifier); the daily watchdog re-arms the chain.
- 2026-10-12 (run 09:25Z): found two API tasks from an earlier run that never logged them (review + fixes, queued
  ~08:06Z); logged their cost. Usage guard section repaired (it had the Doing-now text pasted into it). Review fix 1
  corrected (Media3's InvalidResponseCodeException is TYPE_OPEN: a 403 would have switched the engine), fix 3 done
  (SessionPlayer.notifyMediaMetadataChanged on setVideo; verified the session only re-reads metadata on
  onMediaMetadataChanged). CI green (run 38229710435). **Stage 2 done**: m3/stage2 merged into media3.
- 2026-10-12 (same run): **Stage 3 done.** 3a: CI "API check" (docs/media3/tools/api_check.py): 186 Media3 refs into
  cronet-api 105 / core 1.9.0 / annotation 1.1.0, none missing (cronet 46 refs checked). 3b: subtitles: last cue only,
  padded background; entity decoding + 10 s cap per cue at parse time in media3-sabr JoSubtitleParserFactory (also DASH,
  HLS); unit tests. 3c: track selector without the audio channel constraint (no Context). 3d: JoLeanbackPlayerAdapter
  (copy with the no-surface fix, restarts progress when the surface returns). 3e: retry delay = Media3 default. 3f review
  agent: no blockers, 3 SHOULD-FIX fixed. Known no-ops: audio sync fix, AmazonQuirks settings (Media3 has no equivalent).
- 2026-10-10 (run 20:15Z): Media3 1.11.1 sources are readable through the proxy with
  `git clone --depth 1 --branch 1.11.1 --filter=blob:none --sparse https://github.com/androidx/media /home/claude/media3src`
  + `git sparse-checkout set libraries/exoplayer libraries/exoplayer_dash libraries/common libraries/datasource
  libraries/datasource_okhttp libraries/session libraries/ui libraries/ui_leanback gradle` (raw.githubusercontent, codeload,
  dl.google.com and maven.google.com are blocked, so no local Android build). All 2c ports were checked against them.
