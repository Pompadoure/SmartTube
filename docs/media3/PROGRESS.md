# JoTube M3 progress

Status: ACTIVE
Next step: 2c (on branch `m3/stage2`: swap the app's ExoPlayer modules for Media3 + `:media3-sabr`, port the minimal player path until CI is green, then merge)

Status values: ACTIVE (the scheduled runs keep going), WAITING_FOR_JOHAN (a question to Johan, runs only check
for his answer), BLOCKED (a stage can't be made stable; reason below), DONE (stage 6 finished).

## Usage guard (no unexpected stops)
The Claude plan's usage can't be read by a run, so the work goes as fast as possible but in pieces that survive
a cut-off (Johan's decision 2026-10-10):
- Fast pace: a run keeps working step after step until the work is done, its context gets long, or the usage cap
  stops it. Commit and push after every meaningful change (never more than ~15 minutes uncommitted) and keep
  `Doing now:` below current with each push (step, what's done in it, exact next action).
- Every run first writes `Run: STARTED <time>`, at the end `Run: FINISHED <time>`. A run that finds STARTED without
  FINISHED knows the previous one was cut off: it finishes what `Doing now:` describes first and logs "cut off".
- Self-restarting runs: a run that pushed real progress and ran 10+ minutes fires the scheduled task again
  (fire_trigger) so a fresh run continues within the same usage window.
- A scheduled run starts just after each usage reset (the first run of a window re-arms it to now + 5h05m), and a
  daily watchdog re-arms the chain if it ever breaks.
- Repeated cut-offs: tell Johan.
- API credits: hard cap per claude-task run and a total budget (below). At 12.00 USD spent the runs stop using
  the API and tell Johan; the last 2.00 USD stay untouched.

Run: STARTED 2026-10-10T17:35Z
Doing now: 2b done and merged (CI run 38074051880 green, 20 media3-sabr unit tests pass). Next action: 2c, create branch m3/stage2 from media3; step 1 = gradle swap (settings.gradle drops the exoplayer-amzn-2.10.6 include; common/smarttubetv/doubletapplayerview depend on Media3 1.11.1 artifacts per PLAN Decisions 4 + project(':media3-sabr')), step 2 = run docs/media3/tools/m3_rename.py over the 36 app files + doubletapplayerview (INVENTORY.md lists them), step 3 = fix compile errors file group by file group (INVENTORY sections 1-9), CI after each group.
Cut-offs in a row: 0

## API credits (Claude Console, used only by the claude-task workflow)
Johan had 14.00 USD left on 2026-10-10. Budget for this project: 12.00 USD in total (2.00 USD stays as a reserve).
Each claude-task run has a hard cap in `.claude-task/BUDGET_USD` (at most 1.50 USD); its real cost is in
`.claude-task/COST_USD` on the task branch. Add every run here.

| Date | Task branch | Budget | Cost (USD) | Result |
|------|-------------|--------|------------|--------|

Spent so far: 0.00 USD

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
