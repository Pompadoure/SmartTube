Tre av de fyra fixarna är portade, men inget är byggt eller testat. Media3-källorna gick inte att nå här (`git clone` och `WebFetch` krävde godkännande). Alla Media3-antaganden är därför från minnet och markerade UNVERIFIED i `.claude-task/REPORT.md`. Allt ligger i `SubtitleManager.java`.

- **HTML-entiteter:** text som matchar en entitet avkodas med `Html.fromHtml`, annars lämnas den orörd. Jag minns att Media3 bara själv avkodar `&amp; &lt; &gt; &nbsp; &lrm; &rlm;`, men det är inte kontrollerat mot 1.11.1.
- **Max 10 s per cue:** en Handler tömmer cue-vyn efter 10 s om ingen ny `CueGroup` kommit. Timern pausas vid paus och buffring via `onIsPlayingChanged`. Jag valde den framför en wrapping `SubtitleParser.Factory` för att den inte rör `media3-sabr` eller DASH-källorna och inte kräver att jag gissar signaturer. Nackdelen är att tiden räknas i klocktid, inte i uppspelningstid.
- **Bara sista cue:** `forceCenterAlignment` behåller sista cue när flera överlappar.
- **Utfylld bakgrund:** `PaddingBackgroundColorSpan` läggs på cue-texten och stilens egen bakgrund sätts till transparent för att undvika dubbel ritning. Jag bytte också `setApplyEmbeddedStyles(false)` till `true`, eftersom spannet enligt mitt minne annars rensas. Cues byggs ändå om som ren text, så `true` ger inga andra stilar.
- **3-radershöjden:** portas inte. I 2.10.6 är raden med `getLineBaseline(0) * 3` bortkommenterad, så den aktiva koden är redan stock-beteende.

Ingen `@UnstableApi`-åtgärd behövs, eftersom `common/build.gradle:39` redan opt-in:ar globalt. Inget är committat eller pushat.
