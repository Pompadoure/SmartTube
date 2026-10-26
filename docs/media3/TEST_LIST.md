# JoTube M3 – what to test on the device (when Johan installs it)

Check with logcat tag/lines `JoTubeTiming` for times. Targets: regular start < 1.5 s with prefetched info; instant Short switch.
1. Regular video: start time, quality menu, audio language (no dubbed audio by default), subtitles on/off.
2. Resume: open a half watched video, it starts at the saved position without a visible restart; a finished video starts at 0; a live stream.
3. SponsorBlock skip, playback speed (also on live), auto frame rate (off by default), SmartNext, history entry.
4. Live DASH/HLS, PIP and background playback, MediaSession (media keys, metadata).
5. Shorts: swipe next (several in a row), swipe back (1-2 back instant, 3+ back normal), side panel (title, avatar, like/dislike/comments/CC), seen-Shorts history, long session (memory).
6. Errors: airplane mode during play, a 403/404 video; no codec switch of the playing Short caused by preloaded ones.
7. Format info fetches stay serial (no 403 bursts in the log).
