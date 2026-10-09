# Task: real media preloading of the next Shorts + a playback stall (JoTube, ExoPlayer 2.10 fork)

Repo: personal SmartTube fork (Android TV). Player: ExoPlayer 2.10.6 fork in `exoplayer-amzn-2.10.6/` (core + `library/sabr`
for YouTube's SABR streaming). Shorts are played as one playlist: `common/.../exoplayer/controller/ShortsQueue.java` +
`ExoPlayerController.java` (openShortsAware, enqueueShortInt appends sources to a ConcatenatingMediaSource,
switchToQueued = seekToDefaultPosition(index)), load control `common/.../exoplayer/other/FastStartLoadControl.java`
(Shorts: start after 0.5 s, keep loading up to 180 s / 128 MB, retains played periods via PlayedPeriodsPolicy — find
that interface/its use in the fork's ExoPlayerImplInternal/MediaPeriodQueue). Read `git log --oneline -40` first.

## Evidence from the device log (Google TV Streamer), user swipes every 2–5 s, Shorts are 60–105 s long
- The format info of the next 4 Shorts is fetched and their sources are enqueued ("Shorts: preloading X (+n)") well
  before the swipe. But the SABR log shows each Short's media requests start only AFTER the switch:
  switch 18:54:40.358 → `Load init chunk: track=2, rn=0` 40.531 → first frame 40.925;
  switch 42.436 → init 42.647 → first frame 43.183; switch 45.283 → init 45.410 → first frame 45.875.
  So nothing of the next Short is buffered: ExoPlayer 2.10 only starts loading the next playlist period when the
  current one is fully buffered, which never happens when the user swipes after a few seconds.
  (Switch → first frame is 190–290 ms when it was preloaded, 550–820 ms when not.)
- A stall: after switching to index 7 (18:54:45.283), loads for the new item: video `rn=2 startTimeMs=0` 45.668,
  audio rn=3 45.822, audio `rn=4 startTimeMs=10001` 46.113, then NO more chunk loads at all; playback stopped at
  position ~11 s (AudioTrack pause 18:54:57.5), and the app's watchdog restarted the engine at 18:55:16.7. Before that,
  index 6 had loaded fine (chunks up to 31.7 s video / 30 s audio).

## What to do
1. Explain from the code why loading stops (stall) in that situation. Candidates: which MediaPeriodHolder is the
   loading period after a seek into the playlist while later periods were enqueued; FastStartLoadControl's byte cap
   with retained played periods (shouldRetainPlayedPeriods / back buffer 10 min) blocking shouldContinueLoading;
   anything in the SABR chunk source. Fix it.
2. Make the start of the next 1–2 queued Shorts load while the current one plays, without the current one stalling:
   e.g. in Shorts mode let the queue enqueue the next period once the loading period has ≥ ~8 s buffered ahead of the
   playback position (not only when fully buffered), and make sure the playing period continues loading when its own
   buffer gets low (ExoPlayer 2.10 only calls continueLoading on the loading period: you may need to call it on the
   playing/earlier holder too, or alternate). Limit each queued Short's preload to its first ~5–8 s. Keep the regular
   (non-Shorts) behavior exactly as it is (gate everything on a Shorts flag the app sets, e.g. via the existing
   PlayedPeriodsPolicy-style hook in the load control).
3. Keep changes as small and contained as possible, commented `// JoTube:`, Java 7/8 compatible. No new deps.
You can't build or run here. Be very careful with ExoPlayer's internal invariants (period queue order, renderer
stream switching, holder release). In REPORT.md: the stall cause, the design, why it's safe, and what to look for in logs.
