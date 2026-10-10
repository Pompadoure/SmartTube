# JoTube M3 progress

Status: ACTIVE
Next step: stage 1 review (independent agent), then 2a (toolchain upgrade: AGP 8.x, compileSdk 36, Kotlin 2.x; PLAN.md Decisions 3)

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

Run: STARTED 2026-10-10T16:19Z
Doing now: 1d done (Decisions in PLAN.md). Next action: independent review agent over the stage 1 docs (INVENTORY, FORK_CHANGES, SABR_PORT, Decisions), fix findings, mark stage 1 done; then 2a.
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
  work branch. CI now also builds `m3/**` branches without publishing. Probe branches deleted.
