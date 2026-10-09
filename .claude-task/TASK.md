# Task: faster start of regular videos (SABR) in JoTube

Repo: personal fork of SmartTube (Android TV YouTube client), Java, ExoPlayer 2.10.6 fork in `exoplayer-amzn-2.10.6/`.
All videos play through YouTube's SABR protocol, implemented in `exoplayer-amzn-2.10.6/library/sabr/`
(`DefaultSabrChunkSource`, `SabrMediaPeriod`, `parser/SabrStream`, `manifest/SabrManifest`, `parser/SabrProcessor`, UMP parsing).
Video and audio formats are pinned to one track each (`pinToTopTrack`): SABR can't switch itag mid-stream.

## Measured on the device (Google TV Streamer), one regular 1080p VP9 video, from logcat
- 38.097 player gets the format info and opens the SABR source
- 38.273 `Load init chunk: track=2, rn=0` → response is a SabrRedirect (new googlevideo URL) → UMP stream ended
- 38.441 `Load init chunk: track=2, rn=2` → FormatInitializationMetadata (itag 248) at 38.659, another SabrRedirect at 38.728
- 39.056 UMP stream ended; 39.061 `Load media chunk: track=1, rn=4` (audio media)
- 39.299 audio format known, 39.692 a stream ended
- 40.443 UMP stream ended; 40.446 `Load media chunk: track=2, rn=5` (video media)
- 40.513 video format, 40.561 decoder configured, 40.814 FIRST FRAME
So ~2.7 s from source open to first frame. The video media chunk only starts ~1.4 s after the audio one, and two redirects
happen in a row before any media. `rn` (request number) looks global/serialized.

## What to do
1. Read the SABR code and explain exactly why requests happen one after another (shared stream/lock? the request number
   ordering? ExoPlayer's loader per track? the redirect handling re-requesting?), and what the minimum number of
   round trips to the first video frame could be.
2. Implement the safest improvements that cut the time to the first frame, for example:
   - remember the redirect URL (SabrManifest/SabrCdnSelector) so later requests, and the other track, go straight to it;
   - let the audio and video loads run in parallel if they are serialized now;
   - start the video media request without waiting for the audio one; use media data that already arrives with the
     init response instead of requesting it again.
   Keep the protocol semantics correct (playback cookie, request numbers, buffered ranges, player time). A request the
   server rejects ends in a 403 and playback fails: when unsure, don't change it and describe it in the report instead.
3. Do not touch: `MediaServiceCore/` (a submodule, patched in CI), `.github/`, signing, app UI code. Only the SABR
   module (and, if really needed, `common/.../exoplayer/` glue).
4. Keep the changes small and commented (`// JoTube:`), Java 8 compatible, no new dependencies.

You can't run the app or the build here. Be careful and precise; another reviewer will check your diff and build it.
In REPORT.md: the cause, what you changed and why it is safe, the expected gain, and any risk or open question.
