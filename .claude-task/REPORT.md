# SABR start: report

Nothing was built or run here. The diff is two small edits, and the analysis below comes from reading the code and the logcat in the task.

## Cause (what the code does)

- **Each track has its own `SabrStream`.** `SabrManifest.getSabrStream(trackType)` creates one per track, with its own URL, playback cookie and `NextRequestPolicy`. Each track also has its own `ChunkSampleStream`, `Loader` thread and `DataSource`. Audio and video requests are therefore not serialized by a lock. `rn` is only a global counter incremented in `getRequestUrl()`, and it takes no lock across a request. `backoffTimeMs` is only logged and never waited on.
- **Redirects are already remembered per stream.** `SabrStream.setUrl()` keeps the new URL, so the second init request (`rn=2`) already goes to the redirected host. The first request of each stream (`rn=0`/`rn=1`) always goes to the original URL, and the server answers it with a redirect only. That request returns no format, so ExoPlayer asks for init again (`rn=2`/`rn=3`). Sharing the redirect between audio and video would not help, because both first requests start at the same time. I did not change this. This round trip (~0.17 s) is the minimum cost without a cached redirect.
- **The init chunk reads the whole init response.** `InitializationChunk.load()` calls `extractor.read()` until `RESULT_END_OF_INPUT`, and `SabrExtractorInput` ends only when the UMP stream ends. The server sends the format info, the init segment and then media segments in that response. The media goes to a `DummyTrackOutput` and is dropped. The loader stays busy until the last byte has arrived.
- **Matching the log:**
  - The audio init response ended at 39.056, and audio media (`rn=4`) started at 39.061.
  - The video init response (`rn=2`) had its format at 38.659 but ended only at 40.443, and video media (`rn=5`) started at 40.446.
  - Video media therefore waits ~1.4 s for the drain of data that is thrown away, and then downloads the same data again.
- **Minimum round trips to the first video frame:** 2 sequential. The first is the redirect-only response. The second is the init request, where the format and the init segment arrive. The third is the media request, which can run in parallel with audio. A media request issued before the init response completes isn't safe: it needs the playback cookie and the redirect URL.

## Change

1. `SabrStream`: new `setInitLoad(boolean)`.
   - While it is set, `parse()` returns `null`. That ends the UMP stream, so the extractor reports `END_OF_INPUT` and the init chunk completes.
   - This happens only after both of these hold:
     - the init segment's `MEDIA_END` was seen;
     - a fresh `NEXT_REQUEST_POLICY` (playback cookie) was received in this response.
   - If either never arrives (for example the redirect-only response), the response is read to the end as before.
2. `DefaultSabrChunkSource`: `newInitializationChunk()` calls `setInitLoad(true)`. `newMediaChunk()` calls `setInitLoad(false)`.

## Why it should be safe

- Per the comment in `SabrStream.nextKnownUMPPart` (`42, 35, 20, 21, 22`), `NextRequestPolicy` comes before the media parts. The newest cookie is therefore kept, and the guard makes the shortcut a no-op if the order is different.
- Dropping a response early is an existing path: seeks and `cancelLoad` already do it. `UMPDecoder` is stateless. A leftover entry in `partialSegments` is harmless, because the map is keyed by header id and overwritten.
- The init segment is fully consumed before the stop, so the extractor has the tracks and the format. The media request is built exactly as before.
- Side effect: the media from the init response used to be processed and filled `initializedFormats`. That made the first media request carry a partial `BufferedRange` for the dropped data (`reset(iTag)` zeroes the sequence number but keeps `durationMs`). Now the first media request has no buffered range for that format, like the init request. This is more correct, but see the open questions.
- Playback cookie, request numbers, player time and buffered-range logic are unchanged.

## Expected gain

About 1.4 s: the video media request should start at ~38.8 instead of 40.4, in parallel with audio. The first frame should come at roughly 1.2–1.5 s from source open instead of ~2.7 s. Audio gains less, because its init response is smaller.

## Risks / open questions

- I couldn't verify the real response order for every video. If `NEXT_REQUEST_POLICY` comes after the media, the shortcut is skipped and nothing changes.
- With `DefaultHttpDataSource` (HTTP/1.1), closing a half-read response drops the TCP connection, so the media request may need a new TLS handshake (~100–200 ms). OkHttp and Cronet over HTTP/2 only reset the stream. Still a net win.
- The first media request now has no partial `BufferedRange` for the format. If the server did not send media from time 0 in that case, the first frame would fail. I think this is unlikely, because the init request is the same and returned media. Please check the first run in logcat: `Load media chunk` must be followed by media, not by a SabrError or 403.
- Not done, because the protocol risk is too high for the gain:
  - Sharing the redirect or the playback cookie between the audio and video streams. The cookie is per stream.
  - Starting the media request before the init response ends.
