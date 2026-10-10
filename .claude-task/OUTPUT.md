Alla åtta filerna är portade till Media3 1.11.1, men inget är kompilerat (ingen Android SDK här). Signaturerna bygger på min kunskap om API:t. Rapporten ligger i `.claude-task/REPORT.md`, och inget är committat.

- **Renderers och fabrik:**
  - `CustomOverridesRenderersFactory` har de nya Media3-signaturerna och använder `getCodecAdapterFactory()`.
  - Den egna ljudrenderaren återanvänder den `AudioSink` fabriken skickar in.
  - `AmazonQuirks` är borttagen med en `// M3:`-kommentar.
  - `CustomRenderersFactoryBase` behövde ingen ändring.
- **DebugInfoMediaCodecVideoRenderer:** `canKeepCodec` blev `canReuseCodec` med `DecoderReuseEvaluation`, och Shorts-logiken är kvar. Eftersom `getCodecMaxInputSize` är privat i Media3 finns en lokal kopia av uppskattningen. Den saknar Amazon-/Samsung-specialfallen för AVC.
- **TweaksMediaCodecVideoRenderer:** Sony-fixen använder nu `shouldDropOutputBuffer` och `shouldDropBuffersToKeyframe` med samma trösklar (-1,0 s och -1,5 s), eftersom `isBufferLate` och `isBufferVeryLate` inte finns längre.
- **DelayMediaCodecAudioRenderer:** fördröjningen ligger kvar i `getPositionUs()`. Audio-sync-fixen flyttade jag dit från `processOutputBuffer`, eftersom jag inte var säker på den metodens signatur i 1.11.1.
- **BlacklistMediaCodecSelector:** `getPassthroughDecoderInfo` är borttagen eftersom den inte finns i Media3.
- **DebugInfoManager:** den implementerar `Player.Listener` och de tomma overrides är borttagna.
- **VolumeBooster:** den implementerar `Player.Listener` med `onAudioSessionIdChanged`.

**Behöver åtgärdas i andra filer:** `ExoPlayerController.java:831` och `ExoPlayerInitializer.java:159` och `:201` anropar `addAudioListener` och `removeAudioListener` med VolumeBooster. Det finns inte i Media3, så de ska byta till `addListener` och `removeListener`. Jag fick inte röra de filerna.

**Osäkert:**
- Jag har `@Override` på `shouldDropOutputBuffer`, `shouldDropBuffersToKeyframe`, `renderOutputBufferV21` och `canReuseCodec`. Om 1.11.1 ändrat någon signatur får CI ett fel, vilket jag föredrar framför en override som tyst inte gör något.
- Audio-sync-fixen patchar privata fält med reflektion. Fältnamnen i 1.11.1 är inte verifierade, och minify kan döpa om dem.
- `getCodecAdapterFactory()` antar jag finns som protected i `DefaultRenderersFactory`, enligt uppgiften.
