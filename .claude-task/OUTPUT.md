I've added preloading of the next 1–2 Shorts and a safety net for the stall. I couldn't pin the stall to one proven cause, and none of this has been built or run. Git and `python3` calls were blocked for approval, so I made the edits with the edit tool and haven't looked at the diff.

**Why nothing of the next Short is buffered:** `MediaPeriodQueue.shouldLoadNextMediaPeriod()` only enqueues the next period once the loading one is fully buffered. A 60–105 s Short is never fully buffered after a few seconds, so the next period isn't prepared before the swipe.

**The stall:**
- **No proven cause:** the code suggests two weak spots. ExoPlayer only continues loading on the last period, and a lost "continue loading" kick is only retried when `isLoading` is false.
- **`FastStartLoadControl`:** it doesn't look like the cause. Below 10 s of buffer it always allows loading, and the byte cap only applies above that.
- **Safety net:** in Shorts mode `doSomeWork` now calls `maybeContinueLoading()` every cycle. That is a no-op while a chunk is loading.
- **Limit of the fix:** if a video request simply never completes, this won't fix it. The report lists the log signature that would show that.

**Preload design** (all `// JoTube:`, on only when `isShortsMode()`; non-Shorts goes through the original code):
- **Early enqueue:** the next period is enqueued once the playing one has 8 s or more buffered ahead of the position. A queued one with at least 5 s buffered enqueues the one after it, and a fully buffered period always enqueues the next. At most 3 periods are in the queue.
- **Preload limit:** a queued period loads only its first 6 s.
- **Playing period keeps loading:** it is continued directly when it has less than 10 s ahead, or once the preload has reached its limit. The existing 180 s and byte caps still apply.
- **Buffer measure:** while preloading, the buffer duration is measured from the playing period, not from the end of the last queued one.
- **Swipe to a queued period:** it reuses the existing jump path. The periods behind it are dropped and re-preloaded, which costs a short delay.

The changes are in `ExoPlayerImplInternal`, `MediaPeriodQueue`, `PlayedPeriodsPolicy` and `FastStartLoadControl`. `.claude-task/REPORT.md` is overwritten with the stall analysis, the design, why it's safe, and what to look for in logs. Nothing is committed or pushed.
