# Shorts media preload + stall

Nothing was built or run here. The changes come from reading the code and need a device test.

## 1. Why nothing of the next Short is buffered (confirmed from the code)
`MediaPeriodQueue.shouldLoadNextMediaPeriod()` only enqueues the next period when the loading period is
`isFullyBuffered()`. A 60–105 s Short is never fully buffered after 2–5 s, so the next period is
never created or prepared before the swipe. `FastStartLoadControl` only makes the current Short load further.
That is why the SABR init chunk of the next Short starts only after the switch.

## 2. The stall: what I could and could not establish
I could not prove a single root cause from the code and the log excerpt alone. What the code shows:

- ExoPlayer 2.10 only ever calls `continueLoading` on the *loading* (last) holder. `handleContinueLoadingRequested`
  drops (as "stale") the requests of every other period.
- Loading for one period is a chain: `CompositeSequenceableLoader` only continues the loader with the lowest
  next-load position. If the video loader gets no chunk (it is busy, or `getNextChunk` returns neither a chunk nor
  end of stream), audio is held back too. This matches the log: video rn=2 (0 s), audio rn=3, audio rn=4 (10 s),
  then nothing.
- After that, loading is only retried from `doSomeWork` when `playbackInfo.isLoading` is false. If `isLoading`
  stays true and a "continue loading" kick is lost, nobody polls again.
- `FastStartLoadControl` does not look like the cause on its own. Below 10 s of buffer it always returns true,
  and the byte cap (with retained periods) only applies above 10 s. Retained periods are released by
  `queue.clear()` on a non-preloaded switch, as in this log.
- The buffered duration that `maybeContinueLoading` passes to the load control is measured from the playing
  position to the end of the *last* period. Once next periods are queued it would count the whole playing
  Short plus the queued ones, and the 180 s cap would wrongly stop loading. This is fixed for the new
  preload mode (see below).

Fix for the stall class (Shorts only): `doSomeWork` now calls `maybeContinueLoading()` on every cycle. Calling it
while a chunk loads does nothing, so a lost kick can no longer leave the period idle. If the real cause is a
SABR request that never completes, this does not fix it. The log signature for that is below.

## 3. Design (all `// JoTube:`, gated on `PlayedPeriodsPolicy.shouldPreloadQueuedPeriods()` = `isShortsMode()`)
Files: `PlayedPeriodsPolicy`, `FastStartLoadControl` (the flag), `MediaPeriodQueue.canEnqueueEarly`,
`ExoPlayerImplInternal`.

- **Early enqueue** (`shouldEnqueueEarly`, replaces `shouldLoadNextMediaPeriod` in Shorts when a playing
  period exists): enqueue the next period when the loading period is prepared and non-final, and
  - it is the playing one with ≥ 8 s buffered ahead of the position, or
  - it is a queued one with ≥ 5 s buffered, or
  - it is fully buffered.
  At most 3 periods are in the queue (playing + 2).
- **Preload limit**: a queued (non-playing) loading period loads only its first 6 s.
- **Playing period keeps loading** (`maybeContinueQueuedLoading`): the playing holder's `mediaPeriod.continueLoading`
  is called directly (the holder method asserts "is loading"). It is called when the playing period has <10 s ahead
  (priority over the preload), or when the preload has reached its limit, and only if `shouldContinueLoading`
  allows it (so the 180 s / byte cap still applies). The playing period is also continued from the
  "continue loading requested" event that used to be dropped as stale, and from the per-cycle poll.
- `getTotalBufferedDurationUs` uses the playing period's buffer while preloading (used for rebuffer/resume and
  the load decisions), instead of the distance to the end of the last queued period.
- Non-Shorts: `isPreloading()` / `shouldPreloadQueued()` are false, so every changed branch reduces to the
  original code.

## 4. Why it should be safe
- The queue order, reading/playing advance and holder release code are untouched. The only new
  state is "more than one not fully buffered period in the queue", which ExoPlayer already allows when a
  period is prepared ahead of the reading one.
- Jumping (swipe) to a queued, partially buffered period uses the existing "jump forward to a prepared period" path in
  `seekToPeriodPosition`: the older periods are released, the target becomes playing and loading again
  (`removeAfter`), and the original loading logic takes over. The periods behind it are dropped
  and re-preloaded (a short delay, not a correctness issue).
- Natural advance (P6 ends, P7 starts) needs P6 fully buffered, which the playing-period loading guarantees.
- The existing rewind code already cuts the chain after a not fully buffered period.
- `PlayedPeriodsPolicy` has one implementer (`FastStartLoadControl`).

## 5. What to look for in logs
- After a Short starts and has ~8 s buffered, an init chunk (`rn=0`) for the next one appears while the current
  one is still playing. Switch → first frame should then be ~200–300 ms.
- While swiping, 2 queued Shorts should each have only chunks for their first ~6 s.
- Stall signature: the last chunk load of the playing Short never completes (no `Load completed`). Then the
  cause is in the SABR request or `SabrStream`, not the period queue. Another possible signature is
  `isLoading` stuck true with no new `rn` for >1 s (the per-cycle poll should now prevent it).
- Risks to watch: the current Short rebuffering (the playing priority is <10 s ahead), memory with 3 periods
  plus the retained ones, and a wrong `Switch → first frame` on a jump to a queued period.
