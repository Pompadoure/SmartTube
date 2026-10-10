# Task: port the renderer/decoder group of JoTube to AndroidX Media3 1.11.1

Context: this repo (JoTube, an Android TV YouTube app, SmartTube fork) is being moved from a forked ExoPlayer 2.10.6
(`exoplayer-amzn-2.10.6/`, kept only as a reference, no longer built) to AndroidX Media3 **1.11.1**
(`androidx.media3:*`). The gradle swap and the mechanical import renames (`com.google.android.exoplayer2.X` ->
`androidx.media3.*`) are already done; now each file has to be fixed by hand so it compiles against the real
Media3 1.11.1 API. There is no Android SDK here, so you can't compile: work carefully from your knowledge of the
Media3 1.11.1 API, and prefer APIs you are sure exist in 1.11.1. The old 2.10.6 sources in
`exoplayer-amzn-2.10.6/library/core/src/main/java/com/google/android/exoplayer2/` show what each old call did.

## Files to port (ONLY these; under `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer/`)
- `versions/renderer/CustomRenderersFactoryBase.java`
- `versions/renderer/CustomOverridesRenderersFactory.java`
- `versions/renderer/TweaksMediaCodecVideoRenderer.java`
- `versions/renderer/DebugInfoMediaCodecVideoRenderer.java`
- `versions/renderer/DelayMediaCodecAudioRenderer.java`
- `versions/selector/BlacklistMediaCodecSelector.java`
- `other/DebugInfoManager.java`
- `other/VolumeBooster.java`

## Errors the CI compiler reported in these files (javac, Media3 1.11.1)
- TweaksMediaCodecVideoRenderer:51 `MediaCodec cannot be converted to MediaCodecAdapter`; :87 method does not
  override a supertype method; :93 cannot find symbol
- DebugInfoMediaCodecVideoRenderer: 9 errors (constructor/overrides/counters)
- DelayMediaCodecAudioRenderer: 5 errors
- CustomOverridesRenderersFactory: 11 errors (buildAudioRenderers/buildVideoRenderers signatures,
  FrameworkMediaCrypto/DrmSessionManager gone, AmazonQuirks gone)
- BlacklistMediaCodecSelector:80 no override, :82 cannot find symbol (MediaCodecSelector.getDecoderInfos now has
  `(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)` and throws DecoderQueryException)
- DebugInfoManager: 11 errors; VolumeBooster: 3 errors

## Target APIs (Media3 1.11.1)
- `DefaultRenderersFactory.buildVideoRenderers(Context, @ExtensionRendererMode int, MediaCodecSelector,
  boolean enableDecoderFallback, Handler, VideoRendererEventListener, long allowedVideoJoiningTimeMs,
  ArrayList<Renderer>)` and `buildAudioRenderers(Context, int, MediaCodecSelector, boolean enableDecoderFallback,
  AudioSink, Handler, AudioRendererEventListener, ArrayList<Renderer>)`. No DRM parameters any more.
- Renderer constructors: `MediaCodecVideoRenderer(Context, MediaCodecAdapter.Factory, MediaCodecSelector,
  long allowedJoiningTimeMs, boolean enableDecoderFallback, Handler, VideoRendererEventListener,
  int maxDroppedFramesToNotify)` and `MediaCodecAudioRenderer(Context, MediaCodecAdapter.Factory,
  MediaCodecSelector, boolean enableDecoderFallback, Handler, AudioRendererEventListener, AudioSink)`; use
  `getCodecAdapterFactory()` of DefaultRenderersFactory where a factory is needed (or
  `MediaCodecAdapter.Factory.getDefault(context)`).
- Video renderer hooks now take `MediaCodecAdapter codec` instead of `MediaCodec codec` (e.g.
  `renderOutputBuffer(MediaCodecAdapter, int, long)`, `renderOutputBufferV21(MediaCodecAdapter, int, long, long)`,
  `skipOutputBuffer(MediaCodecAdapter, int, long)`, `dropOutputBuffer(...)`), `codecNeedsSetOutputSurfaceWorkaround(String)`
  still exists (protected). Decoder counters: `decoderCounters` field / `DecoderCounters` in
  `androidx.media3.exoplayer`. Check what each JoTube override really does and keep the same behaviour.
- AudioSink: build with `new DefaultAudioSink.Builder(context).setAudioProcessorChain(...)/setAudioProcessors(...)
  .build()`; `AudioCapabilities` now via `AudioCapabilities.getCapabilities(context)` if still needed; override
  `buildAudioSink(Context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams)` in the renderers
  factory if JoTube adds processors.
- Amazon-only code (`AmazonQuirks`, Fire TV quirks) has no Media3 counterpart: drop it with a short `// M3:` comment.
- VolumeBooster: old `AudioListener`/`AudioRendererEventListener` usage -> `Player.Listener` /
  `AnalyticsListener.onAudioSessionIdChanged(EventTime, int)`; `ExoPlayer.getAudioSessionId()` exists.
- DebugInfoManager: `SimpleExoPlayer` -> `ExoPlayer`; `getVideoFormat()/getAudioFormat()`,
  `getVideoDecoderCounters()/getAudioDecoderCounters()` exist on ExoPlayer; `Format.frameRate`, `Format.codecs`,
  `Format.bitrate` stay fields; `ExoPlayerLibraryInfo.VERSION_SLASHY` -> `MediaLibraryInfo.VERSION_SLASHY`
  (androidx.media3.common); playback state constants on `Player`.

## Rules
- Keep every JoTube behaviour (frame-drop tweaks, audio delay, codec blacklist, debug overlay values, volume
  boost). If something truly can't be kept, leave a `// M3: <what and why>` comment and mention it in REPORT.md.
- Most Media3 APIs are `@UnstableApi`: lint is configured to allow it, no annotations needed.
- Do NOT touch any other file: not the old `exoplayer-amzn-2.10.6/`, not `media3-sabr/`, not gradle files, not
  `MediaServiceCore`, not `.github/`. Other app files are being ported in parallel by someone else.
- Public method names/signatures that other app files call (e.g. `new CustomOverridesRenderersFactory(context)`,
  DebugInfoManager's public methods, VolumeBooster's public API) must stay the same.
- The result must compile against Media3 1.11.1 (double-check every overridden method signature and constructor).
- In REPORT.md list per file what changed and anything you are unsure about.
