# JoTube M3 inventory (step 1a)

Every use of `com.google.android.exoplayer2` outside the ExoPlayer fork itself (`exoplayer-amzn-2.10.6/`), grouped by
feature, with the Media3 counterpart. Counted on `media3` at 0846b5a with
`grep -rl com.google.android.exoplayer2 common smarttubetv` (36 files, 118 distinct imports) plus two fully
qualified uses without import (`DebugInfoMediaCodecVideoRenderer` → `video.ColorInfo`, `PlaybackFragment` →
`ExoPlaybackException`). MediaServiceCore has no ExoPlayer use.

Not in the original count: **`doubletapplayerview/`** (4 Kotlin files: `Player`, `ui.PlayerView`; gradle depends on
`:exoplayer-library-core` and `:exoplayer-library-ui`). It must move to Media3 too (or out of the build).

Short names below: `e2.` = `com.google.android.exoplayer2.`; "fork" = a class that exists only in
`exoplayer-amzn-2.10.6` (JoTube/SmartTube/Amazon addition, not in stock ExoPlayer). Media3 mappings marked (?) are
from knowledge of the Media3 API and are checked against the chosen version in step 1d.

## Gradle wiring today
- `settings.gradle` includes the fork via `exoplayer-amzn-2.10.6/core_settings.gradle` (library, core, dash, sabr,
  hls, smoothstreaming, ui and the extensions ffmpeg, flac, cast, cronet, mediasession, okhttp, opus, vp9,
  leanback, workmanager).
- `common/build.gradle`: `:exoplayer-library` (all), `:exoplayer-extension-okhttp`, `:exoplayer-extension-cronet`.
- `smarttubetv/build.gradle`: `:exoplayer-library-core`, `-ui`, `:exoplayer-extension-leanback`, `-mediasession`.
- `doubletapplayerview/build.gradle`: `:exoplayer-library-core`, `-ui`.
- No app code uses ffmpeg/vp9/opus/flac/cast/workmanager (the "vp9"/"opus" hits are codec preference strings only),
  so those extensions are not needed in Media3.

## 1. Player construction and the player controller
Files: `ExoPlayerInitializer` (250), `ExoPlayerController` (865), `PlaybackFragment` (1879), `EmbedPlayerView` (633),
`ExoUtils` (67), `BasePlayerController` (385, only `Format`), `PlayerEngineEventListener` (only `ExoPlaybackException`).

| Today | Use | Media3 |
|---|---|---|
| `ExoPlayerFactory.newSimpleInstance(ctx, renderers, selector, loadControl, drm=null, bandwidthMeter)` | ExoPlayerInitializer | `ExoPlayer.Builder(ctx, renderersFactory).setTrackSelector().setLoadControl().setBandwidthMeter().build()` |
| `SimpleExoPlayer` | everywhere | `ExoPlayer` |
| `DefaultLoadControl.Builder` | createLoadControl | same (`androidx.media3.exoplayer.DefaultLoadControl`) |
| `SeekParameters`, `PlaybackParameters`, `C`, `Timeline`, `Player`, `PlayerMessage` | controller | same names (`PlaybackParameters` constructor and `Player` window→media item renames: `getCurrentWindowIndex` → `getCurrentMediaItemIndex`) |
| `player.prepare(mediaSource)` | `openMediaSource` | `setMediaSource(s)` + `prepare()` |
| `player.createMessage(...)` (item-end message, Shorts) | ExoPlayerController:314 | same API (`PlayerMessage`) |
| `MergingMediaSource(dash, hls)` | live (dash + hls) | same |
| `ExoPlaybackException` in listeners | PlayerEngineEventListener, PlaybackFragment, controller | `PlaybackException` in `Player.Listener.onPlayerError`; `ExoPlaybackException` still exists as subclass |
| `VideoListener`, `AudioListener`, `TextOutput` | PlaybackFragment (first frame), VolumeBooster (audio session id), SubtitleManager | all merged into `Player.Listener` (`onRenderedFirstFrame`, `onAudioSessionIdChanged`, `onCues(CueGroup)`) |
| `audio.AudioAttributes` | audio focus | same, set via `setAudioAttributes(attrs, handleFocus)` |
| `ExoUtils`: `MediaCodecUtil`, `MediaCodecInfo`, `DecoderQueryException` | codec checks | same in `androidx.media3.exoplayer.mediacodec` |
| DRM: `DefaultDrmSessionManager`, `ExoMediaDrm.KeyRequest/ProvisionRequest`, `FrameworkMediaCrypto`, `MediaDrmCallback`, `UnsupportedDrmException` | ExoPlayerInitializer (built but passed as null to the factory) | `DefaultDrmSessionManager.Builder`, `FrameworkMediaCrypto` is gone (`CryptoConfig`). Probably dropped (YouTube needs no DRM here); decide in 1d |

## 2. Track selection and track info
Files: `TrackSelectorManager` (1109), `RestoreTrackSelector` (220), `backport/Definition` (53), `TrackSelectorUtil`
(411), `MediaTrack`/`VideoTrack`/`AudioTrack`, `ExoFormatItem` (437), `TrackInfoFormatter2` (97),
`DebugInfoManager` (595, also debug overlay).

| Today | Media3 |
|---|---|
| `DefaultTrackSelector`, `Parameters`, `SelectionOverride` | `DefaultTrackSelector` + `TrackSelectionParameters`; `SelectionOverride` still exists (deprecated) or `TrackSelectionOverride` (?) |
| `MappingTrackSelector.MappedTrackInfo` | same |
| `TrackGroup`, `TrackGroupArray`, `TrackSelectionArray`, `TrackSelection` | `TrackGroup` moved to `androidx.media3.common`; `TrackSelection` → `ExoTrackSelection`; `Tracks` for the current state |
| `TrackSelection.Definition` + the `backport/Definition` copy | `ExoTrackSelection.Definition` (the backport can go) |
| `RestoreTrackSelector extends DefaultTrackSelector` overriding `selectVideoTrack/selectAudioTrack/selectTextTrack` (protected, 2.10 signatures, `AudioTrackScore`/`TextTrackScore`) | protected hooks changed in Media3 (`selectVideoTrack(MappedTrackInfo, int[][][], int[], Parameters)` etc., `@Nullable Pair<ExoTrackSelection.Definition, Integer>`) (?); the "restore last chosen track" logic has to be re-hooked, possibly via overrides set from TrackSelectorManager instead of subclassing |
| `AdaptiveTrackSelection.Factory` | same |
| `Format` (fields `id, bitrate, width, height, frameRate, codecs, sampleMimeType, language, label, roleFlags`) | `androidx.media3.common.Format`, same fields; `Format` builders instead of `createVideoContainerFormat(...)` statics |
| `MimeTypes`, `Util` | `androidx.media3.common.MimeTypes`, `androidx.media3.common.util.Util` |
| `DecoderCounters` (debug) | same in `androidx.media3.exoplayer` |

## 3. Renderers and decoders
Files: `CustomRenderersFactoryBase` (57), `CustomOverridesRenderersFactory` (284), `TweaksMediaCodecVideoRenderer`
(120), `DebugInfoMediaCodecVideoRenderer` (161), `DelayMediaCodecAudioRenderer` (101),
`BlacklistMediaCodecSelector` (83).

| Today | Media3 |
|---|---|
| `DefaultRenderersFactory` subclass overriding `buildVideoRenderers/buildAudioRenderers` (2.10 signature with `DrmSessionManager<FrameworkMediaCrypto>`) | same class, new signatures (no DRM manager; `MediaCodecAdapter.Factory`, `AudioSink`) |
| `MediaCodecVideoRenderer` / `MediaCodecAudioRenderer` subclasses (tweaks: frame drop, vsync, delay audio, debug info) | same classes, new constructors (`Context`, `MediaCodecAdapter.Factory`, ...). Overridden methods need checking one by one in 1b |
| `DefaultAudioSink`, `AudioProcessor`, `AudioCapabilities` (VolumeBooster/audio delay) | `DefaultAudioSink.Builder`; `AudioProcessor` moved to `androidx.media3.common.audio` |
| `MediaCodecSelector` + `MediaCodecUtil` (codec blacklist) | same, `getDecoderInfos(mime, secure, tunneling)` |
| `util.AmazonQuirks` (fork: `disableSnappingToVsync`, `skipProfileLevelCheck`) | no counterpart: Amazon port only. Needed only for Fire TV; Johan's devices are Google TV Streamer and Mi Box → expected to drop (1b decides) |
| `video.ColorInfo` (HDR check) | `androidx.media3.common.ColorInfo` |
| Tunneling: off (commented `setTunnelingAudioSessionId`) | Media3 default is also off (`setTunnelingEnabled(false)`) |

## 4. Data sources and media sources
Files: `ExoMediaSourceFactory` (452), `LiveDashManifestParser` (390), `DashDefaultLoadErrorHandlingPolicy` (39).

| Today | Media3 module | Media3 |
|---|---|---|
| `DefaultDataSourceFactory`, `DefaultHttpDataSourceFactory`, `HttpDataSource`, `DataSource`, `DefaultBandwidthMeter` | media3-datasource / -exoplayer | `DefaultDataSource.Factory`, `DefaultHttpDataSource.Factory`, `DefaultBandwidthMeter.Builder` |
| `ext.okhttp.OkHttpDataSourceFactory` | media3-datasource-okhttp | `OkHttpDataSource.Factory(callFactory)` |
| `ext.cronet.CronetDataSourceFactory`, `CronetEngineWrapper` | media3-datasource-cronet | `CronetDataSource.Factory(engine, executor)`; wrapper gone (app already has `CronetManager.getEngine`) |
| `source.dash.*` (`DashMediaSource`, `DefaultDashChunkSource`, `DashChunkSource`, manifest classes) | media3-exoplayer-dash | same names |
| `dash.manifest.DashManifestParser2` (fork: SmartTube parser variant) | — | fork class, must be ported or replaced; see 1b |
| `LiveDashManifestParser extends DashManifestParser` (overrides protected builders, `SegmentTimelineElement`, `MultiSegmentBase`) | media3-exoplayer-dash | protected methods still exist but signatures changed (more params, `Format.Builder`); port by hand |
| `source.hls.HlsMediaSource` | media3-exoplayer-hls | same |
| `source.smoothstreaming.*` | media3-exoplayer-smoothstreaming | same (check if still reachable from YouTube data; may drop) |
| `ExtractorMediaSource` + `DefaultExtractorsFactory` | media3-exoplayer / -extractor | `ProgressiveMediaSource.Factory` |
| `source.sabr.*` (`SabrMediaSource`, `DefaultSabrChunkSource`, `SabrChunkSource`, `SabrManifest`, `SabrManifestParser`) | — | fork module `library/sabr`, ported in stage 2 (step 1c maps its internals) |
| `DefaultLoadErrorHandlingPolicy` subclass (2.10: `getRetryDelayMsFor(dataType, loadDurationMs, IOException, errorCount)`) | media3-exoplayer | override `getRetryDelayMsFor(LoadErrorInfo)` |
| `ParserException`, `Loader.UnexpectedLoaderException`, `InvalidResponseCodeException` | common / exoplayer / datasource | same names, new packages |

## 5. Error handling
Files: `TrackErrorFixer` (259), `PlayerEngineEventListener`, `DashDefaultLoadErrorHandlingPolicy` (above),
ErrorFixerController (no direct ExoPlayer import).

| Today | Media3 |
|---|---|
| `DefaultMediaSourceEventListener` (abstract adapter) + `MediaPeriodId`, `onLoadError(...)` | `MediaSourceEventListener` (default methods) or `AnalyticsListener.onLoadError` |
| `chunk.Chunk`, `chunk.ContainerMediaChunk` | `androidx.media3.exoplayer.source.chunk.*` |
| `MediaCodecRenderer.DecoderInitializationException` | same |
| `ExoPlaybackException.type` (`TYPE_SOURCE/RENDERER/UNEXPECTED`) | `PlaybackException.errorCode` (`ERROR_CODE_*`); `ExoPlaybackException.type` still there |

## 6. UI, leanback glue and view helpers
Files: `PlaybackFragment`, `SurfacePlaybackFragment` (234), `EmbedPlayerView`, `VideoZoomManager` (28),
`PlayerConstants` (37), `SubtitleManager` (190), `PlayerData` (`CaptionStyleCompat`), `doubletapplayerview/`.

| Today | Media3 module | Media3 |
|---|---|---|
| `ext.leanback.LeanbackPlayerAdapter` | media3-ui-leanback | `androidx.media3.ui.leanback.LeanbackPlayerAdapter` (no `ControlDispatcher`) |
| `ControlDispatcher`, `DefaultControlDispatcher` | — | removed; wrap the player in a `ForwardingPlayer` to intercept commands |
| `ui.PlayerView`, `ui.AspectRatioFrameLayout` (+ `ResizeMode`), `ui.SubtitleView`, `text.CaptionStyleCompat` | media3-ui | `PlayerView`, `AspectRatioFrameLayout`, `SubtitleView`, `CaptionStyleCompat` (`androidx.media3.ui`) |
| `text.Cue`, `text.TextOutput` | media3-common | `androidx.media3.common.text.Cue`, `Player.Listener.onCues(CueGroup)` |

## 7. MediaSession
Files: `PlaybackFragment` (`MediaSessionConnector` with metadata provider, control dispatcher, queue navigator),
`BackboneQueueNavigator` (50, `MediaSessionConnector.QueueNavigator`).

Media3 has no `MediaSessionConnector` (?). Options for 1d: `androidx.media3:media3-session` `MediaSession` (needs a
`MediaSession.Callback`, queue = the player's playlist, metadata from `MediaItem.mediaMetadata`), or keep the
platform `MediaSessionCompat` (androidx.media) and feed it by hand from `Player.Listener`. The app only uses it for
remote control keys and now-playing metadata.

## 8. Shorts queue and preloading
Files: `ShortsQueue` (151, `ConcatenatingMediaSource`), `ExoPlayerController` (queue checks against
`getCurrentTimeline().getWindowCount()`, item-end `PlayerMessage`, `seekTo(window, pos)`), `FastStartLoadControl`
(124, implements `LoadControl` + fork `PlayedPeriodsPolicy`).

| Today | Media3 |
|---|---|
| `ConcatenatingMediaSource` + `addMediaSource(index, source)` | deprecated; use the player playlist (`addMediaSource(index, source)` on `ExoPlayer`) — or, for stage 4, `DefaultPreloadManager` + `PreloadMediaSource` and swapping the current source |
| `LoadControl` (2.10 methods `onPrepared/onTracksSelected(Renderer[], TrackGroupArray, TrackSelectionArray)/shouldContinueLoading(bufferedDurationUs, speed)/shouldStartPlayback(...)`) | `LoadControl` with `PlayerId`, `Timeline`, `MediaPeriodId` params (?); `DefaultLoadControl.Builder` covers the buffer values |
| `PlayedPeriodsPolicy` (fork: keeps/drops already played periods of the concatenated Shorts playlist) | no counterpart; with `DefaultPreloadManager` the queue is no longer one long timeline, so likely not needed (1b confirms) |

## 9. Misc
- `VolumeBooster` (121): `AudioListener.onAudioSessionId` → `LoudnessEnhancer`. Media3: `Player.Listener.onAudioSessionIdChanged`.
- `DebugInfoManager`: reads `DashManifest`/`SabrManifest` from `getCurrentManifest()`, decoder counters, formats. Same APIs in Media3 (`getVideoDecoderCounters`, `getVideoFormat`) on `ExoPlayer`.

## Size of the work (lines of app code touching ExoPlayer)
Player/controller ~3.9k (incl. PlaybackFragment 1.9k, only parts touch the player), track selection ~2.9k (+ DebugInfoManager 0.6k),
renderers ~0.8k, sources ~0.9k, UI ~1.1k, Shorts ~0.3k, doubletapplayerview 4 files. Plus the SABR module
(~9k lines, step 1c) and the fork changes (step 1b).
