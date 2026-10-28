# Main Thread Audit: Shorts Switch

This document lists everything that runs ON THE MAIN THREAD when a Shorts swipe happens in the TV app, so we can move it off the critical 450 ms slide window.

## A) Live switch (VideoLoaderController.java:243 -> startShortsLiveSwitch)
Path: VideoLoaderController (common/.../playback/controllers/VideoLoaderController.java, the Shorts next/prev handling that calls getPlayer().startShortsLiveSwitch) -> PlaybackFragment.startShortsLiveSwitch -> ShortsLiveSwitch.start / startSlide / onSlideEnd -> the `open` runnable (openVideoInt) -> PlaybackFragment.resetPlayerState -> ExoPlayerController.adoptPlayer -> PlaybackFragment adopt/onAdopted.

### Steps
1. **ShortsLiveSwitch.start / startSlide** (ShortsLiveSwitch.java:244 / 274)
   - **What it does:** Sets up `ValueAnimator` for the slide, calculates aspect ratios, calls `ShortsSidePanel.setSlideAside(true)` which animates avatar/margins.
   - **Cost/Work:** UI view manipulation, layout, starting animators. (No disk/network).
   - **Timing:** Before slide.

2. **ShortsLiveSwitch.onSlideEnd** (ShortsLiveSwitch.java:319)
   - **What it does:** Listener on `ValueAnimator` completes, updates `ShortsSidePanel` back, and posts the open runnable.
   - **Cost/Work:** UI state update.
   - **Timing:** After slide.

3. **openVideoInt** (VideoLoaderController.java:661)
   - **What it does:** Calls `getMainController().onNewVideo(item)`.
   - **Cost/Work:** Triggers controllers.
   - **Timing:** After slide.

4. **Controllers onNewVideo**
   - **SuggestionsController** (SuggestionsController.java:75): Cancels previous network actions. (Cost: Cheap).
   - **VideoStateController** (VideoStateController.java:55): Cancels tickles, calls `saveState()` which accesses the database. (Cost: Disk DB I/O!).
   - **RemoteController** (RemoteController.java:62): Updates connected state. (Cost: Cheap).
   - **ErrorFixerController** (ErrorFixerController.java:111): Resets buffering detector. (Cost: Cheap).
   - **PlayerUIController** (PlayerUIController.java:109): Resets button states/hide timeout. (Cost: Cheap).
   - **VideoLoaderController** (VideoLoaderController.java:122): Playlist updates. (Cost: Cheap).
   - **SponsorBlockController** (SponsorBlockController.java:88): Clears segment data. (Cost: Cheap).
   - **ChatController** (ChatController.java): Disposes old actions. (Cost: Cheap).
   - **CommentsController** (CommentsController.java): Disposes old actions. (Cost: Cheap).
   - **TrackingService**: (Cost: Cheap).
   - **DeArrowController**: (Cost: Cheap).
   - **Timing:** After slide.

5. **PlaybackFragment.resetPlayerState** (PlaybackFragment.java:2156)
   - **What it does:** Validates direction, updates `ShortsBackground` via `Glide`, triggers live switch logic.
   - **Cost/Work:** Skips heavy Glide load because it's a live switch, calls `adoptLiveShort`.
   - **Timing:** After slide.

6. **PlaybackFragment.adoptLiveShort** (PlaybackFragment.java:751)
   - **What it does:** Swaps players and listeners, updates MediaSession, view surface swap.
   - **Cost/Work:** Heavy Binder IPC via `mMediaSession.setPlayer(sessionPlayer)`.
   - **Timing:** After slide.

7. **ExoPlayerController.adoptPlayer** (ExoPlayerController.java:204)
   - **What it does:** Sets ExoPlayer, invalidates TrackSelector, calls `onSourceChanged`.
   - **Cost/Work:** Internal state updates.
   - **Timing:** After slide.

8. **Controllers onSourceChanged**
   - **VideoStateController** (VideoStateController.java:308): Restores formats, calls `applyStartPosition()` which looks up state from DB. (Cost: Disk DB I/O!).
   - **Timing:** After slide.

9. **ShortsLiveSwitch.onAdopted / onMainFirstFrame** (ShortsLiveSwitch.java:537 / 558)
   - **What it does:** Hides the spare frame, hands over player, finishes swap.
   - **Timing:** After slide.


## B) Usual switch (without live switch)
Path: the usual switch (openVideoInt without live switch) including ShortsTransitionOverlay.swipe.

### Steps
1. **openVideoInt** (VideoLoaderController.java:661)
   - **What it does:** Calls `onNewVideo` on controllers.
   - **Cost/Work:** Same as A4 above (VideoStateController does Disk DB I/O).
   - **Timing:** Before slide.

2. **Controllers onSourceChanged** (Via ExoPlayerController)
   - **What it does:** VideoStateController calls `applyStartPosition()`.
   - **Cost/Work:** Disk DB I/O!
   - **Timing:** Before slide.

3. **PlaybackFragment.resetPlayerState** (PlaybackFragment.java:2156)
   - **What it does:** Updates `ShortsBackground`.
   - **Cost/Work:** Begins `Glide` loads for thumbnails/covers if not cached.
   - **Timing:** Before slide.

4. **ShortsTransitionOverlay.swipe** (ShortsTransitionOverlay.java:493)
   - **What it does:** The slide animation itself.
   - **Cost/Work:** Sets up and animates `ValueAnimator` moving views/alpha.
   - **Timing:** SLIDING (~420 ms).

5. **Controllers onVideoLoaded**
   - **SuggestionsController** (SuggestionsController.java:87): Calls `loadSuggestions()` doing a network request and JSON parsing. (Cost: Network/Glide/JSON I/O!).
   - **VideoStateController** (VideoStateController.java:227): (Cost: Cheap).
   - **RemoteController** (RemoteController.java:80): Syncs position. (Cost: Cheap).
   - **PlayerUIController** (PlayerUIController.java:359): Updates UI. (Cost: Cheap).
   - **VideoLoaderController** (VideoLoaderController.java:167): Schedules prefetch. (Cost: Cheap).
   - **SponsorBlockController** (SponsorBlockController.java:96): Checks DB/Network. (Cost: Network/DB).
   - **AutoFrameRateController** (AutoFrameRateController.java:74): Applies AFR. (Cost: Hardware display change).
   - **Timing:** After slide (when video is loaded).


## Candidates to defer until after the slide
1. **`SuggestionsController.onVideoLoaded` (`loadSuggestions`)**: Network request and JSON parsing for suggestions; heavy, not needed immediately.
2. **`VideoStateController.onNewVideo` (`saveState`)**: Database I/O to save watch history of the previous video.
3. **`VideoStateController.onSourceChanged` (`applyStartPosition`)**: Database I/O to fetch playback position.
4. **`PlaybackFragment.adoptLiveShort` (`mMediaSession.setPlayer`)**: MediaSession Binder IPC; can block the main thread.
5. **`ShortsBackground` (Glide Loads)**: If not preloaded, starting Glide loads during `resetPlayerState` can delay the start of the slide animation.

## Must stay synchronous
1. **`ExoPlayerController.adoptPlayer` & Listener Swapping**: Handing over the ExoPlayer instance and video surface must be synchronous to avoid visual glitches, desync, or black frames.
2. **Slide Animation setup (`ShortsLiveSwitch.start`, `ShortsTransitionOverlay.swipe`)**: Must happen immediately to respond to user input without perceived latency.
3. **`VideoLoaderController.openVideoInt`**: Core state tracking must happen predictably so controllers know what video is active.
