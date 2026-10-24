package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

/**
 * JoTube: the Shorts switch with two players (owner 2026-10-24: "no stills, no black frames, it must be smooth").
 * <ol>
 * <li>The next Short waits in the second player ({@link ShortsPreview}): prepared with sound, paused on its first
 * frame, out of sight.</li>
 * <li>Key press: it starts playing at once and slides in while the current Short keeps playing and slides out; the sound
 * crosses over. Both are moving video. The core's loading of the new Short (200-370 ms of main thread work, device log)
 * waits until the slide is over, so nothing stops the slide.</li>
 * <li>Then the main player loads the same Short behind the second one, silent, and catches up with it (a little
 * faster or slower) until both show the same moment; then it takes over (same picture, same sound) and the second
 * player is free for the next Short.</li>
 * </ol>
 * Everything runs on the main thread; the state is only touched there.
 */
final class ShortsLiveSwitch {
    private static final String TAG = "ShortsLive";
    // Moving video, not a still: a touch longer than the picture slide; the strong deceleration keeps it calm at the end
    static final int SLIDE_MS = 420;
    private static final int GAP_DP = 24;
    private static final long SYNC_POLL_MS = 32;
    private static final long SYNC_TIMEOUT_MS = 6_000; // the main player doesn't catch up: take over anyway
    private static final long LOAD_TIMEOUT_MS = 8_000; // no first frame of the main player at all
    private static final long SYNC_TOLERANCE_MS = 25; // under one frame at 30 fps
    private static final long SEEK_LEAD_MS = 300; // the main player seeks a bit ahead (its seek takes about that long)

    interface Host {
        ExoPlayer mainPlayer();

        View mainSurface();

        ShortsPreview preview();

        ShortsSidePanel panel();

        /** The page of the new Short (background, title, channel): at the start of the slide. */
        void onLivePage(Video next);
    }

    private enum State {IDLE, SLIDING, LOADING, SYNCING}

    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private State mState = State.IDLE;
    private ValueAnimator mSlide;
    private Runnable mOpen;
    private Video mTarget;
    private float mTargetAspect;
    private float mVolume = 1f; // the main player's volume before the switch
    private PlaybackParameters mParameters; // the main player's speed for the new Short (set by the core)
    private long mSyncStartMs;
    private long mStartMs;
    private final Runnable mPoll = this::poll;
    private final Runnable mLoadTimeout = () -> {
        Log.w(TAG, "The main player didn't start the Short: take over now");
        swap();
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
        if (mState != State.IDLE) {
            // Still handing the last one over: finish that at once, this swipe takes the usual way
            swap();
            return false;
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
        preview.setFrame(frame[0], frame[1], frame[2]);

        if (!preview.startLive()) {
            return false;
        }

        mStartMs = SystemClock.uptimeMillis();
        mTarget = next;
        mTargetAspect = aspect;
        mOpen = open;
        mVolume = main.getVolume();
        incomingPlayer.setVolume(0f);

        final float distance = frame[1] + GAP_DP * mainSurface.getResources().getDisplayMetrics().density;
        incoming.animate().cancel();
        incoming.setAlpha(1f);
        incoming.setTranslationY(distance);

        mHost.onLivePage(next);
        panel.setSlideAside(true);

        mState = State.SLIDING;
        Log.d(TAG, "Live switch to %s: slide starts", next.videoId);

        mSlide = ValueAnimator.ofFloat(0f, 1f);
        mSlide.setDuration(SLIDE_MS);
        mSlide.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        mSlide.addUpdateListener(animation -> {
            float position = (float) animation.getAnimatedValue();
            float time = animation.getAnimatedFraction();
            mainSurface.setTranslationY(-distance * position);
            incoming.setTranslationY(distance * (1f - position));
            // the sound crosses over with the time (not the position): even, no dip
            main.setVolume(mVolume * (1f - time));
            incomingPlayer.setVolume(mVolume * time);
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

    boolean isSliding() {
        return mState == State.SLIDING;
    }

    private void onSlideEnd() {
        mSlide = null;
        ExoPlayer main = mHost.mainPlayer();
        ShortsPreview preview = mHost.preview();
        Log.d(TAG, "Slide ends: +%s ms", SystemClock.uptimeMillis() - mStartMs);

        if (main != null) {
            main.setVolume(0f); // it loads the new Short silently behind the second player
        }
        if (preview != null && preview.getPlayer() != null) {
            preview.getPlayer().setVolume(mVolume);
        }
        if (preview != null && preview.getView() != null) {
            preview.getView().setTranslationY(0);
        }

        // The main player goes back in place, behind the second player (same frame): it is covered until it takes over
        View mainSurface = mHost.mainSurface();
        if (mainSurface != null) {
            mainSurface.setTranslationY(0);
        }

        ShortsSidePanel panel = mHost.panel();
        if (panel != null) {
            panel.setVideoAspect(mTargetAspect); // frame, border and the main player's frame: the new shape
            panel.setSlideAside(false);
        }

        mState = State.LOADING;
        mHandler.postDelayed(mLoadTimeout, LOAD_TIMEOUT_MS);
        Runnable open = mOpen;
        mOpen = null;
        if (open != null) {
            open.run(); // the core opens the Short now (the heavy part)
        }
    }

    /**
     * The core starts {@code video} in the main player (resetPlayerState).
     *
     * @return true: it is the Short of the live switch (nothing to cover, the second player shows it)
     */
    boolean onMainStarting(Video video) {
        if (mState == State.IDLE || mState == State.SLIDING) {
            return false;
        }

        if (video == null || mTarget == null || !mTarget.videoId.equals(video.videoId)) {
            Log.d(TAG, "Another video starts: hand over at once");
            swap();
            return false;
        }

        ExoPlayer main = mHost.mainPlayer();
        if (main != null) {
            main.setVolume(0f);
        }
        return true;
    }

    /** The main player shows the new Short's first frame (behind the second player). */
    void onMainFirstFrame() {
        if (mState != State.LOADING) {
            return;
        }

        ExoPlayer main = mHost.mainPlayer();
        ShortsPreview preview = mHost.preview();
        ExoPlayer second = preview != null ? preview.getPlayer() : null;

        if (main == null || second == null) {
            swap();
            return;
        }

        mHandler.removeCallbacks(mLoadTimeout);
        mParameters = main.getPlaybackParameters();
        main.setVolume(0f);
        long target = second.getCurrentPosition() + SEEK_LEAD_MS;
        Log.d(TAG, "Main player ready +%s ms: seeks to %s ms to meet the second player", SystemClock.uptimeMillis() - mStartMs, target);
        main.seekTo(target);
        mState = State.SYNCING;
        mSyncStartMs = SystemClock.uptimeMillis();
        mHandler.postDelayed(mPoll, SYNC_POLL_MS);
    }

    private void poll() {
        if (mState != State.SYNCING) {
            return;
        }

        ExoPlayer main = mHost.mainPlayer();
        ShortsPreview preview = mHost.preview();
        ExoPlayer second = preview != null ? preview.getPlayer() : null;

        if (main == null || second == null) {
            swap();
            return;
        }

        main.setVolume(0f); // the core may set it while loading

        if (SystemClock.uptimeMillis() - mSyncStartMs > SYNC_TIMEOUT_MS) {
            Log.w(TAG, "No meeting point in time: take over now");
            swap();
            return;
        }

        if (!main.isPlaying() || !second.isPlaying()) {
            mHandler.postDelayed(mPoll, SYNC_POLL_MS); // e.g. buffering after the seek
            return;
        }

        long diff = main.getCurrentPosition() - second.getCurrentPosition();
        float base = mParameters != null ? mParameters.speed : 1f;

        if (Math.abs(diff) <= SYNC_TOLERANCE_MS) {
            Log.d(TAG, "Same moment (%s ms apart) +%s ms: the main player takes over", diff, SystemClock.uptimeMillis() - mStartMs);
            swap();
            return;
        }

        // behind: a little faster, ahead: a little slower (silent and hidden, so nobody hears or sees it)
        float factor = diff < 0 ? (diff < -150 ? 1.25f : 1.08f) : (diff > 150 ? 0.8f : 0.92f);
        main.setPlaybackParameters(new PlaybackParameters(base * factor, mParameters != null ? mParameters.pitch : 1f));
        mHandler.postDelayed(mPoll, SYNC_POLL_MS);
    }

    /**
     * The main player takes over: its speed and sound back, it comes to the front, the second player is free.
     */
    void swap() {
        if (mState == State.IDLE) {
            return;
        }

        mHandler.removeCallbacks(mPoll);
        mHandler.removeCallbacks(mLoadTimeout);

        if (mSlide != null) {
            ValueAnimator slide = mSlide;
            mSlide = null;
            slide.cancel();
        }

        ExoPlayer main = mHost.mainPlayer();
        final ShortsPreview preview = mHost.preview();
        View mainSurface = mHost.mainSurface();

        if (main != null) {
            if (mParameters != null) {
                main.setPlaybackParameters(mParameters);
            }
            main.setVolume(mVolume);
        }
        if (preview != null && preview.getPlayer() != null) {
            preview.getPlayer().setVolume(0f);
        }
        if (mainSurface != null) {
            mainSurface.setTranslationY(0);
        }

        boolean wasSliding = mState == State.SLIDING;
        Runnable open = mOpen;
        mOpen = null;
        mState = State.IDLE;
        mParameters = null;
        mTarget = null;

        if (wasSliding) {
            ShortsSidePanel panel = mHost.panel();
            if (panel != null) {
                panel.setVideoAspect(mTargetAspect);
                panel.setSlideAside(false);
            }
        }

        // The second player goes a few frames later: the main player's picture is in place under it by then
        if (preview != null) {
            mHandler.postDelayed(preview::consumed, 50);
        }

        if (open != null) {
            open.run(); // cut short during the slide: the core still has to open the Short
        }
    }

    /** The screen goes away. */
    void release() {
        mHandler.removeCallbacksAndMessages(null);
        if (mSlide != null) {
            mSlide.cancel();
            mSlide = null;
        }
        mState = State.IDLE;
        mOpen = null;
        mTarget = null;
    }
}
