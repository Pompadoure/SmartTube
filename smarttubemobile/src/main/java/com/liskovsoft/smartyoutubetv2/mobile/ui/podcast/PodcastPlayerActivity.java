package com.liskovsoft.smartyoutubetv2.mobile.ui.podcast;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.RatioFrameLayout;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.List;
import java.util.Locale;

/**
 * The podcast player screen: cover, title, seek bar, previous/next episode, -10 s / +30 s and the speed.
 */
public class PodcastPlayerActivity extends Activity {
    private static final float[] SPEEDS = {0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f};
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ExoPlayer mPlayer;
    private ImageView mCover, mPlayPause;
    private TextView mTitle, mShow, mTime, mSpeed;
    private SeekBar mSeek;
    private boolean mSeeking;
    private PodcastRepository.Episode mShown;
    private boolean mDestroyed;
    private FrameLayout mFrame;
    private FrameLayout mQueueSheet;
    private TextView mQueueButton;
    private final Runnable mQueueListener = this::onQueueChanged;
    private boolean mSkipQueueRefresh;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!PodcastPlayer.isActive()) {
            finish();
            return;
        }
        mPlayer = PodcastPlayer.player(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.yt_bg));
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(0x00000000);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        ImageView down = icon(R.drawable.ic_chevron_down, 48, 10);
        down.setOnClickListener(v -> finish());
        top.addView(down);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        // JoTube: the same light cross as the mini player
        ImageView stop = icon(R.drawable.ic_close_thin, 48, 13);
        stop.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.yt_text_secondary)));
        stop.setBackgroundResource(R.drawable.bg_icon_round_ripple);
        stop.setOnClickListener(v -> {
            PodcastPlayer.stop();
            finish();
        });
        top.addView(stop);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        RatioFrameLayout coverBox = new RatioFrameLayout(this);
        coverBox.setHeightPerWidth(1f);
        mCover = new ImageView(this);
        mCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mCover.setBackgroundColor(getColor(R.color.yt_surface));
        Ui.makeRounded(mCover, Ui.dp(this, 14));
        coverBox.addView(mCover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        coverParams.setMargins(Ui.dp(this, 32), Ui.dp(this, 8), Ui.dp(this, 32), 0);
        root.addView(coverBox, coverParams);

        mTitle = new TextView(this);
        mTitle.setTextColor(getColor(R.color.yt_text));
        mTitle.setTextSize(20);
        mTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setPadding(Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 24), 0);
        root.addView(mTitle);
        mShow = new TextView(this);
        mShow.setTextColor(getColor(R.color.yt_text_secondary));
        mShow.setTextSize(14);
        mShow.setMaxLines(1);
        mShow.setPadding(Ui.dp(this, 24), Ui.dp(this, 4), Ui.dp(this, 24), 0);
        root.addView(mShow);

        mSeek = new SeekBar(this);
        mSeek.setMax(1000);
        mSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        mSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mPlayer.getDuration() != C.TIME_UNSET) {
                    mTime.setText(format(mPlayer.getDuration() * progress / 1000) + " / " + format(mPlayer.getDuration()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mPlayer.getDuration() != C.TIME_UNSET) {
                    mPlayer.seekTo(mPlayer.getDuration() * seekBar.getProgress() / 1000);
                }
                mSeeking = false;
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seekParams.setMargins(Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 12), 0);
        root.addView(mSeek, seekParams);
        mTime = new TextView(this);
        mTime.setTextColor(getColor(R.color.yt_text_secondary));
        mTime.setTextSize(12);
        mTime.setPadding(Ui.dp(this, 24), 0, Ui.dp(this, 24), 0);
        root.addView(mTime);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.addView(textButton("−10 s", v -> mPlayer.seekTo(Math.max(0, mPlayer.getCurrentPosition() - 10_000))));
        ImageView prev = icon(R.drawable.ic_prev, 56, 12);
        prev.setOnClickListener(v -> mPlayer.seekToPreviousMediaItem());
        controls.addView(prev);
        mPlayPause = icon(R.drawable.ic_pause, 72, 12);
        mPlayPause.setOnClickListener(v -> mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady()));
        controls.addView(mPlayPause);
        ImageView next = icon(R.drawable.ic_next, 56, 12);
        next.setOnClickListener(v -> mPlayer.seekToNextMediaItem());
        controls.addView(next);
        controls.addView(textButton("+30 s", v -> mPlayer.seekTo(mPlayer.getCurrentPosition() + 30_000)));
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlParams.topMargin = Ui.dp(this, 16);
        root.addView(controls, controlParams);

        // JoTube: the speed and the queue side by side
        LinearLayout extras = new LinearLayout(this);
        extras.setGravity(Gravity.CENTER);
        mSpeed = textButton(speedLabel(), v -> cycleSpeed());
        extras.addView(mSpeed);
        mQueueButton = textButton(queueLabel(), v -> showQueue(true));
        extras.addView(mQueueButton);
        LinearLayout.LayoutParams extrasParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        extrasParams.topMargin = Ui.dp(this, 8);
        root.addView(extras, extrasParams);

        // the queue sheet goes over the player (same frame)
        mFrame = new FrameLayout(this);
        mFrame.setBackgroundColor(getColor(R.color.yt_bg));
        mFrame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mFrame.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(mFrame);
        PodcastPlayer.setQueueListener(mQueueListener);
        update();
    }

    private ImageView icon(int res, int sizeDp, int padDp) {
        ImageView view = new ImageView(this);
        view.setImageResource(res);
        view.setPadding(Ui.dp(this, padDp), Ui.dp(this, padDp), Ui.dp(this, padDp), Ui.dp(this, padDp));
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        return view;
    }

    private TextView textButton(String label, View.OnClickListener click) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextColor(getColor(R.color.yt_text));
        button.setTextSize(14);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundResource(R.drawable.bg_chip);
        button.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        button.setLayoutParams(params);
        button.setOnClickListener(click);
        return button;
    }

    private String speedLabel() {
        float speed = PodcastPlayer.getSpeed();
        return (speed == Math.floor(speed) ? String.format(Locale.US, "%.0f", speed) : String.format(Locale.US, "%.1f", speed)) + "x";
    }

    private void cycleSpeed() {
        float current = PodcastPlayer.getSpeed();
        float next = SPEEDS[0];
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - current) < 0.01f && i + 1 < SPEEDS.length) {
                next = SPEEDS[i + 1];
                break;
            }
        }
        PodcastPlayer.setSpeed(next);
        mSpeed.setText(speedLabel());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        PodcastPlayer.removeQueueListener(mQueueListener);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mQueueSheet != null) {
            showQueue(false);
            return;
        }
        super.onBackPressed();
    }

    ///////////////////// queue

    private String queueLabel() {
        int count = PodcastPlayer.queued().size();
        return count > 0 ? "Kö (" + count + ")" : "Kö";
    }

    private void onQueueChanged() {
        if (mDestroyed) {
            return;
        }
        // posted: it can come from a drag that is ending (the list is rebuilt)
        mFrame.post(() -> {
            if (mDestroyed) {
                return;
            }
            mQueueButton.setText(queueLabel());
            // after a drag the list already shows the new order (no rebuild: no jump, no flicker)
            if (mQueueSheet != null && !mSkipQueueRefresh) {
                refreshQueue();
            }
            mSkipQueueRefresh = false;
        });
    }

    private void showQueue(boolean show) {
        if (!show) {
            if (mQueueSheet != null) {
                mFrame.removeView(mQueueSheet);
                mQueueSheet = null;
            }
            return;
        }

        if (mQueueSheet == null) {
            mQueueSheet = new FrameLayout(this);
            mQueueSheet.setBackgroundColor(getColor(R.color.yt_bg));
            mQueueSheet.setClickable(true);
            mFrame.addView(mQueueSheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        refreshQueue();
    }

    private void refreshQueue() {
        mQueueSheet.removeAllViews();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView close = icon(R.drawable.ic_chevron_down, 48, 12);
        close.setOnClickListener(v -> showQueue(false));
        bar.addView(close);
        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        List<PodcastRepository.Episode> queued = PodcastPlayer.queued();
        List<PodcastRepository.Episode> rest = PodcastPlayer.rest();
        View queue = PodcastQueueView.create(this, PodcastPlayer.current(), queued, rest, new PodcastQueueView.Listener() {
            @Override
            public void onPlay(PodcastRepository.Episode episode) {
                if (episode != PodcastPlayer.current()) {
                    PodcastPlayer.jumpTo(episode);
                }
                showQueue(false);
            }

            @Override
            public void onLongPress(PodcastRepository.Episode episode) {
                if (episode == PodcastPlayer.current()) {
                    return;
                }
                new android.app.AlertDialog.Builder(PodcastPlayerActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(episode.title)
                        .setItems(new String[]{"Spela nu", "Ta bort ur kön"}, (d, which) -> {
                            if (which == 0) {
                                PodcastPlayer.jumpTo(episode);
                                showQueue(false);
                            } else {
                                PodcastPlayer.remove(episode);
                            }
                        })
                        .show();
            }

            @Override
            public void onReordered(List<PodcastRepository.Episode> order) {
                mSkipQueueRefresh = true;
                PodcastPlayer.reorder(order);
            }
        });
        column.addView(queue, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mQueueSheet.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void update() {
        if (mDestroyed || mPlayer == null) {
            return;
        }
        if (!PodcastPlayer.isActive()) {
            finish(); // stopped from the notification
            return;
        }

        PodcastRepository.Episode episode = PodcastPlayer.current();
        if (episode != null && episode != mShown) {
            mShown = episode;
            mTitle.setText(episode.title);
            mShow.setText(episode.feedTitle);
            if (episode.image != null) {
                Glide.with(mCover).load(episode.image).into(mCover);
            } else {
                Glide.with(mCover).clear(mCover);
                mCover.setImageDrawable(null);
            }
        }

        long duration = mPlayer.getDuration();
        if (!mSeeking) {
            mSeek.setProgress(duration > 0 && duration != C.TIME_UNSET ? (int) (mPlayer.getCurrentPosition() * 1000 / duration) : 0);
            mSeek.setSecondaryProgress(duration > 0 && duration != C.TIME_UNSET ? (int) (mPlayer.getBufferedPosition() * 1000 / duration) : 0);
            mTime.setText(format(mPlayer.getCurrentPosition()) + (duration != C.TIME_UNSET ? " / " + format(duration) : ""));
        }
        mPlayPause.setImageResource(mPlayer.getPlayWhenReady() && mPlayer.getPlaybackState() != Player.STATE_ENDED ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private static String format(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds % 60) : String.format(Locale.US, "%d:%02d", minutes, seconds % 60);
    }
}
