# Task: find the next speed-ups for JoTube (analysis only, no code changes)

Repo: personal fork of SmartTube, an Android TV YouTube client. Read `git log --oneline -40` first: all "JoTube" commits
are recent speed/UX work (Shorts queue + preloading in ExoPlayerController/ShortsQueue/VideoLoaderController,
FastStartLoadControl, SABR init shortcut, decoder reuse, focus prefetch in BrowsePresenter, format-info cache and
solver runtime reuse in patches/MediaServiceCore/0001..0006 — MediaServiceCore here is already patched with them).

Measured on the target device (Google TV Streamer, Android 14, MediaTek):
- Regular video, info prefetched on hover: click → player opens ~0.2 s; SABR source open → first frame ~1.2 s.
- Regular video, not prefetched: format info 2.5–4.5 s (before the solver-runtime fix; expected ~1–2 s now).
- Shorts: switch to a preloaded Short → first frame 0.2–0.45 s; the next Shorts' info is prefetched one at a time.
- Shorts feed continuation, reel details, browse rows: not measured.

## What to do
Trace the two hot paths end to end in the code and list concrete further optimizations, ranked by expected gain/risk:
1. Click on a regular video in a grid → first video frame (activity/fragment creation, player creation, format info,
   SABR requests, ExoPlayer buffering thresholds, decoder setup, UI work on the main thread).
2. Entering the Shorts section → first Short playing, and each swipe → next Short playing (feed load, reel details,
   prefetch chain, playlist buffering — ExoPlayer 2.10 only loads the next playlist item after the current one is fully
   buffered —, transition overlay).
For each item: where in the code (file:line), what happens now, the proposed change, expected time saved, risk (403s,
wrong video, memory), and effort. Point out anything that runs on the main thread and can block it. Also note anything
that looks like a bug.
Write it all to .claude-task/REPORT.md (no code changes in this task).
