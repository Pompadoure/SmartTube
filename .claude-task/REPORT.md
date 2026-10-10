# Rapport m3-renderers (ej kompilerad, ingen Android SDK)

## Ändrat per fil
- **CustomRenderersFactoryBase**: oförändrad (importerna stämde redan).
- **CustomOverridesRenderersFactory**: `buildAudioRenderers`/`buildVideoRenderers` har nya Media3-signaturer (AudioSink in, inga DRM-parametrar). Egna renderare får `getCodecAdapterFactory()`. Ljudrenderaren återanvänder den `AudioSink` som fabriken skickar in (ingen egen `DefaultAudioSink`). `AmazonQuirks` borttagen (`// M3:`). Gammal utkommenterad kod borttagen.
- **DebugInfoMediaCodecVideoRenderer**: ny konstruktor (adapter-factory). `canKeepCodec`/`KEEP_CODEC_RESULT_*` -> `canReuseCodec`/`DecoderReuseEvaluation.REUSE_RESULT_*` (Shorts-logiken kvar). `getCodecMaxInputSize` är privat i Media3, så en lokal kopia av uppskattningen används (utan Amazon/Samsung-specialfall för AVC).
- **TweaksMediaCodecVideoRenderer**: ny konstruktor, `renderOutputBufferV21(MediaCodecAdapter, ...)`. Sony-fixen: `isBufferLate`/`isBufferVeryLate` finns inte längre -> `shouldDropOutputBuffer`/`shouldDropBuffersToKeyframe` med samma trösklar (-1,0 s / -1,5 s).
- **DelayMediaCodecAudioRenderer**: ny konstruktor. Ljudfördröjningen kvar i `getPositionUs()`. Audio-sync-fixen (reflektion) flyttad från `processOutputBuffer` (lång signatur, osäker) till `getPositionUs()`.
- **BlacklistMediaCodecSelector**: `getPassthroughDecoderInfo` borttagen (finns inte i Media3). Resten oförändrat.
- **DebugInfoManager**: `Player.EventListener` -> `Player.Listener`, alla NOP-overrides borttagna (default-metoder), `getCurrentWindowIndex` -> `getCurrentMediaItemIndex`.
- **VolumeBooster**: implementerar `Player.Listener`, `onAudioSessionIdChanged(int)`.

## Osäkert / kräver åtgärd i andra filer
- **ExoPlayerController.java:831 och ExoPlayerInitializer.java:159/201** anropar `addAudioListener`/`removeAudioListener` med VolumeBooster. Det finns inte i Media3: byt till `addListener`/`removeListener` (jag får inte röra filerna).
- `shouldDropOutputBuffer`, `shouldDropBuffersToKeyframe`, `renderOutputBufferV21` och `canReuseCodec` har `@Override`; om 1.11.1 har ändrat signaturerna får CI fel (hellre synligt än tyst utan effekt).
- Audio-sync-fixen patchar privata fält (`audioTimestampPoller`, `state`) via reflektion; fältnamn i 1.11.1 är ej verifierade, och minify kan döpa om dem (samma risk som förut).
- `getCodecAdapterFactory()` antas finnas som protected i DefaultRenderersFactory (enligt uppgiften).
