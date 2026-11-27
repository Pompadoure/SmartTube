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

## 2026-11-06
Checked: SmartTube up to 0b421183 (2026-11-05), MSC up to 347d43c9 (upstream pin; our pin is 1b43ec7).

TAKEN
- Nothing.

REJECTED
- 348cbe28 (History keeps a remote "100% watched" instead of the older local position): the condition in VideoGroup.add()
  decides the progress bar and the "hide watched" filter for every list. Videos finished elsewhere but with an older
  local position would show as watched/hidden where they show a partial bar today; a video rewatched here while the
  remote value is still stale would also flip. Changes existing behaviour, so left out.
- b74dd217 (add_to_queue intent extra): new feature (new share target for a third-party app), not an improvement.
- c8873b53 (Keep up with live option): new setting and rewrite of live handling in VideoStateController; live was
  rejected earlier.
- 64d72fab, 4557e4c3 (PoToken errors to ErrorFixerController, SabrPropagationError base class) and 10bc15af (reverted
  again upstream): net effect is zero upstream; PoToken changes conflict with our patches 0001/0005/0006.
- 0b421183 (Player/Network engine settings moved): moves settings UI that works today.
- MediaServiceCore pin bumps (089ddf64, 54377a52, 10bc15af): follow the rejected changes.
- Translations, version bumps 32.69/32.70, remote-control string fixes: skipped.

## 2026-11-13
Checked: SmartTube up to ab04c75f (2026-11-12, bump 32.74), MSC up to b3d561f5 (upstream pin; our pin is 1b43ec7).

TAKEN
- a0a82e48 (+ merge 6b68f502) card previews no longer keep the screen on: the embed (card preview) player skips
  `showHideScreensaver` and is not counted as "playing" in ScreensaverManager. A fullscreen start opens a new view
  (PlaybackPresenter.openVideo finishes the embed one), so its own onPlay still disables the screensaver as before.
  Fullscreen/phone behaviour is unchanged (`isEmbed()` is false there).

REJECTED
- aa862d40 (SABR error fix attempts 3 -> 1): fewer recovery tries; changes existing error recovery for the worse.
- 481059fe + MSC 767a6685 (allowRestrictedVideos, child mode turns auth off for video info): new option wired to child mode,
  touches the auth path of format info; no failure seen.
- 5c9cfb0c (keep the title of unplayable videos): changes shown titles; depends on MSC pin bump.
- d3e3c4fc (Home group with only a continuation button counts as empty): changes list/empty-state handling, not seen as a problem here.
- 71c760bf, 2cf24dd7 (PhoenixActivity theme / XGIMI on Android 8.0): not our devices, manifest change.
- MSC 66b5a1bc (error result fallback in FormatInfoWrapper): changes an error path; 9390e388 (client switch refactor) and
  b3d561f5 (PoToken attestation move) conflict with our patches 0001/0005/0006; 4010903f / 66f02ffd (live SABR setting
  moved): live was rejected earlier; db543d4d (hide mixes option) needs a new setting; 4be5193d (Shorts detection from reel style)
  changes which items count as Shorts in lists.
- Translations, README/docs, version bumps 32.71-32.74, dialog checkbox margins (f8ac53cb, 71c41c5c): skipped.

## 2026-11-20
Checked: SmartTube up to 41fbc7c6 (2026-11-19, bump 32.80), MSC up to c1d202fc (upstream pin; our pin is 1b43ec7).

TAKEN
- MSC a0a4b87e -> patch 0017 (renumbered to 0020 on 2026-11-27: media3 had got its own 0017-0019): the "Add device" pairing code. get_pairing_code answers with text/plain (only digits), but
  PairingCodeV2 was parsed with JsonPath ($.code) and gave null ("fromNullable result is null"). Now parsed with the RegExp
  converter, which also still accepts the JSON form, so a server answering either way works. Only this one call moved
  (InfoManager -> BindManager, like the older PairingCode); nothing else changes. Upstream test not ported (needs network).

REJECTED
- db76a0c9 + MSC e6ae6615 (invalidate the format cache before reloads): upstream reloads at the two sites changed here
  (fallback branch of the buffering fixer, SABR backoff action). Ours restarts the engine in that fallback (615ddecb was
  rejected for that reason) and has no SABR backoff action; the other reloads either already invalidate (switchNextClient*,
  reloadPlayerResponse) or intentionally reuse the cached info (subtitle/renderer errors).
- 41fbc7c6 (null check in VideoStateController.onNewVideo): only partial, the other controllers dereference the item too;
  no NPE seen here.
- 654f30dc (swallow NPE in UriBackgroundManager): "likely" OOM, no failure seen; hides errors.
- 72dd0ef4 (backup workers cancel at freqDays <= 0): the settings screen already cancels the worker; no failure seen.
  Its isStale() on SabrStreamError makes getFatalError() return nothing for 1 s after an error: changes SABR error timing.
- 29a8771d, e0fe1ac3 (skip chapters with UP button): new option/feature. 72e137b2, da643a87 (PoToken error propagation):
  conflicts with patches 0001/0005/0006 (rejected earlier). 4e4ef9c6 (proxy test on main thread): proxy dialog, no failure seen.
- MSC 63a7d61c, 1087a014 (TV client default again, TV_SAMSUNG client): changes the client order, conflicts with our patches.
  MSC 30389a8d (getMyVideos checkSigned): our pin has no checkSigned in that path, so nothing to fix.
- Translations (a6750dde Swedish, zh-rTW), README, version bumps 32.75-32.80, proguard/SharedModules/MSC pin bumps, typos: skipped.

## 2026-11-27
Checked: SmartTube up to ccc6800f (2026-11-27, bump 32.85), MSC up to 3e82ef20 (upstream pin; our pin is 1b43ec7).
The unmerged findings of 2026-11-13 and 2026-11-20 are carried on this branch.

TAKEN
- f4b90fce (part): when the long-buffering subtitle fix would fire within the last 3 s of a regular video, the video is
  now finished (normal playback mode) instead. In our code that fix's reload restarts the video from 0, because
  `resetPositionIfNeeded` treats < 3 s left as watched. Stricter than upstream: only with subtitles on, only if the
  video has been playing and its suggestions are synced (next video known); nothing is done while paused (e.g. after
  `stopPlayback()` seeks to the end, where the pending detector would otherwise disable subtitles and replay) or if
  the end was already handled; waits while a non-overlay dialog is open (not for repeat-one). With subtitles off the
  old `restartEngine` stays (it keeps the position). Found by code reading, not seen in a device log.
- b1d44d9d: `Locale.ROOT` for the SABR client name and the proxy type (crash with a Turkish system language).
  Same result on every other language.
- MSC 1814c5ad (TCL part, own implementation) -> patch 0021: `by lazy` cached a failed player download as null, so
  n-param solving stayed off while that extractor was reused. A failure still returns null, the next call retries.

REJECTED
- f4b90fce, error path (`runEngineErrorAction` near the end -> onPlayEnd): the player is idle after an error; with
  repeat-one / pause-after-video `onPlayEnd` only seeks it and nothing prepares it again. Today's reload recovers.
- f4b90fce for the engine restart without subtitles: it keeps the position and may still play the tail (e.g. after a
  short network drop); finishing would skip it.
- MSC 1814c5ad (web part: throw on download failure): in our pin's `firstValidExtractor` the exception would end the
  loop before the fallback player URLs are tried. MSC bad6cb57 (3 download attempts): adds waiting inside the
  serialized format fetch; with 0021 the next request retries anyway.
- 077009ca, 711ce2f9, 2983117a (SABR PoToken status as "unthrown error", checked on long buffering and every tickle):
  any status other than OK, including "pending", would run the engine error action, also while playback works.
  Follow-up of 72e137b2 (rejected 2026-11-20).
- 708096dc + MSC 3e82ef20 and the live part of c5a6944b: live without a start time becomes "unplayable" when SABR live
  is off. Changes how live streams are handled (live was rejected earlier).
- MSC c5a6944b (one solver script for TCL and web, new fallback player URLs), 09a1e13d: large solver rewrite, no
  failure seen here.
- MSC 3c733fef (PoToken cache reset back to 60 s): our pin already uses 60 s.
- MSC pin / SharedModules bumps, version bumps 32.81-32.85, README: skipped.
