# Shorts role swap (design, 2026-10-24)

Johan's decision 2026-10-24 21:20: replace the two-player "live switch with take-over" by a role swap.

## Why (device evidence, Streamer, 2026-10-24)
- During a live switch the visible (second) player dropped 50 frames in 1-2 s and its sound ran dry (underruns,
  up to 1.5 s without a feed) exactly while the main player loaded and seeked the same Short (LOADING/SYNCING).
- 720p cap (94a2695e) lowered the frame drops (50 -> 7-16) but not the audio underruns; Johan: no improvement.
- In 9 of 17 switches (21:14-21:17) the hand-over ended silently without sync (no "Sync"/"takes over" log;
  silent paths: `onMainError` -> `swap(true)`, poll null check): a hard jump.
- Even when it works the offset after the start varies +-150 ms (audible hop at the hand-over).

## Design
The player that already plays the next Short (today: `ShortsPreview`'s player) stays the player of that Short.
The core (`ExoPlayerController`, `PlaybackFragment`) is re-bound to it; the old core player becomes the spare that
prepares the next Short. No reload, no seek, no sync, no hand-over sound fade.

1. Spare player built like the core player: `CustomOverridesRenderersFactory`, `RestoreTrackSelector` selected by
   `TrackSelectorManager` rules (quality, 720p Shorts cap, AAC), `FastStartLoadControl`, audio attributes/focus, own
   playback thread (not the preload manager's: a shared thread stalls one player while the other inits a codec).
2. Surfaces stay put: at the swap the new core player gets the main surface (`setVideoSurfaceHolder`, seamless
   `setOutputSurface` on API 23+), the old one gets the spare's surface; the spare view is hidden on the first frame
   of the new core player on the main surface.
3. `ExoPlayerController.adopt(player, selector)`: move its Player.Listener/analytics listener/volume booster,
   `TrackSelectorManager.setTrackSelector`, replay state to itself (timeline, tracks, playback state, playWhenReady)
   so the core sees "loaded". `open*` of a video the spare already plays = adopt instead of setMediaSource.
4. `PlaybackFragment`: swap `mPlayer`, re-bind JoLeanbackPlayerAdapter, media session connector, YouTubeOverlay,
   first-frame/transition listeners, subtitle/debug managers.
5. Preloaded sources of `DefaultPreloadManager` play only on the preload player's looper: `switchToQueued` only when
   the core player is that player; otherwise open normally (swipe back etc.).
6. `ShortsLiveSwitch` shrinks to: slide in the spare (playing), then adopt. Delete SYNCING/held/seek/trim code.

## Steps (each: branch m3/role-swap-N, CI green, review agent, then device test with Johan)
- RS1 spare player factory in ExoPlayerInitializer + ShortsPreview uses it (no behaviour change yet).
- RS2 ExoPlayerController.adopt + PlaybackFragment re-bind; ShortsLiveSwitch uses adopt; old sync code removed.
- RS3 edge cases: swipe back, pause/seek during slide, errors, leaving Shorts, PIP/background, speed/volume.
