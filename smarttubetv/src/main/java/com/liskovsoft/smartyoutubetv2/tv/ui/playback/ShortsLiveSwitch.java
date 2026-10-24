package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

/**
 * JoTube: the Shorts switch with two players and a role swap (owner 2026-10-24: "no stills, no black frames, no
 * hiccups"; design and device evidence: docs/media3/ROLE_SWAP.md).
 * <ol>
 * <li>The next Short waits in the spare player ({@link ShortsPreview}): prepared, paused on its first frame, out of
 * sight.</li>
 * <li>Key press: it starts playing at once and slides in while the current Short slides out; the sound crosses over.</li>
 * <li>After the slide the core opens the Short as usual; the player controller asks the screen to adopt the spare player
 * (it plays the Short already): the spare player becomes the main one (main surface, core, session) and keeps playing,
 * nothing is loaded, sought or synced again. The old main player becomes the spare and prepares the next Short.</li>
 * </ol>
 * Everything runs on the main thread; the state is only touched there.
 */
final class ShortsLiveSwitch {
    private static final String TAG = "ShortsLive";
    // Moving video, not a still: a touch longer than the picture slide; the strong deceleration keeps it calm at the end
    static final int SLIDE_MS = 420;
    private static final int GAP_DP = 24;
    private static final long OPEN_TIMEOUT_MS = 8_000; // the core didn't open the Short (format info): give up
    private static final long HAND_OVER_TIMEOUT_MS = 700; // no first frame on the main surface: uncover anyway
    private static final long DIAG_AFTER_MS = 5_000; // glitches of the adopted player right after the swap are logged

    interface Host {
        ExoPlayer mainPlayer();

        View mainSurface();

        ShortsPreview preview();

        ShortsSidePanel panel();

        /** The page of the new Short (background, title, channel): at the start of the slide. */
        void onLivePage(Video next);

        /** The slide was cut short before the new Short was opened: the page goes back to the one that stays. */
        void onLiveCancelled();
    }

    private enum State {IDLE, SLIDING, WAITING, ADOPTED}

    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private State mState = State.IDLE;
    private ValueAnimator mSlide;
    private Runnable mOpen;
    private Video mTarget;
    private float mTargetAspect;
    private float mVolume = 1f; // the volume the core wants
    private float mBaseSpeed = 1f; // the speed the core wants
    private float mPitch = 1f;
    private boolean mProblemInSlide; // the spare player failed while sliding
    private long mStartMs;
    private Runnable mOnHandedOver; // the screen gives the old main player to the preview (after the uncover)
    private final Runnable mOpenTimeout = () -> {
        Log.w(TAG, "The core didn't open the Short in time: the switch is dropped");
        fallBack();
    };
    private final Runnable mHandOverTimeout = () -> {
        Log.w(TAG, "No first frame on the main surface after the swap: uncovered anyway");
        finishHandOver();
    };

    ShortsLiveSwitch(Host host) {
        mHost = host;
    }

    /**
     * The key press: plays the prepared Short live and slides it in. {@code open} = the core opening it (after the slide).
     *
     * @return false: not prepared (the usual switch is used)
     */
    boolean start(Video next, Runnable open) {
        if (mState == State.ADOPTED) {
            finishHandOver(); // the last swap is done, only the uncover was pending
        }

        if (mState != State.IDLE) {
            return false; // still switching: the swipe waits (isBusy)
        }

        ShortsPreview preview = mHost.preview();
        ExoPlayer main = mHost.mainPlayer();
        View mainSurface = mHost.mainSurface();
        ShortsSidePanel panel = mHost.panel();

        if (next == null || preview == null || main == null || mainSurface == null || panel == null ||
                !preview.isReadyFor(next.videoId) || preview.getView() == null || preview.getPlayer() == null) {
            return false;
        }

        float aspect = preview.getAspectRatio();
        int[] frame = panel.frameFor(aspect);

        if (frame == null) {
            return false;
        }

        final View incoming = preview.getView();
        final ExoPlayer incomingPlayer = preview.getPlayer();
        // the incoming Short in its own frame shape
        preview.setFrame(frame[0], frame[1], frame[2], true);

        if (!preview.startLive()) {
            return false;
        }

        mStartMs = SystemClock.uptimeMillis();
        mTarget = next;
        mTargetAspect = aspect;
        mOpen = open;
        mProblemInSlide = false;
        mVolume = main.getVolume();
        mBaseSpeed = main.getPlaybackParameters().speed;
        mPitch = main.getPlaybackParameters().pitch;
        incomingPlayer.setVolume(0f);
        incomingPlayer.setPlaybackParameters(main.getPlaybackParameters()); // the user's speed
        incomingPlayer.setRepeatMode(main.getRepeatMode());

        final float distance = frame[1] + GAP_DP * mainSurface.getResources().getDisplayMetrics().density;
        incoming.animate().cancel();
        incoming.setAlpha(1f);
        incoming.setTranslationY(distance);

        mHost.onLivePage(next);
        panel.setSlideAside(true);

        mState = State.SLIDING;
        Log.d(TAG, "Live switch to %s: slide starts", next.videoId);
        attachDiag(main, incomingPlayer);

        mSlide = ValueAnimator.ofFloat(0f, 1f);
        mSlide.setDuration(slideDuration(mainSurface));
        mSlide.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        mSlide.addUpdateListener(animation -> {
            float position = (float) animation.getAnimatedValue();
            float time = animation.getCurrentPlayTime() / (float) Math.max(1, animation.getDuration());
            time = Math.max(0f, Math.min(1f, time));
            mainSurface.setTranslationY(-distance * position);
            incoming.setTranslationY(distance * (1f - position));
            // the sound crosses over with the time (not the position), equal power (no dip in the middle)
            main.setVolume(mVolume * (float) Math.cos(time * Math.PI / 2));
            incomingPlayer.setVolume(mVolume * (float) Math.sin(time * Math.PI / 2));
        });
        mSlide.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!mCancelled) {
                    onSlideEnd();
                }
            }
        });
        mSlide.start();
        return true;
    }

    /** The system's animation scale (developer option) shortens or lengthens every animator: the slide keeps its time. */
    private static long slideDuration(View view) {
        float scale = 1f;
        try {
            scale = android.provider.Settings.Global.getFloat(view.getContext().getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Throwable e) {
            // keep 1
        }
        return scale > 0.05f ? (long) (SLIDE_MS / scale) : SLIDE_MS;
    }

    /** Sliding, or the core is still opening the Short (the spare player plays it on top): swipes wait. */
    boolean isBusy() {
        return mState == State.SLIDING || mState == State.WAITING;
    }

    /** The spare player is the one heard and controlled for the core (until it is adopted). */
    boolean isActive() {
        return mState == State.SLIDING || mState == State.WAITING;
    }

    /** The core sets the volume while the spare player plays the Short on top. */
    boolean onCoreVolume(float volume) {
        if (!isActive()) {
            return false;
        }
        mVolume = volume;
        ShortsPreview preview = mHost.preview();
        if (mState == State.WAITING && preview != null && preview.getPlayer() != null) {
            preview.getPlayer().setVolume(volume);
        }
        return true;
    }

    float getVolume() {
        return mVolume;
    }

    /** The core sets the speed (the user's, per channel...): the spare player follows it. */
    void onCoreSpeed(float speed) {
        if (!isActive() || speed <= 0) {
            return;
        }
        mBaseSpeed = speed;
        ShortsPreview preview = mHost.preview();
        if (preview != null && preview.getView() != null
                && !PlayerTweaksData.instance(preview.getView().getContext()).isAudioTimeStretchingEnabled()) {
            mPitch = speed; // same rule as in the player controller
        }
        if (preview != null && preview.getPlayer() != null) {
            preview.getPlayer().setPlaybackParameters(new PlaybackParameters(speed, mPitch));
        }
    }

    float getBaseSpeed() {
        return mBaseSpeed;
    }

    /** Play / pause from the core (remote, button): the spare player, which is the one on screen, follows. */
    void onCorePlayWhenReady(boolean play) {
        ShortsPreview preview = mHost.preview();
        if (isActive() && preview != null && preview.getPlayer() != null) {
            preview.getPlayer().setPlayWhenReady(play);
        }
    }

    /** The spare player failed or ended while it plays the Short on top: the core loads it the usual way. */
    void onSecondPlayerProblem() {
        if (mState == State.SLIDING) {
            mProblemInSlide = true; // handled when the slide ends
        } else if (mState == State.WAITING) {
            Log.w(TAG, "The spare player stopped before it was adopted: the usual switch");
            fallBack();
        }
    }

    /** An error of the main player: during the switch it's the old Short's (it leaves anyway). */
    void onMainError() {
        // nothing: the old main player is muted and becomes the spare
    }

    private void onSlideEnd() {
        mSlide = null;
        ExoPlayer main = mHost.mainPlayer();
        ShortsPreview preview = mHost.preview();
        Log.d(TAG, "Slide ends: +%s ms", SystemClock.uptimeMillis() - mStartMs);

        if (main != null) {
            main.setVolume(0f); // the old Short: silent until the swap (then it stops)
        }
        if (preview != null && preview.getPlayer() != null) {
            preview.getPlayer().setVolume(mVolume);
        }
        if (preview != null && preview.getView() != null) {
            preview.getView().setTranslationY(0);
        }

        // The main surface goes back in place, under the spare player's frame: the adopted player moves onto it
        View mainSurface = mHost.mainSurface();
        if (mainSurface != null) {
            mainSurface.setTranslationY(0);
        }

        ShortsSidePanel panel = mHost.panel();
        if (panel != null) {
            panel.setVideoAspect(mTargetAspect); // frame, border and the main surface's frame: the new shape
            panel.setSlideAside(false);
        }

        mState = State.WAITING;
        mHandler.postDelayed(mOpenTimeout, OPEN_TIMEOUT_MS);
        Runnable open = mOpen;
        mOpen = null;
        if (mProblemInSlide) {
            mProblemInSlide = false;
            Log.w(TAG, "The spare player stopped during the slide: the usual switch");
            fallBack();
            if (open != null) {
                open.run();
            }
            return;
        }
        if (open != null) {
            open.run(); // the core opens the Short now: it adopts the spare player (adopt)
        }
    }

    /**
     * The core starts {@code video} (resetPlayerState).
     *
     * @return true: it is the Short of the live switch (the spare player plays it: nothing to reset or cover)
     */
    boolean onMainStarting(Video video) {
        if (mState == State.IDLE || mState == State.ADOPTED) {
            return false;
        }

        if (mState == State.SLIDING) {
            // Something else is opened during the slide (not by the user's swipe): that one wins
            Log.d(TAG, "Another video starts during the slide: the live switch is dropped");
            abort();
            return false;
        }

        if (video == null || mTarget == null || !mTarget.videoId.equals(video.videoId)) {
            Log.d(TAG, "Another video starts: the live switch is dropped");
            fallBack();
            return false;
        }

        return true;
    }

    /**
     * The player controller opens {@code videoId}: if the spare player plays it on top, it is handed over (the caller
     * makes it the main player and calls {@link #onAdopted}).
     */
    @Nullable
    ExoPlayer takeForAdoption(String videoId) {
        if (mState != State.WAITING || mTarget == null || videoId == null || !videoId.equals(mTarget.videoId)) {
            return null;
        }

        ShortsPreview preview = mHost.preview();
        ExoPlayer player = preview != null ? preview.handOverLive() : null;

        if (player == null) {
            Log.w(TAG, "The spare player isn't live any more: the usual switch");
            fallBack();
            return null;
        }

        mHandler.removeCallbacks(mOpenTimeout);
        // From now on the core talks to the adopted player itself (volume, speed and play state go to the controller)
        mState = State.ADOPTED;
        mTarget = null;
        mHandler.postDelayed(mHandOverTimeout, HAND_OVER_TIMEOUT_MS);
        return player;
    }

    /**
     * The spare player is the main one now (it plays on the main surface). The spare frame stays on top until the main
     * surface shows a frame of it ({@link #onMainFirstFrame}), then {@code onHandedOver} gives the old player to the
     * preview.
     */
    void onAdopted(Runnable onHandedOver) {
        if (mState != State.ADOPTED) {
            if (onHandedOver != null) {
                onHandedOver.run(); // uncovered already
            }
            ShortsPreview preview = mHost.preview();
            if (preview != null) {
                preview.onMainFirstFrame();
            }
            return;
        }
        mOnHandedOver = onHandedOver;
        Log.d(TAG, "Swapped: +%s ms (no reload, no seek)", SystemClock.uptimeMillis() - mStartMs);
        mHandler.removeCallbacks(mDetachDiag);
        mHandler.postDelayed(mDetachDiag, DIAG_AFTER_MS);
    }

    /** A frame on the main surface (after the swap: the adopted player's). */
    void onMainFirstFrame() {
        if (mState == State.ADOPTED) {
            Log.d(TAG, "The main surface shows the adopted player: +%s ms", SystemClock.uptimeMillis() - mStartMs);
            finishHandOver();
        }
    }

    private void finishHandOver() {
        mHandler.removeCallbacks(mHandOverTimeout);
        if (mState != State.ADOPTED) {
            return;
        }
        mState = State.IDLE;
        ShortsPreview preview = mHost.preview();
        if (preview != null) {
            preview.onHandedOver();
        }
        Runnable onHandedOver = mOnHandedOver;
        mOnHandedOver = null;
        if (onHandedOver != null) {
            onHandedOver.run();
        }
        if (preview != null) {
            preview.onMainFirstFrame(); // the next Short may be prepared (after the quiet time)
        }
    }

    /**
     * The switch can't finish with a swap (the spare player failed, another video, the core didn't open it): the spare
     * player stops and leaves, the main player gets its sound back, the core opens videos the usual way.
     */
    private void fallBack() {
        mHandler.removeCallbacks(mOpenTimeout);

        if (mSlide != null) {
            ValueAnimator slide = mSlide;
            mSlide = null;
            slide.cancel();
        }

        ExoPlayer main = mHost.mainPlayer();
        ShortsPreview preview = mHost.preview();
        View mainSurface = mHost.mainSurface();

        if (main != null) {
            main.setVolume(mVolume);
            main.setPlaybackParameters(new PlaybackParameters(mBaseSpeed, mPitch));
        }
        if (mainSurface != null) {
            mainSurface.setTranslationY(0);
        }

        boolean wasSliding = mState == State.SLIDING;
        mState = State.IDLE;
        mTarget = null;

        if (wasSliding) {
            ShortsSidePanel panel = mHost.panel();
            if (panel != null) {
                panel.setSlideAside(false);
            }
        }

        if (preview != null) {
            preview.consumed(); // stops the spare player and parks its frame
        }

        mHandler.removeCallbacks(mDetachDiag);
        mHandler.postDelayed(mDetachDiag, DIAG_AFTER_MS);
    }

    // --- diagnostics: dropped frames and audio underruns of both players during and just after a live switch ---
    private ExoPlayer mDiagMain;
    private ExoPlayer mDiagSecond;
    private final Runnable mDetachDiag = this::detachDiag;

    private AnalyticsListener diagListener(final String who) {
        return new AnalyticsListener() {
            @Override
            public void onDroppedVideoFrames(AnalyticsListener.EventTime eventTime, int droppedFrames, long elapsedMs) {
                Log.d(TAG, "Glitch: %s dropped %s frames in %s ms (%s, +%s ms)", who, droppedFrames, elapsedMs, mState,
                        SystemClock.uptimeMillis() - mStartMs);
            }

            @Override
            public void onAudioUnderrun(AnalyticsListener.EventTime eventTime, int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
                Log.d(TAG, "Glitch: %s audio underrun (%s ms since the last feed) (%s, +%s ms)", who, elapsedSinceLastFeedMs, mState,
                        SystemClock.uptimeMillis() - mStartMs);
            }
        };
    }

    private final AnalyticsListener mDiagMainListener = diagListener("old main");
    private final AnalyticsListener mDiagSecondListener = diagListener("incoming");

    private void attachDiag(ExoPlayer main, ExoPlayer second) {
        mHandler.removeCallbacks(mDetachDiag);
        detachDiag();
        mDiagMain = main;
        mDiagSecond = second;
        main.addAnalyticsListener(mDiagMainListener);
        second.addAnalyticsListener(mDiagSecondListener);
    }

    private void detachDiag() {
        try {
            if (mDiagMain != null) {
                mDiagMain.removeAnalyticsListener(mDiagMainListener);
            }
            if (mDiagSecond != null) {
                mDiagSecond.removeAnalyticsListener(mDiagSecondListener);
            }
        } catch (RuntimeException e) {
            // the player is gone
        }
        mDiagMain = null;
        mDiagSecond = null;
    }

    /**
     * The screen is left (pause, background, PIP) or something else is opened: the spare player stops at once, the
     * main player gets its sound and speed back, nothing is opened.
     */
    void abort() {
        if (mState == State.IDLE) {
            return;
        }
        Log.d(TAG, "Live switch aborted in state %s", mState);
        if (mState == State.ADOPTED) {
            finishHandOver();
            return;
        }
        boolean slid = mState == State.SLIDING;
        mOpen = null; // not opened: the user left
        fallBack();
        if (slid) {
            mHost.onLiveCancelled();
        }
    }

    /** The screen goes away. */
    void release() {
        abort();
        detachDiag();
        mHandler.removeCallbacksAndMessages(null);
    }
}
