package com.liskovsoft.smartyoutubetv2.tv.ui.playback;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Dynamic background for Shorts, like the official app: a blurred, darkened version of the video's
 * thumbnail fills the area next to the vertical video (downscaled + box blurred, no RenderScript).
 */
public class ShortsBackground {
    // Small bitmap, real blur on top of it (smooth after upscaling, no visible pixels)
    private static final int BLUR_WIDTH = 120;
    private static final int BLUR_HEIGHT = 214;
    private static final int BLUR_RADIUS = 10;
    private static final int DIM_COLOR = Color.argb(150, 0, 0, 0);
    private static final int FADE_DURATION_MS = 250;
    private ImageView mView;
    private String mVideoId;

    /**
     * Placed behind the video surface (index 0 of the fragment root).
     */
    public void attach(ViewGroup root) {
        if (root == null || mView != null) {
            return;
        }

        mView = new ImageView(root.getContext());
        mView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mView.setColorFilter(DIM_COLOR, PorterDuff.Mode.SRC_ATOP);
        mView.setVisibility(View.GONE);
        mView.setFocusable(false);
        mView.setClickable(false);

        root.addView(mView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void detach() {
        if (mView != null) {
            if (mView.getParent() instanceof ViewGroup) {
                ((ViewGroup) mView.getParent()).removeView(mView);
            }
            mView = null;
        }

        mVideoId = null;
    }

    public void show(Video video) {
        if (mView == null || video == null || video.videoId == null) {
            return;
        }

        if (video.videoId.equals(mVideoId) && mView.getVisibility() == View.VISIBLE) {
            return;
        }

        mVideoId = video.videoId;
        mView.setVisibility(View.VISIBLE);

        try {
            blurred(Glide.with(mView), video.videoId)
                    .error(Glide.with(mView).load(video.getCardImageUrl()).override(BLUR_WIDTH, BLUR_HEIGHT)
                            .transform(new BlurTransformation(BLUR_RADIUS)))
                    .transition(DrawableTransitionOptions.withCrossFade(FADE_DURATION_MS))
                    .into(mView);
        } catch (IllegalArgumentException e) {
            // Activity is destroyed
        }
    }

    /**
     * JoTube: the same request as in show() (same memory cache key): url, size and blur.
     */
    private static RequestBuilder<Drawable> blurred(RequestManager glide, String videoId) {
        return glide
                .load(ShortsTransitionState.getThumbnailUrl(videoId))
                .override(BLUR_WIDTH, BLUR_HEIGHT)
                .transform(new BlurTransformation(BLUR_RADIUS));
    }

    /**
     * JoTube: blur the backgrounds of the next Shorts in advance, so a switch shows them at once (memory cache).
     */
    public static void preload(Context context, List<String> videoIds) {
        if (context == null || videoIds == null) {
            return;
        }

        for (String videoId : videoIds) {
            if (videoId == null || !sPreloaded.add(videoId)) {
                continue;
            }

            if (sPreloaded.size() > 100) {
                Iterator<String> iterator = sPreloaded.iterator();
                iterator.next();
                iterator.remove();
            }

            try {
                blurred(Glide.with(context.getApplicationContext()), videoId).preload(BLUR_WIDTH, BLUR_HEIGHT);
            } catch (RuntimeException e) {
                // optional
            }
        }
    }

    private static final LinkedHashSet<String> sPreloaded = new LinkedHashSet<>();

    public void hide() {
        mVideoId = null;

        if (mView != null) {
            mView.setVisibility(View.GONE);
            mView.setImageDrawable(null);
        }
    }
}
