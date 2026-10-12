# Rapport: stage 2c review fix 1 och 2

Inget byggt (kan inte här). Inget committat.

## Fix 1: nätverksfel når "Unable to connect to"-hanteringen
`common/.../exoplayer/controller/ExoPlayerController.java`
- rad 15, 48: imports `HttpDataSource.HttpDataSourceException`, `java.io.IOException`.
- rad ~690-694 (`onPlayerError`): om `isConnectError(playbackError)` byts `nested` mot
  `new IOException("Unable to connect to: " + nested.getMessage(), nested)`. `ErrorFixerController` är orörd, så dess
  gamla strängkontroll (`startsWithAny(errorContent, "Unable to connect to")`) matchar igen och kör `switchNextEngine`.
- rad 711: ny `isConnectError`: `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` eller `_TIMEOUT`, eller en `HttpDataSourceException`
  med `type == TYPE_OPEN` var som helst i cause-kedjan.

## Fix 2: Shorts back buffer
`common/.../exoplayer/other/FastStartLoadControl.java`
- rad 109: `getBackBufferDurationUs(PlayerId)` ger `SHORTS_BACK_BUFFER_US` i Shorts-läge, annars delegatens värde.
- rad 114: `retainBackBufferFromKeyframe(PlayerId)` ger true i Shorts-läge, annars delegatens värde.
- LoadControl i Media3 har bara varianterna med `PlayerId`, så ingen no-arg-variant finns att åsidosätta (läst från koden:
  klassen har bara `PlayerId`-varianterna med `@Override`).

## Osäkert
- Media3-källorna kunde inte läsas. `HttpDataSourceException.type` (public final int) och `TYPE_OPEN` antas finnas enligt
  Media3 1.x API.
- Om delegaten är `DefaultLoadControl` ger `getBackBufferDurationUs` 0 utanför Shorts, som förut.
- Felet som skickas vidare får ett nytt meddelande ("Unable to connect to: <orsakens text>"), så det visas så i felrutan.
  Cronet-fel ("Exception in CronetUrlRequest") som också har kod CONNECTION_FAILED går nu in i "Unable to connect to"-grenen
  (som kommer först i `applyEngineErrorAction`) i stället för Cronet-grenen. Samma sak hände i 2.10 för
  "Unable to connect to"-meddelanden.
