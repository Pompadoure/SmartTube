I made two small edits to cut the time to the first frame. I couldn't build or run anything here, so none of it is tested. The expected gain is about 1.4 s, taking the first frame from ~2.7 s to roughly 1.2–1.5 s after the source opens.

## Why requests happen one after another

- **No lock or request-number ordering is involved.** Each track has its own `SabrStream`, loader thread and `DataSource`. `rn` is just a global counter.
- **The video init chunk drains the whole init response before the media request can start.**
  - `InitializationChunk.load()` reads until the UMP stream ends, and that response carries the format info, the init segment and then media segments.
  - The media is thrown away, and then the same data is requested again as `rn=5`.
  - In your log, the video format arrived at 38.659 but the response ended at 40.443. The video media request started 3 ms later.
- **Audio lines up the same way.** Its init response ended at 39.056, and its media request started at 39.061.
- **The first request of each track always gets only a redirect.** It carries no format, so ExoPlayer asks for init again. Each stream already remembers its own redirect URL. Sharing it between audio and video wouldn't help, because both first requests start together.
- **Minimum round trips:** two sequential ones before media (redirect-only, then init), and the media request can then run in parallel with audio.

## What I changed

- **`SabrStream`:** a new `setInitLoad()` makes `parse()` end the stream right after the init segment. It only does this once a fresh `NextRequestPolicy` (the playback cookie) has also arrived in that response. If either never arrives, the response is read to the end as before.
- **`DefaultSabrChunkSource`:** `newInitializationChunk()` turns the flag on and `newMediaChunk()` turns it off.

Per the part order noted in a comment in `SabrStream.nextKnownUMPPart` (`42, 35, 20, 21, 22`), the playback cookie comes before the media, so the shortcut should keep the latest one. Stopping a response early is already what seeks and cancelled loads do. Request numbers, player time and the buffered-range logic are unchanged.

## Open questions for the reviewer

- **Buffered range on the first media request:** it no longer includes a partial range for the dropped init-response data. That should be closer to correct, but please check the first run in logcat. `Load media chunk` should be followed by media, not by a SabrError or 403.
- **Connection drop with `DefaultHttpDataSource`:** closing a half-read response drops the connection, which may cost one extra TLS handshake (~100–200 ms). OkHttp and Cronet shouldn't.
- **Not done:** sharing the playback cookie between tracks, and issuing the media request before the init response ends. Both carry too much risk of a rejected request for the gain.

The full write-up is in `.claude-task/REPORT.md`.
