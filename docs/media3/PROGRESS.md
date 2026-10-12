# JoTube M3 progress

Status: WAITING_FOR_JOHAN
Next step (LOG ARRIVED 2026-10-12 17:31Z, see 'BUG-1 EVIDENCE' at the end; fix it. Old text: WAITING_FOR_JOHAN: install the new latest-media3 build, play a regular video once, say so in a commit/note; then the run reads logcat via HA, grep JoTubeSabr and fixes BUG-1): BUG-1 (audio restarts at video start, see "Bug reports from device tests" below). Johan installed JoTube[beta] (org.smarttube.johan.media3, armeabi-v7a build) on the Streamer 2026-10-12 16:00 and is testing; fix bugs he reports, then set DONE again.

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
  `Doing now: **All stages done.** Stage 5 reviewed (1 agent, 0 blockers, 2 should-fix fixed, CI green run 38250273348), stage 6 docs written. Nothing left; the scheduled task can be turned off.

Doing now: **Stage 4 done** (m3/stage4 merged into media3, CI green run 38239575727). Next action: 5a: walk the PLAN.md checklist (SponsorBlock, speed, auto frame rate, resume, history, SmartNext, live DASH/HLS, PIP/background, Shorts side panel/history/prefetch/sidebar/hover prefetch, FormatFetchLock) against the code; list what still references removed 2.10 behaviour (grep for TODO M3 / 'M3:' notes), fix each, add JoTubeTiming logs for the Shorts switch and regular start.

Run: FINISHED 2026-10-12T10:25Z
Run: STARTED 2026-10-12T10:34Z (manual start by Johan)
Run: FINISHED 2026-10-12T11:25Z
Run: STARTED 2026-10-12T11:43Z (manual start by Johan, after a skipped fire)
Run: FINISHED 2026-10-12T12:02Z
Run: STARTED 2026-10-12T12:22Z (scheduled fire)
Run: FINISHED 2026-10-12T12:45Z (stage 5 and 6 done, Status DONE)
Run: STARTED 2026-10-12T15:02Z (manual fire by Johan, BUG-1)
Run: FINISHED 2026-10-12T15:15Z (BUG-1 diagnostics merged, CI green run 38267393403; waiting for a device log)

## Stage 4 steps (design from 4a, 2026-10-12)
Findings: today `ShortsQueue` keeps all Shorts as items of one `ConcatenatingMediaSource` and switching is a seek in the
playlist (`ExoPlayerController.switchToQueued`, retry loop while the timeline is out of sync, `scheduleItemEnd` message per
window, `handleQueueAutoAdvance`). Media3 releases played periods and only preloads the next item with its default, so
this design is replaced. Media3 1.11.1 `DefaultPreloadManager` (verified in the sources): `add(MediaSource, Integer rank)`
takes our own SABR/DASH `MediaSource` (no MediaItem factory hack needed, mediaId = videoId); `getMediaSource(MediaItem)`
returns the `PreloadMediaSource` to play with `player.setMediaSource(..)` + `prepare()`; `TargetPreloadStatusControl` gives
the status per rank (`PreloadStatus.specifiedRangeLoaded(0, ms)`); `setCurrentPlayingIndex` + `invalidate()`;
the player must come from `DefaultPreloadManager.Builder.buildExoPlayer(ExoPlayer.Builder)` (shared playback looper,
renderers factory, load control, bandwidth meter and a `TrackSelector.Factory`, which is called for the player and again
for the preload manager).
- 4b. Player construction: `ExoPlayerInitializer.createPlayer` builds through the preload manager builder (renderers
  factory, FastStartLoadControl, bandwidth meter). Open question: TrackSelectorManager owns ONE DefaultTrackSelector;
  the factory is called twice, so the preload selector needs the same parameters (mirror setParameters) — decide from
  the sources. Regular videos must behave exactly as before.
- 4c. `ShortsQueue` becomes a ranked list (videoId, formatInfo, source, added time) feeding the preload manager:
  `add(source, index)`, TargetPreloadStatusControl: current+1 = loaded 6 s, current+2 = loaded 3 s, current-1 kept
  (for the swipe back), the rest NOT_PRELOADED; max size and removal of far items (`remove`) to bound memory.
  `enqueueShort(formatInfo, distance)` keeps its signature (VideoLoaderController unchanged).
- 4d. Switching: `switchToQueued` = `setCurrentPlayingIndex`, `player.setMediaSource(preloadManager.getMediaSource(item))`,
  `prepare()`, `play`. No timeline sync/retry loop, no `handleQueueAutoAdvance`; `scheduleItemEnd` works on window 0 of the
  single-item timeline (or Player.STATE_ENDED / position check).
- 4e. App hooks that assumed one playlist: `ShortsQueue.isPlayerOn(videoId, windowIndex)`, `onTracksChanged` sync,
  `TrackSelectorManager.onQueuedSourceSwitch`, duration update, release/reset on leaving Shorts (`setActive(null)` →
  `preloadManager.reset()`), `isQueued/getUpcomingIds/getQueuedFormatInfo` semantics.
- 4f. Codec reuse across Shorts (`canReuseCodec`, `getCodecMaxValues` in the Shorts renderer), tunneling off for Shorts,
  re-check the ChunkSampleStream "seek to clipped start" need (FORK_CHANGES A) with the PreloadMediaSource start position.
- 4g. Remaining: swipe transition / side panel / 9:16 frame / blurred background glue still works with the new events
  (ShortsTransitionState), JoTubeTiming logs for switch time; unit tests where possible.
- 4h. CI green, independent review of the stage 4 diff, fixes, merge m3/stage4 into media3.

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
- 2026-10-12 (run 10:34Z, manual start): **Stage 4 done.** Design from the Media3 sources: DefaultPreloadManager.add(MediaSource, key) with our SABR/DASH sources; the player is built via DefaultPreloadManager.Builder.buildExoPlayer (preload track selector = 2nd RestoreTrackSelector, same TrackSelectorManager callback); ShortsQueue = ranked entries with order-preserving gap keys; ShortsPreloadControl next 6 s / +2 3 s / previous 3 s; switch = setMediaSource(preloaded source), no timeline sync/retry/auto-advance. Independent review: 1 BLOCKER (switch set the playing Short to NOT_PRELOADED, which clears its preload before the player takes it: now loaded status for distance 0 and setCurrentIndex after setMediaSource), 2 SHOULD-FIX (preload priority comparator by feed distance, preload selector mirrors parameters) all fixed. Not measurable without a device: real switch time, codec reuse, first-frame event per Short (check in device logs when Johan installs).


## Bug reports from device tests (Johan, Google TV Streamer, JoTube[beta] = media3 branch, build 32.56-m3-94)
Confirmed on device: logcat shows `ExoPlayerImpl: Init [AndroidXMedia3/1.11.1]` and `MediaSessionImpl [AndroidXMedia3/1.11.1]`;
the Streamer is 32-bit (abilist armeabi-v7a,armeabi), so use the JoTubeM3_armeabi-v7a.apk.

**BUG-1 (every regular video, seen on 2 videos in a row):** at the start of the video the picture freezes and the
audio jumps back and plays again from the beginning; once the audio has caught up, the video starts and plays on normally.
Logcat (16:54:05.189, tag MediaCodecAudioRenderer, "Audio sink error"):
`AudioSink$UnexpectedDiscontinuityException: Unexpected audio track timestamp discontinuity: expected 1000009993500, got 1000000000000`
(from DefaultAudioSink.handleBuffer). 1000000000000 us is Media3's renderer timestamp offset, so the audio delivered
~9.99 s of samples and then restarted at position 0 of the period: the audio sample stream is re-read from the start
(or its timestamps are reset) after ~10 s were already queued, while video waits. Suspects: the SABR media source /
media3-sabr (audio segment request restarts from 0 after the first response, e.g. initial seek or start position applied
only to video, wrong timestamp offset / sampleTimeOffset between the first and second audio response, a re-created
audio chunk source), or the start-position/resume handling. Find the cause with evidence (logs with the SABR request
sequence and timestamps per track), do not guess. The old build (j-series, ExoPlayer 2.10.6) does not have this.
Johan can read logcat via Home Assistant: androidtv.adb_command on media_player.android_tv_192_168_1_247 (write the
output to a file, read via ha_get_state attribute adb_response). Install a new build only after a green CI on media3;
Johan or the main Claude session installs it (runs never install on devices).

**BUG-1 status 2026-10-12 15:15Z:** The start of the old test is gone from logcat (ring buffer), so the cause is not proven.
Evidence so far: expected 9993500 vs got 0 means the audio sample queue received a second chunk whose first sample is at
period position 0 (a duplicate of segment 0) after ~10 s were queued. Code candidates (unproven): first media chunk after
the init chunk uses seekTimeUs=loadPosition and `sabrStream.reset(iTag)`; a later request with queue non-empty uses
`getSegmentStartTimeMs` (start of the last segment, 0 when startMs=-1) as playerTimeMs; init response media is not
skipped (SKIP_INIT_RESPONSE_MEDIA=false). Diagnostics merged (tag `JoTubeSabr`, Log.e): "next chunk:" per decision
(queueSize, loadPosUs, playbackPosUs, seekTimeUs, prev start/end), "Load media chunk:" with start/end/clipped, and
"extractor seek:" / "sample:" lines (first 3 samples after each seek, every BACKWARD timestamp jump).
Read with: androidtv.adb_command `logcat -d -v threadtime | grep -E 'JoTubeSabr|DefaultSabrChunkSource|Audio sink error' > /sdcard/m3.txt; cat /sdcard/m3.txt`
right after one regular video start (the ring buffer is small).

**BUG-1 EVIDENCE 2026-10-12 19:31 local (17:31Z), build with the 8-task port, tag JoTubeSabr, pid 5756 (Shorts click, Johan gave up after ~9 s: "no video started"):**
```
19:31:01.224 extractor seek: SabrMatroskaAdapter@222998212 (video, trackType=2) position=0 timeUs=0 seekCount=1
19:31:01.228 extractor seek: SabrMatroskaAdapter@161763757 (audio, trackType=1) position=0 timeUs=0 seekCount=1
19:31:01.653 sample: audio timeUs=0, 21000, 41000 ... (seekCount=1)  -> audio samples queued up to prevUs=9981000
19:31:01.801 next chunk: track=1 queueSize=0 loadPosUs=0 playbackPosUs=0 seekTimeUs=0 nexChunkIdx=-1
19:31:01.815 extractor seek: audio position=0 timeUs=0 seekCount=2        <-- second seek of the SAME audio extractor to 0
19:31:01.867 sample: video timeUs=0, 17000, 33000 (seekCount=1)
19:31:02.209 sample: audio timeUs=0 prevUs=9981000 BACKWARD (seekCount=2)  <-- the duplicate segment 0
19:31:02.248 MediaCodec ... then SabrExtractorInput "User doing seek? The SABR read isn't finished yet" (Johan pressed back)
```
Reading: the audio chunk source decides "next chunk" a second time with queueSize=0 and nexChunkIdx=-1 although ~10 s of audio
(segment 0) are already loaded (the first load is not in the sample queue's view yet, or the queue was cleared / the first
chunk was cancelled), so it re-requests from 0 (seekCount=2) and the stream gets a duplicate of segment 0 -> discontinuity
(expected 9993500, got 0) and video waits for the audio. Look at what clears/does not account the first audio chunk
(chunk source `getNextChunk` with queue empty right after the first load; `sabrStream.reset(iTag)` on a second call;
startMs=-1 -> loadPosition 0). Not a codec problem. No video appears for >9 s on every start now (Johan could not wait).

