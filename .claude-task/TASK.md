# Independent review of JoTube M3 stage 2c (Media3 port of the app's player path)

You are an independent reviewer. You did not write this code. Repo: JoTube (Johan's Android TV YouTube app, a
SmartTube fork). This branch is `m3/stage2`: the app moved from a forked ExoPlayer 2.10.6 to AndroidX Media3 1.11.1
plus a ported SABR module `:media3-sabr` (SABR = YouTube's streaming protocol; that module was reviewed already in 2b).
CI is green (the APK builds, 20 SABR unit tests pass). Read `CLAUDE.md`, `docs/media3/PLAN.md`,
`docs/media3/PROGRESS.md`, `docs/media3/INVENTORY.md` and the other files in `docs/media3/` first.

Review the app-side changes of 2c: `git diff origin/media3...HEAD -- common smarttubetv doubletapplayerview` (use
`git log origin/media3..HEAD --stat` to see the commits; if origin/media3 isn't available use `git log -15` and the
commits starting with "M3 2c"). Do NOT edit any source file. Only write `.claude-task/REPORT.md`.

Look for, in this order:
1. Behaviour lost or changed in the port (compare each changed method with its old ExoPlayer 2.10 version in the diff):
   track selection, the SABR/DASH source setup in ExoMediaSourceFactory, load error handling/retry delays, renderers
   (decoder reuse, tunneling off, frame rate), the player controller and listeners (first frame, errors ->
   ErrorFixerController/TrackErrorFixer, onPlayEnd, position discontinuity), PlaybackFragment / leanback glue /
   ForwardingPlayer replacing the queue navigator and control dispatcher, MediaSession, subtitles, the Shorts queue
   (ShortsQueue, FastStartLoadControl, PlayedPeriodsPolicy) and anything stubbed out or turned into a no-op.
2. Media3 API misuse you are sure about (wrong thread, listener never removed, Player methods called on a released
   player, deprecated-and-changed semantics, e.g. ConcatenatingMediaSource vs playlist API, Player.Listener event order).
3. Anything that could make video start slower than before, or break the rule "never fetch format info in parallel and
   never cancel such a fetch half-way".

Write `.claude-task/REPORT.md`: a list of findings, each with file:line, severity (BLOCKER / SHOULD-FIX / NIT), what
breaks for the user (concrete scenario) and the suggested fix. Mark anything you are not sure about as "UNVERIFIED"
(you cannot read the Media3 sources here). End with a short list of what you checked and found fine. Be concise.
