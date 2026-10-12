Båda fixarna är gjorda, men inget är byggt eller testat, eftersom jag inte kan bygga här. Jag har bara läst koden. Ingenting är committat.

**Fix 1, nätverksfel:** i `ExoPlayerController.onPlayerError` omvandlas nätverksfel nu till ett `IOException("Unable to connect to: …")`. Det gäller när felkoden är `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` eller `_TIMEOUT`, eller när en `HttpDataSourceException` med `TYPE_OPEN` finns i cause-kedjan. Då matchar den gamla strängkontrollen i `ErrorFixerController` igen och nätverks-/datumfixen körs. `ErrorFixerController` är orörd.

**Fix 2, Shorts back buffer:** `FastStartLoadControl.getBackBufferDurationUs(PlayerId)` returnerar `SHORTS_BACK_BUFFER_US` i Shorts-läge och delegatens värde annars. `retainBackBufferFromKeyframe(PlayerId)` returnerar true i Shorts-läge. Någon variant utan argument fanns inte att åsidosätta; klassen har bara `PlayerId`-varianterna.

Två osäkra punkter, båda i rapporten:
- Media3-källorna kunde inte läsas, så jag antar att `HttpDataSourceException.type` och `TYPE_OPEN` finns i Media3 1.x.
- Ett Cronet-fel med kod `CONNECTION_FAILED` går nu in i "Unable to connect to"-grenen i stället för Cronet-grenen.

Ändringarna med fil och rad finns i `.claude-task/REPORT.md`.
