package com.liskovsoft.smartyoutubetv2.mobile.ui.widgets;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.Player;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.playback.MediaPlaybackService;
import com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayer;
import com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayerActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastRepository;

import java.util.function.BooleanSupplier;

/**
 * The mini player of the music and podcast parts: the song or episode that plays without its player screen.
 * Tap = full screen again. Only shown where {@code allowed} says so.
 */
public class MiniPlayerBar extends LinearLayout {
    private final Activity mActivity;
    private final BooleanSupplier mAllowed;
    private final ImageView mCover, mPlay;
    private final TextView mTitle, mAuthor;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Object mShown;
    private boolean mPodcast;
    private float mProgress; // how far the song / episode is, drawn as a thin line at the bottom
    private final android.graphics.Paint mTrackPaint = new android.graphics.Paint();
    private final android.graphics.Paint mProgressPaint = new android.graphics.Paint();
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mHandler.postDelayed(this, 500);
        }
    };

    public MiniPlayerBar(Activity activity, BooleanSupplier allowed) {
        super(activity);
        mActivity = activity;
        mAllowed = allowed;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(activity.getColor(R.color.yt_surface));
        setPadding(Ui.dp(activity, 8), Ui.dp(activity, 6), Ui.dp(activity, 4), Ui.dp(activity, 6));
        setVisibility(GONE);
        mTrackPaint.setColor(0x33FFFFFF);
        mProgressPaint.setColor(0xFFFFFFFF);

        mCover = new ImageView(activity);
        mCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.makeRounded(mCover, Ui.dp(activity, 6));
        addView(mCover, new LayoutParams(Ui.dp(activity, 44), Ui.dp(activity, 44)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(VERTICAL);
        texts.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 8), 0);
        mTitle = new TextView(activity);
        mTitle.setTextColor(activity.getColor(R.color.yt_text));
        mTitle.setTextSize(14);
        mTitle.setMaxLines(1);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(mTitle);
        mAuthor = new TextView(activity);
        mAuthor.setTextColor(activity.getColor(R.color.yt_text_secondary));
        mAuthor.setTextSize(12);
        mAuthor.setMaxLines(1);
        mAuthor.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(mAuthor);
        addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mPlay = new ImageView(activity);
        mPlay.setImageResource(R.drawable.ic_pause);
        mPlay.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 10), Ui.dp(activity, 10), Ui.dp(activity, 10));
        mPlay.setOnClickListener(v -> {
            Player player = mPodcast ? MediaPlaybackService.getPodcastPlayer() : MediaPlaybackService.getPlayer();
            if (player != null) {
                player.setPlayWhenReady(!player.getPlayWhenReady());
            }
        });
        addView(mPlay, new LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));

        // JoTube: a light, smaller cross in the secondary text color with a round touch feedback
        ImageView close = new ImageView(activity);
        close.setImageResource(R.drawable.ic_close_thin);
        close.setImageTintList(android.content.res.ColorStateList.valueOf(activity.getColor(R.color.yt_text_secondary)));
        close.setBackgroundResource(R.drawable.bg_icon_round_ripple);
        close.setPadding(Ui.dp(activity, 13), Ui.dp(activity, 13), Ui.dp(activity, 13), Ui.dp(activity, 13));
        close.setContentDescription(activity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> {
            if (mPodcast) {
                PodcastPlayer.stop();
            } else {
                PlaybackActivity.closeOrphan();
            }
            update();
        });
        addView(close, new LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));

        setOnClickListener(v -> {
            if (mPodcast) {
                activity.startActivity(new Intent(activity, PodcastPlayerActivity.class));
            } else {
                activity.startActivity(new Intent(activity, PlaybackActivity.class).putExtra(PlaybackActivity.EXTRA_FROM_NOTIFICATION, true));
            }
        });
    }

    private void update() {
        Player player = MediaPlaybackService.getPlayer();
        Video video = MediaPlaybackService.getVideo();
        boolean showVideo = player != null && video != null && PlaybackActivity.hasOrphan();

        Player podcastPlayer = MediaPlaybackService.getPodcastPlayer();
        PodcastRepository.Episode episode = PodcastPlayer.current();
        boolean podcastAlive = podcastPlayer != null && episode != null && PodcastPlayer.isActive();
        // both alive (one paused): show the one that actually plays
        boolean showPodcast = podcastAlive && (!showVideo || (podcastPlayer.getPlayWhenReady() && !player.getPlayWhenReady()));
        showVideo = showVideo && !showPodcast;

        boolean visible = (showVideo || showPodcast) && mAllowed.getAsBoolean();
        setVisibility(visible ? VISIBLE : GONE);
        if (!showVideo && !showPodcast) {
            mShown = null;
            return;
        }

        mPodcast = showPodcast;
        Object key = showPodcast ? episode : video;
        if (mShown != key) {
            mShown = key;
            mTitle.setText(showPodcast ? episode.title : video.getTitle());
            mAuthor.setText(showPodcast ? episode.feedTitle : video.getAuthor());
            String image = showPodcast ? episode.image : video.getCardImageUrl();
            if (image != null) {
                Glide.with(mCover).load(image).into(mCover);
            } else {
                mCover.setImageDrawable(null);
            }
        }
        Player active = showPodcast ? podcastPlayer : player;
        mPlay.setImageResource(active.getPlayWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play);
        long duration = active.getDuration();
        float progress = duration > 0 ? Math.min(1f, Math.max(0f, active.getCurrentPosition() / (float) duration)) : 0f;
        if (Math.abs(progress - mProgress) > 0.0005f) {
            mProgress = progress;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        super.dispatchDraw(canvas);
        float height = Ui.dp(getContext(), 2);
        float bottom = getHeight();
        canvas.drawRect(0, bottom - height, getWidth(), bottom, mTrackPaint);
        if (mProgress > 0) {
            canvas.drawRect(0, bottom - height, getWidth() * mProgress, bottom, mProgressPaint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHandler.post(mTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(mTick);
    }
}
