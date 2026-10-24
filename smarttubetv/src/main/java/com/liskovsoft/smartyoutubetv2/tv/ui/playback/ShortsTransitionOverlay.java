package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;

/**
 * Shorts switch, like the official app: the current Short (a snapshot of its last frame) stays until the next
 * one's first frame is rendered, then it slides out and the real, already playing video slides in from the
 * direction of navigation. No placeholder thumbnail (a black cover only if the new video is late).
 */
public class ShortsTransitionOverlay {
    // JoTube: the slide (owner 2026-10-24: "smoother, a really nice animation, no cheap tricks, it must not slow the switch
    // down, only mask it"). Device log: the next Short's first frame comes 230-600 ms after the key (mostly 300-400).
    // 380 ms: Material's full-screen transitions are 300-375 ms on a phone and ~30% longer on bigger screens (the
    // pictures travel further); it ends about when the new video is there. It starts at the key press.
    static final int SWIPE_DURATION_MS = 380;
    // the next Short's picture fades into its playing video once both are in place
    private static final int REVEAL_DURATION_MS = 160;
    // the outgoing Short recedes a little while it leaves (depth, not a gimmick)
    private static final float OUT_ALPHA = 0.7f;
    // the snapshot of the outgoing picture is taken at the key press; the main thread waits at most this long for it
    private static final int SNAPSHOT_SYNC_MS = 50;
    private static final int FADE_OUT_DURATION_MS = 120;
    private static final int FAILSAFE_HIDE_MS = 10_000;
    private static final int AT_SLIDE_START_TIMEOUT_MS = 400;
    private static final int SNAPSHOT_TIMEOUT_MS = 150; // the main thread can be busy ~70 ms at the switch
    private static final String TAG = "ShortsTransition";
    // JoTube: without the slide the old picture stays until the new Short's first frame; if that takes longer,
    // the new Short's blurred cover is shown after this time (a frozen old picture would look like a hang)
    private static final int CUT_COVER_AFTER_MS = 500;
    // a very short fade instead of a hard step: hides a frame of SurfaceView composition lag after the first frame
    private static final int CUT_FADE_MS = 60;
    private boolean mSlideEnabled = true;
    private boolean mCutPending; // no slide: waiting for the new first frame to switch at once
    private static final int GAP_DP = 24; // space between the outgoing and the incoming Short
    private static final int CORNER_DP = 9; // the frame's inner corners (ShortsSidePanel: 14 - 5)
    // Starts at once and settles softly (Material's standard curve: an on-screen element moving to a new place).
    // The strong deceleration puts the last ~100 ms of the slide into small, calm motion: the video arrives there.
    private static final android.animation.TimeInterpolator SWIPE_INTERPOLATOR = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
    private static android.os.HandlerThread sCopyThread; // PixelCopy answers here (the main thread waits for it)
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mFailsafeHide = () -> hide(false);
    private Video mPendingVideo;
    private Video mTargetVideo; // JoTube: the Short sliding in (its blurred background covers it until its frame)
    private boolean mPendingAsCover; // JoTube: the late cover is the Short's blurred cover, not black
    private final Runnable mPendingShow = () -> {
        if (mPendingVideo != null) {
            Video video = mPendingVideo;
            boolean asCover = mPendingAsCover;
            show(video, ShortsTransitionState.DIRECTION_NONE);
            if (asCover && this.mIn != null && video.isShorts) { // this.: the field is declared below
                ShortsBackground.loadCover(this.mIn, video);
            }
        }
    };
    private FrameLayout mContainer; // the whole screen, holds the two images
    private ImageView mIn; // the upcoming video (moves together with the surface)
    private ImageView mOut; // the current video, slides out
    private View mSurfaceRoot; // the real video
    private ValueAnimator mSwipe;
    private Runnable mSnapshotTimeout;
    private final Bitmap[] mSnapshotBitmaps = new Bitmap[2]; // JoTube: reused PixelCopy targets
    private int mSnapshotIndex;
    private Runnable mAfterSnapshot; // e.g. the player reset: only once the old picture is copied
    private Runnable mHoldStart; // the old picture is held until the new video's first frame
    private float mOutStart; // where the outgoing picture is (not 0 if pressed again during a slide)
    private boolean mSlideActive; // the pictures are moving
    private long mSlideStartMs;
    private float mSlideInStart;
    private Runnable mSlideEnd;
    private Runnable mRevealEnd;
    private SlideListener mSlideListener;
    private boolean mPageAside; // the frame stepped aside for the slide (onSlideStart without onSlideEnd yet)
    private boolean mInIsTarget; // mIn shows mTargetVideo's picture (not the black cover of show())

    /** JoTube: the frame and its border stand still while the pictures move: they step aside for the slide. */
    public interface SlideListener {
        void onSlideStart();

        void onSlideEnd();
    }

    public void setSlideListener(SlideListener listener) {
        mSlideListener = listener;
    }
    private int mSwipeId; // a newer swipe makes the callbacks of an older one void
    private boolean mIsShown;
    private boolean mFirstFrameRendered;
    private boolean mFrameKnown;
    private int mFrameWidth;
    private int mFrameHeight;
    private int mFrameOffsetX;
    private ShortsPreview mPreview; // JoTube: the real first frame of the next Short (second player), optional
    private boolean mPreviewEnabled;
    private boolean mPreviewActive; // the preview slides in with this swipe
    private Runnable mAtSlideStart; // the page (background, frame shape, panel) changes together with the slide
    private Runnable mHeldReshape; // a frame reshape from the format info that came before the old picture was covered
    private final Runnable mAtSlideStartTimeout = this::flushAtSlideStart;
    private boolean mOutFrozen; // a swipe is on: the outgoing picture keeps its shape
    private boolean mOutFramePending; // the new frame shape waits until the outgoing picture is gone
    // JoTube: diagnostics of the swipe (tag ShortsTransition): where the time goes, and how smooth the slide is
    private long mDiagCallMs;
    private long mDiagLastFrameMs;
    private long mDiagMaxGapMs;
    private int mDiagFrames;

    /**
     * JoTube: off = no slide between Shorts: the old picture stays until the new Short's first frame, then the new
     * one is simply there (a cut, like a channel change).
     */
    public void setSlideEnabled(boolean enabled) {
        mSlideEnabled = enabled;
    }

    public boolean isSlideEnabled() {
        return mSlideEnabled;
    }

    public void setPreview(ShortsPreview preview, boolean enabled) {
        mPreview = preview;
        mPreviewEnabled = enabled;
    }

    /**
     * Place the overlay right above the video surface and below the player controls.
     */
    public void attach(ViewGroup root) {
        if (root == null || mContainer != null) {
            return;
        }

        mContainer = new FrameLayout(root.getContext());
        mContainer.setVisibility(View.GONE);
        mContainer.setFocusable(false);
        mContainer.setClickable(false);

        mOut = createImage(mContainer);
        mIn = createImage(mContainer);

        mSurfaceRoot = root.findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.surface_root);
        int index = mSurfaceRoot != null ? root.indexOfChild(mSurfaceRoot) + 1 : Math.min(1, root.getChildCount());
        root.addView(mContainer, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (mPreview != null) {
            mPreview.attach(root, index + 1, mPreviewEnabled);
        }
    }

    private static ImageView createImage(FrameLayout container) {
        ImageView image = new ImageView(container.getContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        // the same rounded corners as the frame
        final float radius = CORNER_DP * container.getResources().getDisplayMetrics().density;
        image.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        image.setClipToOutline(true);
        image.setVisibility(View.GONE);
        container.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        return image;
    }

    /**
     * JoTube: in the Shorts frame the images cover only the frame (0 = the whole screen).
     */
    public void setFrame(int width, int height, int offsetX) {
        if (mPreview != null) {
            mPreview.setFrame(width, height, offsetX);
        }
        setImageSize(mIn, width, height);
        // JoTube: the frame is not in the middle (the right edge is fixed)
        if (mIn != null) {
            mIn.setTranslationX(offsetX);
        }

        // The outgoing picture keeps the shape of the old Short while it slides out (the new frame size arrives
        // ~30-100 ms after the key press, i.e. in the middle of the slide)
        mFrameKnown = true;
        mFrameWidth = width;
        mFrameHeight = height;
        mFrameOffsetX = offsetX;

        if (mOutFrozen) {
            mOutFramePending = true;
        } else {
            applyOutFrame();
        }
    }

    private void applyOutFrame() {
        mOutFramePending = false;
        mOutFrozen = false;

        if (!mFrameKnown) {
            return;
        }

        setImageSize(mOut, mFrameWidth, mFrameHeight);
        if (mOut != null) {
            mOut.setTranslationX(mFrameOffsetX);
        }
    }

    private static void setImageSize(ImageView image, int width, int height) {
        if (image == null || !(image.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) image.getLayoutParams();
        int newWidth = width > 0 ? width : ViewGroup.LayoutParams.MATCH_PARENT;
        int newHeight = height > 0 ? height : ViewGroup.LayoutParams.MATCH_PARENT;

        if (params.width != newWidth || params.height != newHeight || params.gravity != Gravity.CENTER) {
            params.width = newWidth;
            params.height = newHeight;
            params.gravity = Gravity.CENTER;
            image.setLayoutParams(params);
        }
    }

    public void detach() {
        mHandler.removeCallbacksAndMessages(null);
        if (mDrawListener != null && mContainer != null) {
            android.view.ViewTreeObserver observer = mContainer.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnDrawListener(mDrawListener); // now: the posted removal is gone with the callbacks
            }
            mDrawListener = null;
        }
        mSlideActive = false;
        mSlideEnd = null;
        mRevealEnd = null;
        pageBack();
        mPendingVideo = null;
        mHoldStart = null;
        mCutPending = false;
        mSnapshotTimeout = null;
        mAtSlideStart = null;
        Runnable heldReshape = mHeldReshape;
        mHeldReshape = null;
        if (heldReshape != null) {
            heldReshape.run();
        }
        mPreviewActive = false;
        if (mPreview != null) {
            mPreview.detach();
        }
        flushAfterSnapshot();
        mFirstFrameRendered = false;
        mSwipeId++;
        mOutFrozen = false;
        mOutFramePending = false;

        if (mContainer != null) {
            cancelAnimations();
            if (mContainer.getParent() instanceof ViewGroup) {
                ((ViewGroup) mContainer.getParent()).removeView(mContainer);
            }
            mContainer = null;
            mIn = null;
            mOut = null;
        }

        setSurfaceOffset(0);
        mSurfaceRoot = null;
        mIsShown = false;
    }

    /**
     * Preloaded video, no swipe: normally its first frame comes within a few frames, so nothing is shown.
     * If it takes longer (e.g. the data has to be loaded again), the thumbnail covers the old picture.
     */
    public void showIfSlow(Video video, long delayMs) {
        cancelPendingShow();

        if (mContainer == null || video == null) {
            return;
        }

        mFirstFrameRendered = false;
        mPendingVideo = video;
        mHandler.postDelayed(mPendingShow, delayMs);
    }

    /**
     * JoTube: like {@link #showIfSlow} but a late Short gets its blurred cover (not black).
     */
    public void showCoverIfSlow(Video video, long delayMs) {
        showIfSlow(video, delayMs);
        mPendingAsCover = mPendingVideo != null;
    }

    /**
     * Runs when the slide starts (or at once if there is no slide: hide, a newer swipe, a timeout), so the background,
     * the frame and the panel change at the same moment as the picture moves instead of at the key press.
     */
    public void runAtSlideStart(Runnable action) {
        flushAtSlideStart(); // an older one first
        if (action == null) {
            return;
        }
        if (mContainer == null) {
            action.run();
            return;
        }
        if (!mSlideEnabled && mCutPending) {
            // The old picture already covers the video (the hold ran before this was registered): change at once
            action.run();
            return;
        }
        mAtSlideStart = action;
        mHandler.removeCallbacks(mAtSlideStartTimeout);
        // without the slide the page changes with the picture: not before the late cover (CUT_COVER_AFTER_MS)
        mHandler.postDelayed(mAtSlideStartTimeout, mSlideEnabled ? AT_SLIDE_START_TIMEOUT_MS : CUT_COVER_AFTER_MS + 50);
    }

    private void flushAtSlideStart() {
        mHandler.removeCallbacks(mAtSlideStartTimeout);
        Runnable action = mAtSlideStart;
        mAtSlideStart = null;

        if (action != null) {
            action.run();
        }

        if (mSlideActive) {
            return; // a frame shape waits for the end of the slide (onSwipeEnd)
        }
        Runnable reshape = mHeldReshape;
        mHeldReshape = null;
        if (reshape != null) {
            reshape.run();
        }
    }

    /**
     * JoTube: a frame reshape (new aspect from the format info). While the old picture is still being copied, or the
     * page change waits for the slide start / the cut, it waits too: otherwise the old video is shown stretched in the
     * new frame shape before the snapshot covers it.
     */
    public void runReshape(Runnable reshape) {
        if (reshape == null) {
            return;
        }
        if (mContainer != null && (mSnapshotTimeout != null || mAtSlideStart != null || mSlideActive)) {
            mHeldReshape = reshape; // only the newest shape counts
            return;
        }
        reshape.run();
    }

    private void cancelPendingShow() {
        mHandler.removeCallbacks(mPendingShow);
        mPendingVideo = null;
        mPendingAsCover = false;
    }

    /**
     * A new video is about to load: its thumbnail covers the old picture (no animation).
     */
    public void show(Video video, int direction) {
        flushAtSlideStart();

        if (mContainer == null || video == null) {
            return;
        }

        prepare();
        setSurfaceOffset(0);
        pageBack();
        mInIsTarget = false;
        mFirstFrameRendered = false;
        // JoTube: black, never the thumbnail (a still image that pops away when the video starts)
        clearImage(mIn);
        mIn.setVisibility(View.VISIBLE);
        mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);
    }

    /**
     * The swipe from one Short to the next (direction: next = the new one comes from the bottom).
     * No placeholder image: the old picture stays still (a snapshot of its last frame) until the new video's
     * first frame is rendered, then the snapshot slides out and the real, already playing video slides in.
     *
     * @param videoView the view the current video is rendered on: its last frame is the outgoing picture.
     *                  Null if it's not on the surface anymore (the player was reset): the thumbnail is used.
     */
    // Diagnostics: main thread messages that take long while a swipe runs (what makes the slide stutter)
    private static final int WATCH_MS = 900;
    private static final int WATCH_MIN_MS = 10;
    private final android.os.Handler mWatchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.util.Printer mWatchPrinter = new android.util.Printer() {
        private long mStartMs;
        private String mWhat;

        @Override
        public void println(String x) {
            if (x == null) {
                return;
            }
            if (x.startsWith(">>>>> Dispatching")) {
                mStartMs = android.os.SystemClock.uptimeMillis();
                mWhat = x;
            } else if (x.startsWith("<<<<< Finished") && mWhat != null) {
                long duration = android.os.SystemClock.uptimeMillis() - mStartMs;
                if (duration >= WATCH_MIN_MS) {
                    Log.d(TAG, "Slow main thread message %s ms (+%s ms after swipe): %s", duration,
                            mStartMs - mDiagCallMs, mWhat.length() > 150 ? mWhat.substring(0, 150) : mWhat);
                }
                mWhat = null;
            }
        }
    };
    private final Runnable mStopWatch = () -> android.os.Looper.getMainLooper().setMessageLogging(null);

    private void watchMainThread() {
        mWatchHandler.removeCallbacks(mStopWatch);
        android.os.Looper.getMainLooper().setMessageLogging(mWatchPrinter);
        mWatchHandler.postDelayed(mStopWatch, WATCH_MS);
    }

    public void swipe(Video from, Video to, int direction, View videoView) {
        if (mContainer == null || to == null) {
            return;
        }

        Video previousTarget = mTargetVideo;
        mTargetVideo = to.isShorts ? to : null;
        mDiagCallMs = android.os.SystemClock.uptimeMillis();
        watchMainThread();

        int frameHeight = mIn.getLayoutParams() != null ? mIn.getLayoutParams().height : 0;
        int height = frameHeight > 0 ? frameHeight : mContainer.getHeight() > 0 ? mContainer.getHeight() :
                mContainer.getParent() instanceof View ? ((View) mContainer.getParent()).getHeight() : 0;

        if (direction == ShortsTransitionState.DIRECTION_NONE || height <= 0) {
            show(to, ShortsTransitionState.DIRECTION_NONE);
            return;
        }

        mOutFrozen = true;
        final float distance = height + GAP_DP * mContainer.getResources().getDisplayMetrics().density;

        if (mHoldStart != null) {
            // Still showing the old picture (the previous target wasn't ready yet): wait for this one instead
            mHandler.removeCallbacks(mHoldStart);
            mHoldStart = null;
            mFirstFrameRendered = false;
            mHandler.removeCallbacks(mFailsafeHide);
            mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);
            hold(++mSwipeId, direction, distance);
            return;
        }

        // JoTube: pressed again while the pictures move or the next Short's picture waits for its video: that picture
        // leaves from where it is now (the video is still out of sight)
        if (mSlideEnabled && mIsShown && mInIsTarget && (mSlideActive || (mIn.getVisibility() == View.VISIBLE && mRevealEnd == null))) {
            float outStart = currentInOffset();
            prepare();
            mOutStart = outStart;
            mFirstFrameRendered = false;
            final int swipeId = ++mSwipeId;
            applyOutFrame(); // the shape of the picture that is leaving (mIn's)
            mOutFrozen = true;
            clearImage(mOut);
            if (previousTarget != null) {
                loadThumbnail(mOut, previousTarget);
            }
            clearImage(mIn);
            mIn.setVisibility(View.GONE);
            mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);
            hold(swipeId, direction, distance);
            return;
        }

        // The black cover is on screen (the previous video hasn't shown a frame yet): that's the outgoing picture
        boolean coverOnScreen = mIsShown && !mFirstFrameRendered && mSnapshotTimeout == null &&
                mIn.getVisibility() == View.VISIBLE && mIn.getAlpha() > 0.5f;

        // Pressed again during the slide: the new outgoing picture starts where the video is now
        float outStart = 0;

        prepare();
        mOutStart = outStart;
        setSurfaceOffset(outStart);
        mFirstFrameRendered = false;
        final int swipeId = ++mSwipeId;
        clearImage(mIn);
        mIn.setVisibility(View.GONE);
        mHandler.postDelayed(mFailsafeHide, FAILSAFE_HIDE_MS);

        if (coverOnScreen) {
            clearImage(mOut);
            if (previousTarget != null) {
                ShortsBackground.loadCover(mOut, previousTarget); // what is on screen: that Short's cover
            }
            hold(swipeId, direction, distance);
            return;
        }

        if (videoView instanceof TextureView && ((TextureView) videoView).isAvailable()) {
            setOutImage(captureTexture((TextureView) videoView), from);
            hold(swipeId, direction, distance);
            return;
        }

        if (videoView instanceof SurfaceView && Build.VERSION.SDK_INT >= 24 && videoView.getWidth() > 0 &&
                videoView.getHeight() > 0 && ((SurfaceView) videoView).getHolder().getSurface().isValid()) {
            final Bitmap bitmap;
            try {
                // full size for the slide (the picture is seen moving for 380 ms), half for the cut
                int scale = mSlideEnabled ? 1 : 2;
                bitmap = obtainSnapshotBitmap(Math.max(1, videoView.getWidth() / scale), Math.max(1, videoView.getHeight() / scale));
            } catch (OutOfMemoryError e) {
                setOutImage(null, from);
                hold(swipeId, direction, distance);
                return;
            }

            if (mSlideEnabled) {
                // JoTube: copied now (the main thread is busy for 200+ ms right after this: an asynchronous answer
                // came that late and the slide with it)
                Bitmap frame = copySync((SurfaceView) videoView, bitmap);
                setOutImage(frame, from);
                Log.d(TAG, "Snapshot %s: +%s ms", frame != null ? "done" : "failed", android.os.SystemClock.uptimeMillis() - mDiagCallMs);
                hold(swipeId, direction, distance);
                return;
            }

            // The old video is still on the surface (the switch comes later): copy its picture (a few ms)
            mSnapshotTimeout = () -> {
                if (swipeId == mSwipeId) {
                    Log.d(TAG, "Snapshot timed out");
                    mSnapshotTimeout = null;
                    flushAfterSnapshot();
                    setOutImage(null, from);
                    hold(swipeId, direction, distance);
                }
            };
            mHandler.postDelayed(mSnapshotTimeout, SNAPSHOT_TIMEOUT_MS);

            try {
                PixelCopy.request((SurfaceView) videoView, bitmap, result -> {
                    // Void if timed out or a newer swipe started
                    if (swipeId != mSwipeId || mSnapshotTimeout == null) {
                        return;
                    }
                    mHandler.removeCallbacks(mSnapshotTimeout);
                    mSnapshotTimeout = null;
                    flushAfterSnapshot();
                    Log.d(TAG, "Snapshot done (result %s): +%s ms", result, android.os.SystemClock.uptimeMillis() - mDiagCallMs);
                    setOutImage(result == PixelCopy.SUCCESS ? bitmap : null, from);
                    hold(swipeId, direction, distance);
                }, mHandler);
            } catch (IllegalArgumentException e) {
                // The surface is gone: the timeout continues with the thumbnail
            }
            return;
        }

        setOutImage(null, from);
        hold(swipeId, direction, distance);
    }

    /**
     * The old picture stays in place until the new video's first frame (or the timeout: then it slides with
     * a black cover over the new video until its frame comes).
     */
    private void hold(int swipeId, int direction, float distance) {
        if (mContainer == null || swipeId != mSwipeId) {
            return;
        }

        mOut.setTranslationY(mOutStart);
        mOut.setVisibility(View.VISIBLE);

        if (!mSlideEnabled) {
            holdForCut(swipeId);
            return;
        }

        // JoTube: right after the key handling (which registers the page change for the slide start), ahead of the
        // heavy messages queued behind it
        mHandler.postAtFrontOfQueue(() -> startSwipe(swipeId, direction, distance));
    }

    /**
     * Copies the surface's picture on a helper thread and waits for it (at most {@link #SNAPSHOT_SYNC_MS}).
     */
    private Bitmap copySync(SurfaceView view, Bitmap bitmap) {
        if (sCopyThread == null) {
            sCopyThread = new android.os.HandlerThread("ShortsSnapshot");
            sCopyThread.start();
        }
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final int[] result = {-1};
        try {
            PixelCopy.request(view, bitmap, copyResult -> {
                result[0] = copyResult;
                done.countDown();
            }, new Handler(sCopyThread.getLooper()));
            if (done.await(SNAPSHOT_SYNC_MS, java.util.concurrent.TimeUnit.MILLISECONDS) && result[0] == PixelCopy.SUCCESS) {
                return bitmap;
            }
        } catch (IllegalArgumentException e) {
            // the surface is gone: the thumbnail
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Late or failed: the copy may still write into this bitmap later, never use it again
        for (int i = 0; i < mSnapshotBitmaps.length; i++) {
            if (mSnapshotBitmaps[i] == bitmap) {
                mSnapshotBitmaps[i] = null;
            }
        }
        return null;
    }

    /** Where the incoming picture is now (the slide runs on the RenderThread: computed from the time). */
    private float currentInOffset() {
        if (!mSlideActive) {
            return 0;
        }
        float t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - mSlideStartMs) / (float) SWIPE_DURATION_MS);
        return mSlideInStart * (1f - SWIPE_INTERPOLATOR.getInterpolation(t));
    }

    private void holdForCut(int swipeId) {
        // JoTube: the old picture (snapshot) is on screen now: the page takes the new Short's shape behind it at once
        // (frame, background, panel), the snapshot is fitted into the new frame. When the first frame comes the video
        // is already in its shape: before, the reshape ran at the cut (a layout on the busy main thread), so the new
        // video showed stretched in the old frame and the frame jumped late (owner: stretched, jerky, slow).
        mOutFrozen = false;
        if (mOutFramePending) {
            applyOutFrame();
        }
        flushAtSlideStart();

        if (mFirstFrameRendered) {
            cut(swipeId);
            return;
        }

        mCutPending = true;
        mHoldStart = () -> {
            mHoldStart = null;
            showTargetCover(swipeId);
        };
        mHandler.postDelayed(mHoldStart, CUT_COVER_AFTER_MS);
    }

    /**
     * No slide: the new Short's first frame is on screen, the old picture goes at once.
     */
    private void cut(int swipeId) {
        if (mContainer == null || swipeId != mSwipeId) {
            return;
        }

        Log.d(TAG, "Cut: +%s ms after swipe()", android.os.SystemClock.uptimeMillis() - mDiagCallMs);
        mCutPending = false;
        if (mHoldStart != null) {
            mHandler.removeCallbacks(mHoldStart);
            mHoldStart = null;
        }
        flushAtSlideStart(); // background, frame shape and panel change together with the picture
        mHandler.removeCallbacks(mFailsafeHide);
        mIsShown = false;
        mSwipeId++;
        cancelAnimations();
        setSurfaceOffset(0);
        mContainer.animate()
                .alpha(0f)
                .setDuration(CUT_FADE_MS)
                .withEndAction(this::reset)
                .start();
    }

    /**
     * No slide, and the new first frame is late: the new Short's blurred cover instead of the frozen old picture.
     * The cover fades out when the frame comes (hide).
     */
    private void showTargetCover(int swipeId) {
        if (mContainer == null || swipeId != mSwipeId) {
            return;
        }

        Log.d(TAG, "Cut: first frame late, cover at +%s ms", android.os.SystemClock.uptimeMillis() - mDiagCallMs);
        mCutPending = false;
        flushAtSlideStart();
        mOut.setVisibility(View.GONE);
        clearImage(mIn);
        if (mTargetVideo != null) {
            ShortsBackground.loadCover(mIn, mTargetVideo);
        }
        mIn.setAlpha(1f);
        mIn.setTranslationY(0);
        mIn.setVisibility(View.VISIBLE);
    }

    /**
     * Runs the action once the outgoing picture is copied (at once if no copy is pending).
     */
    public void runAfterSnapshot(Runnable action) {
        if (action == null) {
            return;
        }

        if (mSnapshotTimeout == null) {
            action.run();
            return;
        }

        flushAfterSnapshot(); // an older one first
        mAfterSnapshot = action;
    }

    private void flushAfterSnapshot() {
        Runnable action = mAfterSnapshot;
        mAfterSnapshot = null;

        if (action != null) {
            action.run();
        }
    }

    /**
     * JoTube: the PixelCopy target is reused while the size is the same (no ~1 MB allocation per swipe).
     * Two bitmaps in turn: the one shown in mOut is never the target of the next copy.
     */
    private Bitmap obtainSnapshotBitmap(int width, int height) {
        mSnapshotIndex = 1 - mSnapshotIndex;
        Bitmap bitmap = mSnapshotBitmaps[mSnapshotIndex];

        if (bitmap == null || bitmap.isRecycled() || !bitmap.isMutable() || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mSnapshotBitmaps[mSnapshotIndex] = bitmap;
        }

        return bitmap;
    }

    private static Bitmap captureTexture(TextureView view) {
        try {
            return view.getBitmap(Math.max(1, view.getWidth() / 2), Math.max(1, view.getHeight() / 2));
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private void setOutImage(Bitmap frame, Video from) {
        if (mOut == null) {
            return;
        }

        if (frame != null) {
            clearImage(mOut); // a late thumbnail load must not replace the snapshot
            mOut.setImageBitmap(frame);
        } else if (from != null) {
            loadThumbnail(mOut, from);
        }
    }

    /**
     * JoTube: the slide. The outgoing picture (a copy of the last frame) and the next Short's own picture (sharp, in
     * its frame shape) move together as one strip, on the RenderThread so the main thread's work at the switch can't
     * stop them. The video waits out of sight meanwhile; when both the slide and the first frame are done, it takes
     * the picture's place and the picture fades into it.
     */
    private void startSwipe(int swipeId, int direction, float distance) {
        if (mContainer == null || swipeId != mSwipeId) {
            return;
        }

        final float outStart = mOutStart;
        final float outEnd = outStart - direction * distance;
        final float inStart = direction * distance;

        mOut.setVisibility(View.VISIBLE);
        mOut.setTranslationY(outStart);
        mOut.setAlpha(1f);
        clearImage(mIn);
        if (mTargetVideo != null) {
            loadThumbnail(mIn, mTargetVideo); // preloaded: from the memory cache, at once
        }
        mIn.setVisibility(View.VISIBLE);
        mIn.setAlpha(1f);
        mIn.setTranslationY(inStart);
        mInIsTarget = mTargetVideo != null;
        mPreviewActive = false;

        // The page (background, frame shape, panel) changes now, with the first moving frame
        flushAtSlideStart();
        // The outgoing picture covers the video's place: the video goes out of sight until it is revealed
        setSurfaceOffset(offscreenOffset());
        if (mSlideListener != null && !mPageAside) {
            mPageAside = true;
            mSlideListener.onSlideStart();
        }

        mSlideActive = true;
        mSlideStartMs = android.os.SystemClock.uptimeMillis();
        mSlideInStart = inStart;
        RenderThreadAnimation.animate(mOut).translationY(outEnd).alpha(OUT_ALPHA)
                .setDuration(SWIPE_DURATION_MS).setInterpolator(SWIPE_INTERPOLATOR).start();
        RenderThreadAnimation.animate(mIn).translationY(0)
                .setDuration(SWIPE_DURATION_MS).setInterpolator(SWIPE_INTERPOLATOR).start();
        Log.d(TAG, "Slide starts: +%s ms after swipe(), RenderThread: %s, first frame already rendered: %s",
                mSlideStartMs - mDiagCallMs, RenderThreadAnimation.isAvailable(), mFirstFrameRendered);

        // No end listener (it would take the animation off the RenderThread): the clean-up comes by time, counted
        // from the frame that hands the animation to the RenderThread (it may be drawn a little after this)
        mSlideEnd = () -> {
            mSlideEnd = null;
            if (swipeId == mSwipeId) {
                onSwipeEnd();
            }
        };
        mHandler.postDelayed(mSlideEnd, SWIPE_DURATION_MS + 500); // only if no frame is drawn at all
        final android.view.ViewTreeObserver observer = mContainer.getViewTreeObserver();
        final android.view.ViewTreeObserver.OnDrawListener[] self = new android.view.ViewTreeObserver.OnDrawListener[1];
        final boolean[] fired = {false};
        self[0] = () -> {
            if (fired[0]) {
                return;
            }
            fired[0] = true;
            final android.view.ViewTreeObserver.OnDrawListener listener = self[0];
            if (mDrawListener == listener) {
                mDrawListener = null;
            }
            mHandler.post(() -> { // not removable while the draw dispatches
                if (observer.isAlive()) {
                    observer.removeOnDrawListener(listener);
                }
            });
            if (swipeId != mSwipeId || mSlideEnd == null) {
                return;
            }
            mSlideStartMs = android.os.SystemClock.uptimeMillis();
            Log.d(TAG, "Slide drawn: +%s ms after swipe()", mSlideStartMs - mDiagCallMs);
            mHandler.removeCallbacks(mSlideEnd);
            mHandler.postDelayed(mSlideEnd, SWIPE_DURATION_MS + 16);
        };
        removeDrawListener();
        mDrawListener = self[0];
        observer.addOnDrawListener(mDrawListener);
    }

    private android.view.ViewTreeObserver.OnDrawListener mDrawListener;

    private void removeDrawListener() {
        if (mDrawListener != null && mContainer != null && mContainer.getViewTreeObserver().isAlive()) {
            final android.view.ViewTreeObserver.OnDrawListener listener = mDrawListener;
            final android.view.ViewTreeObserver observer = mContainer.getViewTreeObserver();
            mHandler.post(() -> {
                if (observer.isAlive()) {
                    observer.removeOnDrawListener(listener);
                }
            });
        }
        mDrawListener = null;
    }

    private void pageBack() {
        if (mPageAside) {
            mPageAside = false;
            if (mSlideListener != null) {
                mSlideListener.onSlideEnd();
            }
        }
    }

    private float offscreenOffset() {
        View parent = mContainer != null && mContainer.getParent() instanceof View ? (View) mContainer.getParent() : null;
        int height = parent != null && parent.getHeight() > 0 ? parent.getHeight() : 2160;
        return height * 2f;
    }

    private void onSwipeEnd() {
        if (mContainer == null) {
            return;
        }

        mSlideActive = false;
        flushAtSlideStart(); // a frame shape that came during the slide (not preloaded): now, before the reveal
        Log.d(TAG, "Slide ends: +%s ms after swipe(), first frame rendered: %s",
                android.os.SystemClock.uptimeMillis() - mDiagCallMs, mFirstFrameRendered);
        mOut.animate().cancel();
        mIn.animate().cancel();
        mOut.setVisibility(View.GONE);
        clearImage(mOut);
        mOut.setTranslationY(0);
        mOut.setAlpha(1f);
        mIn.setTranslationY(0);
        if (mOutFramePending || mOutFrozen) {
            applyOutFrame();
        }

        if (mFirstFrameRendered) {
            reveal();
        }
        // else: the next Short's picture stays in place until its first frame (hide)
    }

    /**
     * The new video takes its place under its picture, the picture fades into it.
     */
    private void reveal() {
        if (mContainer == null || mRevealEnd != null) {
            return;
        }

        final int swipeId = mSwipeId;
        setSurfaceOffset(0);
        pageBack();
        Log.d(TAG, "Reveal: +%s ms after swipe()", android.os.SystemClock.uptimeMillis() - mDiagCallMs);
        mHandler.removeCallbacks(mFailsafeHide);
        RenderThreadAnimation.animate(mIn).alpha(0f).setDuration(REVEAL_DURATION_MS)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        mRevealEnd = () -> {
            mRevealEnd = null;
            if (swipeId == mSwipeId) {
                mIsShown = false;
                reset();
            }
        };
        mHandler.postDelayed(mRevealEnd, REVEAL_DURATION_MS + 16);
    }

    private void prepare() {
        mHandler.removeCallbacks(mFailsafeHide);
        cancelPendingShow();
        cancelAnimations();

        if (mSnapshotTimeout != null) {
            mHandler.removeCallbacks(mSnapshotTimeout);
            mSnapshotTimeout = null;
        }
        flushAfterSnapshot();
        if (mHoldStart != null) {
            mHandler.removeCallbacks(mHoldStart);
            mHoldStart = null;
        }
        mCutPending = false;

        mContainer.setVisibility(View.VISIBLE);
        mContainer.setAlpha(1f);
        mIn.setAlpha(1f);
        mIn.setTranslationY(0);
        mOut.setTranslationY(0);
        mOut.setVisibility(View.GONE);
        mIsShown = true;
    }

    private void setSurfaceOffset(float offset) {
        if (mSurfaceRoot != null && mSurfaceRoot.getTranslationY() != offset) {
            mSurfaceRoot.setTranslationY(offset);
        }
    }

    private static void loadThumbnail(ImageView image, Video video) {
        try {
            // Shorts: the vertical thumbnail. Regular videos: the HD one (the card image has black bars).
            // Original size, no transformation: the same memory cache entry as the preload (shown at once).
            String url = video.isShorts ? ShortsTransitionState.getThumbnailUrl(video.videoId) :
                    "https://i.ytimg.com/vi/" + video.videoId + "/maxresdefault.jpg";
            Glide.with(image)
                    .load(url)
                    .override(Target.SIZE_ORIGINAL)
                    .dontTransform()
                    .error(Glide.with(image).load(video.getCardImageUrl()))
                    .into(image); // no dontAnimate(): it's an option of the cache key (into() has no transition anyway)
        } catch (IllegalArgumentException e) {
            // Activity is destroyed
        }
    }

    /**
     * The first frame of the new video is on screen (animate = true), or the overlay isn't needed (false).
     */
    public void hide(boolean animate) {
        flushAtSlideStart();

        if (animate) {
            mFirstFrameRendered = true;
            if (mDiagCallMs != 0) {
                Log.d(TAG, "First frame: +%s ms after swipe()", android.os.SystemClock.uptimeMillis() - mDiagCallMs);
            }
        }

        cancelPendingShow();

        if (mContainer == null || !mIsShown) {
            return;
        }

        if (animate && mSnapshotTimeout != null) {
            // The snapshot is due in a few ms: the swipe starts right after it (the frame is ready)
            return;
        }

        if (animate && mCutPending) {
            // No slide: the new frame is there, switch now
            cut(mSwipeId);
            return;
        }

        if (animate && mHoldStart != null) {
            // The new video is ready: slide now
            Runnable start = mHoldStart;
            mHandler.removeCallbacks(start);
            start.run();
            return;
        }

        if (animate && mSlideEnabled && (mSlideActive || mRevealEnd != null)) {
            // JoTube: in the middle of the slide (the video is revealed when it ends), or already being revealed
            return;
        }

        if (animate && mSlideEnabled && mIn.getVisibility() == View.VISIBLE && mOut.getVisibility() != View.VISIBLE) {
            // The slide is over and the next Short's picture waited for its video: now
            reveal();
            return;
        }

        mHandler.removeCallbacks(mFailsafeHide);
        mIsShown = false;
        mSwipeId++;
        cancelAnimations();

        if (mSnapshotTimeout != null) {
            mHandler.removeCallbacks(mSnapshotTimeout);
            mSnapshotTimeout = null;
        }
        flushAfterSnapshot();
        if (mHoldStart != null) {
            mHandler.removeCallbacks(mHoldStart);
            mHoldStart = null;
        }
        mCutPending = false;

        if (!animate) {
            reset();
            return;
        }

        setSurfaceOffset(0);
        mContainer.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_DURATION_MS)
                .withEndAction(this::reset)
                .start();
    }

    /** JoTube: the pictures are moving (the video is out of sight). */
    public boolean isSliding() {
        return mSlideActive;
    }

    public boolean isShown() {
        return mIsShown;
    }

    private void cancelAnimations() {
        if (mSwipe != null) {
            ValueAnimator swipe = mSwipe;
            mSwipe = null;
            swipe.cancel();
        }
        if (mSlideActive) {
            mSlideActive = false;
            // a frame shape held for the slide's end (the slide is cut short here)
            Runnable reshape = mHeldReshape;
            mHeldReshape = null;
            if (reshape != null) {
                reshape.run();
            }
        }
        if (mSlideEnd != null) {
            mHandler.removeCallbacks(mSlideEnd);
            mSlideEnd = null;
        }
        if (mRevealEnd != null) {
            mHandler.removeCallbacks(mRevealEnd);
            mRevealEnd = null;
        }
        if (mContainer != null) {
            mContainer.animate().cancel();
        }
        if (mIn != null) {
            mIn.animate().cancel();
        }
        if (mOut != null) {
            mOut.animate().cancel();
        }
    }

    private void reset() {
        if (mContainer == null || mIsShown) {
            return;
        }

        pageBack(); // e.g. hidden without a reveal (error, another video)
        mInIsTarget = false;
        removeDrawListener();

        if (mPreviewActive && mPreview != null) {
            mPreview.consumed();
            mPreviewActive = false;
        }
        setSurfaceOffset(0);
        mContainer.setVisibility(View.GONE);
        mContainer.setAlpha(1f);
        mIn.setAlpha(1f);
        mIn.setTranslationY(0);
        mOut.setTranslationY(0);
        mIn.setVisibility(View.GONE);
        mOut.setVisibility(View.GONE);
        clearImage(mIn);
        clearImage(mOut);
        if (mOutFramePending || mOutFrozen) {
            applyOutFrame();
        }
    }

    private static void clearImage(ImageView image) {
        try {
            Glide.with(image).clear(image);
        } catch (IllegalArgumentException e) {
            // Activity is destroyed
        }
        image.setImageDrawable(null);
        ShortsBackground.clearCover(image); // black and FIT_CENTER again
    }
}
