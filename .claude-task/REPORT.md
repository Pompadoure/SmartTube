# Oberoende granskning av M3 steg 2c (app-sidan)

Granskat: `git diff -w origin/media3...HEAD` för common/smarttubetv/doubletapplayerview (CRLF-bruset ignorerat).
Inga källfiler ändrade. Ingen BLOCKER hittad. Media3-källor kunde inte läsas, osäkert är markerat UNVERIFIED.

## SHOULD-FIX

1. **`ErrorFixerController.java:139` + `datasource/OkHttp3DataSource.java:278,356`: felmeddelandet "Unable to connect to" finns inte längre.**
   2.10:s `OkHttpDataSource`/`DefaultHttpDataSource` kastade `HttpDataSourceException("Unable to connect to " + uri)`.
   Media3 (och den kopierade OkHttp3DataSource) använder `HttpDataSourceException.createForIOException(...)`.
   Meddelandet blir då orsakens text (t.ex. `java.net.UnknownHostException ...`). `Helpers.startsWithAny(errorContent, "Unable to connect to")`
   matchar aldrig. Det ger ingen nätverks-/datumfix (`switchNextEngine`). Felet hamnar i grenen `ERROR_TYPE_SOURCE && RENDERER_INDEX_UNKNOWN`
   och kör `switchNextClient()` i onödan, utan meddelande. Detta är en sämre felhantering vid fel datum eller ingen uppkoppling.
   Fix: i `ExoPlayerController.onPlayerError` (rad ~687), om `playbackError.errorCode` är
   `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`/`_TIMEOUT` (eller nested är `HttpDataSourceException` med `type == TYPE_OPEN`),
   skicka vidare en `IOException("Unable to connect to " + uri, nested)`, eller matcha på felkoden i ErrorFixerController.
   Cronet/DefaultHttpDataSource i Media3: UNVERIFIED men troligen samma sak.

2. **`FastStartLoadControl.java` (~rad 63-80): Shorts back buffer och förladdning är döda metoder.**
   `shouldRetainPlayedPeriods`, `shouldPreloadQueuedPeriods` och `getCurrentBackBufferDurationUs` anropas inte av Media3 (koden säger det själv, "stage 4").
   `getBackBufferDurationUs(playerId)` delegerar till default (0 ms), så en spelad Short släpps direkt.
   `ExoPlayerController.switchToQueued` → `seekToDefaultPosition(index-1)` måste då förbereda föregående Short på nytt (nätverk),
   i stället för att vara omedelbart. Det är en regression mot "bakåt är direkt" och gör Shorts-bakåt långsammare.
   Förladdningen av nästa Short börjar först när nuvarande är helt buffrad (Media3-standard), utan fork-hooken.
   Fix (billig, i 2c): åsidosätt `getBackBufferDurationUs(PlayerId)` så den returnerar `SHORTS_BACK_BUFFER_US` i Shorts-läge
   (och ev. `retainBackBufferFromKeyframe` true). Förladdningen hör till stage 4 (`DefaultPreloadManager`).

3. **`PlaybackFragment.java:~704-790` (MediaSession): metadata uppdateras inte, ingen duration.**
   `ForwardingPlayer.getMediaMetadata()` läses bara när sessionen ber om den. Inget triggar en uppdatering när videon byts
   (TODO i koden). Tidigare gav `MediaMetadataProvider` ny metadata vid varje ändring och innehöll `DURATION`.
   Resultat: Bluetooth-/Assistant-/Shield-widgetar visar tom eller gammal titel, och ingen längd.
   Fix: i `ForwardingPlayer` hantera `Player.Listener` (eller anropa `mMediaSession.setPlayer`/skicka `onMediaMetadataChanged`
   via en egen `Player.Listener`-wrapper) vid `onSourceChanged`/`onVideoLoaded`. Sätt även en `MediaItem` med metadata på duration.
   Om det skjuts upp till stage 3: notera det i PROGRESS.md.

## NIT

4. **`ExoPlayerController.java:741` (onSeekEnd vid seek-start).** `onSeekProcessed` kom när seeken var klar, `DISCONTINUITY_REASON_SEEK` kommer när den startar.
   `ErrorFixerController.onSeekEnd` startar buffringsdetektorn och `onPlay/onPause` avbryter den vid READY, så det fungerar.
   `PlayerUIController.updateEndingTime` får ändå nya positionen (Media3 rapporterar den nya positionen). Kommentaren är korrekt. Ingen åtgärd, men kom ihåg det vid felsökning av "seek hänger"-detekteringen.

5. **`DelayMediaCodecAudioRenderer.java` (`applyAudioSyncFix`): inställningen "audio sync fix" är en tyst no-op.** Dokumenterat i koden.
   Användare som slagit på den märker ingen effekt. Skriv det i PROGRESS.md eller göm inställningen i stage 3.
   Likaså: `AmazonQuirks` (`isSnappingToVsyncDisabled`, `isProfileLevelCheckSkipped`) är borttagna, så de två inställningarna gör inget.

6. **`DashDefaultLoadErrorHandlingPolicy.java`: `getRetryDelayMsFor` ersätter Medias3 undantagslista.**
   Media3-standard ger `TIME_UNSET` (ingen retry) även för `CleartextNotPermittedException` och `DataSourceException` med `POSITION_OUT_OF_RANGE` (416). Nu retry:as de med upp till 5 s fördröjning.
   Samma beteende som 2.10 för 404/410/403, men 416 och cleartext fördröjs i onödan. Fix: anropa `super.getRetryDelayMsFor` för de typerna. Liten effekt. UNVERIFIED (exakt lista i Media3).

7. **`ExoPlayerController.java:689-699` OOM.** Typen mappas till `ERROR_TYPE_OUT_OF_MEMORY` bara om OOM är orsaken i ett `PlaybackException`.
   UNVERIFIED: om Media3:s `ExoPlayerImplInternal` inte fångar `OutOfMemoryError` (2.10 gjorde det, `createForOutOfMemoryError`) kraschar appen i stället för att
   ErrorFixerController sänker bufferten. Kontrollera på enheten (eller i Media3-källan) innan 2c kallas klar.
   Kommentaren "Player is released at this point" (rad 685) stämmer inte i Media3 (spelaren går till IDLE, spellistan finns kvar). Skadar inte.

8. **`ExoPlayerController.java:642` `selection.getFormat(0)`** (fanns redan i 2.10-koden) är första spåret i urvalet, inte det som spelas, vid adaptivt urval.
   Påverkar `onTrackChanged`/kvalitetsetiketten. Media3 `ExoTrackSelection.getSelectedFormat()` finns om typen ändras. Ej en regression.

9. **`ExoPlayerController.java:739`: `Log.e` i `onPositionDiscontinuity`** på varje hopp (fanns tidigare också). Bara logg-brus.

## Kontrollerat och OK

- `ExoMediaSourceFactory`: DASH/SABR/HLS/progressive byggs med MediaItem, `TrackErrorFixer` läggs på alla källor (även SABR), `DashDefault/SabrDefault`-policy kvar på DASH/SABR, cookie på `timedtext` via `ResolvingDataSource`. Bandbreddsmätaren delas av spelaren och datakällorna (inte dubbelräknad). Inga parallella eller avbrutna format-hämtningar tillkom (ingen ändring av hämtning av format info; SABR POST avbryts aldrig enligt 2b).
- `ExoPlayerController.openMediaSource`: `prepare(MediaSource)` (deprecated men ger samma som 2.10: reset position + state). `ShortsQueue` använder fortfarande `ConcatenatingMediaSource`: fungerar i Media3 som enskild MediaSource, `getWindowCount()==size()`-kontrollen gäller.
- `PlayerMessage.setLooper(mHandler.getLooper())` ersätter `setHandler` korrekt. `AUTO_TRANSITION` ersätter `PERIOD_TRANSITION`. `handleQueueAutoAdvance` anropar `seekTo` inifrån en listener (tillåtet, köas).
- Tunneling: `TUNNELING_ALLOWED=false` gör att `mTunnelingTrackSelector` aldrig sätts, så `applyTunneling` är död kod. Det nya `!isShortsMode()` motsvarar gamla sessionId-logiken. Tunneling är av.
- `stop()` + `clearMediaItems()` ersätter `stop(true)` med samma `containsMedia()`-spärr. `removeListener(this)` sker före, så inga extra händelser vid release. `VolumeBooster` registreras och avregistreras som `Player.Listener`.
- `DebugInfoMediaCodecVideoRenderer.canReuseCodec` behåller Shorts-återanvändningen (SDR, adaptive). `TweaksMediaCodecVideoRenderer` (`shouldDropOutputBuffer`/`shouldDropBuffersToKeyframe` med `!isLastBuffer`) och `renderOutputBufferV21` åsidosätter rätt.
- `RestoreTrackSelector`: callback per renderer bevaras (video 0, audio 1, text 2), `null` = avstängd som förut. `TrackSelectorManager` bygger `Definition` med `override.type`. `isDrc` via `SabrFormatMetadata` sätts i både SABR- och DASH-parsern.
- `TrackErrorFixer`: kroppen är oförändrad (bara `MediaSourceEventListener`-implementationen). `SubtitleManager`: `onCues(CueGroup)`, bitmap-cues hoppas över, lyssnaren försvinner med spelaren. `createSubtitleManager` anropas fortfarande (rad 1537).
- `PlaybackFragment`: `mFirstFrameListener`/`mTransitionStateListener` avregistreras vid release. MediaSession släpps före spelaren. AFR-spärren (`getAfrSwitchTimeMs` 5 s) täcker `setPlayWhenReady/play/pause` i sessions-spelaren. `AdaptiveTrackSelection.Factory(3000, 25000, 25000, 0.85f)` har samma argumentordning, mätaren kommer från spelaren.
- `FastStartLoadControl.shouldStartPlayback`/`shouldContinueLoading`: samma trösklar (0,5 s Shorts, 1 s VOD) och minnesgränser, `mPlayerId` sätts i `onPrepared`. Start blir inte långsammare än tidigare.
- `ExoPlayerInitializer`/`ExoPlayer.Builder`: samma LoadControl, ljudattribut och fokus. `ZoomAspectRatioFrameLayout` används i layout och `SurfacePlaybackFragment`.
- `Dash/SabrDefaultLoadErrorHandlingPolicy`: 404/410 → fallback, 5 s för "Wait 5 sec", övrigt `min((n-1)*1000, 5000)` som förut.
