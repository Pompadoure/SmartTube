# Upstream sync log

Weekly check of yuliskov/SmartTube and yuliskov/MediaServiceCore (MSC) for improvements to port to JoTube M3.
Rule: only improvements; nothing existing may change or break. When in doubt: REJECTED.

## 2026-10-16 (baseline, first run)
Checked: SmartTube up to e2436a44, MSC up to 7f36935.
TAKEN: SABR ReloadPlayerResponse handling (17b09643, 486c493b), buffering-detector reset on errors (e2436a44),
YouTube Mix autoplay (ad1418b1), search retry with a cloned Call (MSC 6c02bd1, patch 0011).
REJECTED: eb3713bd, 615ddecb, SABR backoff as fatal error, 4 s SABR read timeout + CDN switch (7e13d233), 1d28f43e,
88ca7a20, MSC e64b852, DNS-fix changes, NPE disables subtitles, popups, PoToken refactors, FormatInfoWrapper race, WEB_DUMMY.

## 2026-10-23
Checked: SmartTube up to c94f0757 (2026-10-23), MSC up to dca0e0b (upstream pin; our pin is 1b43ec7).

TAKEN
- MSC 4929ff24 (7c2451ea null-check refactor deliberately not taken) -> patch 0012: auto-dubbed videos carry an extra auto subtitle per dub
  (`variant=timing-optimized` / language of a dubbed audio track). `findOriginTrack` took the first auto track, so the
  "translated" subtitles were machine-translated from a dub transcript. Now a real (non-dub) auto track is preferred.
  Videos without auto-dub behave exactly as before (same first auto track; fallback unchanged).

REJECTED
- MSC d5ec583d (live via SABR, `isAdaptiveFormatsBroken` -> false): upstream calls it experimental; changes which
  live streams are played via SABR vs. skipped to the next client. Existing live behaviour must not change.
- MSC b83ff1db (FormatInfoWrapper null videoId): a null id would then return null instead of an error through Rx;
  no observed failure, changes an error path.
- MSC b6a0d64b (Lounge lazy init): only matters with a proxy set (NetworkOnMainThread in proxy init); `resetDataObserve`
  still resolves the service eagerly on the calling thread; delays Cast service creation. No benefit shown, some risk.
- MSC 63b23977 (rename VideoInfoType -> Client, warm-up per client): refactor that touches code our patches
  0002-0008 change; the persisted client setting is renamed. No user-visible gain.
- MSC 9234fc3a, 3a575eeb: tests only. MSC 73399c11/3edc9d57/495c1a8b/c3db3a8e/03b6625b/b0b2b5f4/e68ca7b4/107cf9aa
  and the pin bumps: depend on the rejected MSC changes (and a SharedModules update).
- 533a56c3, adc17ba1 (more "hardware decoder" name prefixes for the Force-SW-decoder option): would change which
  decoders an already saved Force-SW setting selects on c2.* devices.
- c94f0757 (Conscrypt for Android 4.x): irrelevant for our devices.
- bbed8fbb (restart app after DNS change): DNS-fix changes were rejected on 2026-10-16.
- a43ef340 (SafeWorkManager): manifest + startup changes for a direct-boot crash on some Android 9 devices; not
  seen on ours, wide change.
- 1be2c8da (history toggle refactor): equivalent behaviour, no gain.
- Translations, version bump 32.68, other chores: skipped.

## 2026-10-30
Checked: SmartTube up to 4b3f333e (2026-10-28), MSC up to 40fec843 (upstream pin; our pin is 1b43ec7).

TAKEN
- Nothing.

REJECTED
- 440c15b8 (+ MSC ba369250, 05feeb7e): live and past-live streams via SABR. Changes how existing live streams are played
  (MSC 05feeb7e is marked "temporarily unlock for testing, DO NOT RELEASE"; ba369250 says some streams are still broken).
- 380a2b79, a4f4b321, 404d1ad6 (DVR flag part): position restoring / seek rules for the new SABR live path only.
- 404d1ad6 (sleep for the server backoff): rides on the live change; sleeping on the loader thread changes timing of the
  normal VOD path and interacts with cancelling a load (403 risk). Backoff handling was rejected on 2026-10-16 too.
- e7c66266 + MSC 40fec843 (DIAL casting, "show up in the YouTube cast list"): new feature with a new network service and
  manifest changes, not an improvement to an existing one.
- 4314570f (Preferred DNS moved to General/Network, SABR live option), 3d6aa161, 63d5fe43, a9e705fd (checkbox dialogs):
  move settings and change UI that works today.
- 79d4a447 (reverted by 168e4848), eadfe9e5 (refactor): no gain.
- Translations (incl. 6cd95de6 Swedish), README, chores: skipped.
