package com.google.android.exoplayer2;

/**
 * JoTube: optionally implemented by a {@link LoadControl}. Lets the app keep already played
 * playlist periods (with their buffered samples) so a seek back into them is instant, and change
 * the back buffer duration at runtime (read on every discard, instead of once at creation).
 */
public interface PlayedPeriodsPolicy {
  boolean shouldRetainPlayedPeriods();

  long getCurrentBackBufferDurationUs();

  /**
   * Whether the start of the next queued playlist periods is loaded while the playing period is still
   * loading (Shorts). When false, the original behavior applies (the next period is only loaded once
   * the loading one is fully buffered).
   */
  boolean shouldPreloadQueuedPeriods();
}
