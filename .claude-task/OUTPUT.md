Granskningen av steg 2c är klar och rapporten ligger i `.claude-task/REPORT.md` (på svenska). Jag hittade ingen BLOCKER. Jag har inte ändrat någon källfil, inte committat och inte pushat.

Ingenting är kört eller byggt. Allt bygger på kodläsning, eftersom Media3-källorna inte fanns. Det som är osäkert är markerat UNVERIFIED.

**SHOULD-FIX**
1. **Felet "Unable to connect to" matchas aldrig.** Den kopierade `OkHttp3DataSource` använder `createForIOException`, så texten finns inte längre. `ErrorFixerController.java:139` (nätverks-/datumfixen) slås därför aldrig på, och felet går i stället via `switchNextClient()`.
2. **Back buffer och förladdning för Shorts är döda.** `FastStartLoadControl` har tre metoder som Media3 inte anropar. Back buffer blir 0 ms, så bakåt-Short måste laddas om från nätet. Det kan åtgärdas billigt genom att åsidosätta `getBackBufferDurationUs(PlayerId)`. Förladdningen hör till stage 4.
3. **MediaSession-metadata uppdateras inte** när videon byts, och längden saknas.

**NIT**
- `onSeekEnd` skickas nu när en seek startar, inte när den är klar.
- Ljudsynk-fixen är en tyst no-op, och de två AmazonQuirks-inställningarna är borttagna.
- `DashDefaultLoadErrorHandlingPolicy` ersätter Media3:s lista över fel som inte ska retry:as (416 och cleartext).
- Det är osäkert om Media3 fångar `OutOfMemoryError` (UNVERIFIED), så sänkningen av bufferten kan utebli.
- `getFormat(0)` är första spåret i urvalet, inte det som spelas (fanns redan i 2.10-koden).
- `Log.e` loggar varje positionshopp.

Rapporten slutar med en lista över det jag kontrollerade och fann i ordning. Regeln om format-hämtning (aldrig parallellt, aldrig avbruten) bröts inte av något i diffen.
