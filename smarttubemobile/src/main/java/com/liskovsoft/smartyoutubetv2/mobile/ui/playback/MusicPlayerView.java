package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicMenu;
import com.liskovsoft.smartyoutubetv2.mobile.ui.music.QueueView;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.List;

/**
 * The music player screen, like YouTube Music: big cover with the title on it, action chips, progress, controls and
 * "Spelas just nu" (the queue) as a page that slides up. It only draws; the playback is driven through {@link Host}.
 */
public class MusicPlayerView extends FrameLayout {
    public interface Host {
        Video video();

        boolean isPlaying();

        long position();

        long duration();

        void togglePlay();

        void previous();

        void next();

        void seekTo(long ms);

        void setSpeed(float speed);

        void minimize();

        void showVideo();

        void share();

        QueueView.Listener queueListener();

        List<Video> queued();

        List<Video> rest();
    }

    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.75f};

    private final Activity mActivity;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ImageView mArt;
    private TextView mTitle, mAuthor, mSpeedButton, mPosition, mDuration;
    private SeekBar mSeek;
    private ImageView mPlay;
    private ImageView mModeAudio, mModeVideo;
    private FrameLayout mQueueSheet;
    private boolean mSeeking;
    private int mSpeedIndex;
    private Video mShown;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mHandler.postDelayed(this, 500);
        }
    };
    private final Runnable mSleep = new Runnable() {
        @Override
        public void run() {
            if (mHost.isPlaying()) {
                mHost.togglePlay();
            }
        }
    };

    public MusicPlayerView(Activity activity, Host host) {
        super(activity);
        mActivity = activity;
        mHost = host;
        setBackgroundColor(activity.getColor(R.color.yt_bg));
        setClickable(true); // nothing behind it gets the touches

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(createPage(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View createPage() {
        Activity a = mActivity;
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);

        // cover with the top bar and the title on it
        FrameLayout cover = new FrameLayout(a);
        int size = Math.min(a.getResources().getDisplayMetrics().widthPixels, (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55f));
        mArt = new ImageView(a);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setBackgroundColor(a.getColor(R.color.yt_surface));
        cover.addView(mArt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(a);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, a.getColor(R.color.yt_bg)});
        scrim.setBackground(fade);
        FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 160), Gravity.BOTTOM);
        cover.addView(scrim, scrimParams);

        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), Ui.dp(a, 8));
        mTitle = new TextView(a);
        mTitle.setTextColor(a.getColor(R.color.yt_text));
        mTitle.setTextSize(26);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setShadowLayer(8, 0, 0, 0xCC000000);
        titles.addView(mTitle);
        mAuthor = new TextView(a);
        mAuthor.setTextColor(a.getColor(R.color.yt_text_secondary));
        mAuthor.setTextSize(16);
        mAuthor.setSingleLine(true);
        mAuthor.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(mAuthor);
        cover.addView(titles, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // top bar: collapse, audio/video switch, more
        FrameLayout top = new FrameLayout(a);
        ImageView collapse = icon(R.drawable.ic_chevron_down, 48, 12);
        collapse.setOnClickListener(v -> mHost.minimize());
        top.addView(collapse, new FrameLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48), Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout pill = new LinearLayout(a);
        pill.setBackgroundResource(R.drawable.bg_mode_pill);
        pill.setPadding(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3));
        mModeAudio = modeButton(R.drawable.ic_headphones);
        mModeAudio.setBackgroundResource(R.drawable.bg_mode_selected);
        mModeVideo = modeButton(R.drawable.ic_video_mode);
        mModeVideo.setOnClickListener(v -> mHost.showVideo());
        pill.addView(mModeAudio);
        pill.addView(mModeVideo);
        top.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView more = icon(R.drawable.ic_more, 48, 12);
        more.setOnClickListener(v -> MusicMenu.show(a, mHost.video(), null, null));
        top.addView(more, new FrameLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48), Gravity.END | Gravity.CENTER_VERTICAL));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 56), Gravity.TOP);
        cover.addView(top, topParams);

        page.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size));

        // chips
        HorizontalScrollView chipScroll = new HorizontalScrollView(a);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(a);
        chips.setPadding(Ui.dp(a, 16), Ui.dp(a, 8), Ui.dp(a, 16), Ui.dp(a, 8));
        chips.addView(chip(R.drawable.ic_save, "Spara", v -> MusicMenu.addToPlaylist(a, mHost.video())));
        chips.addView(chip(R.drawable.ic_comment, "Detaljer", v -> showDetails()));
        chips.addView(chip(R.drawable.ic_share, "Dela", v -> mHost.share()));
        chips.addView(chip(R.drawable.ic_next, "Kö", v -> showQueue(true)));
        chipScroll.addView(chips);
        page.addView(chipScroll);

        // progress
        mSeek = new SeekBar(a);
        mSeek.setMax(1000);
        mSeek.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        mSeek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        mSeek.setSecondaryProgressTintList(ColorStateList.valueOf(0x66FFFFFF));
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mPosition.setText(format(mHost.duration() * progress / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                long duration = mHost.duration();
                if (duration > 0) {
                    mHost.seekTo(duration * seekBar.getProgress() / 1000);
                }
                mSeeking = false;
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seekParams.setMargins(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), 0);
        page.addView(mSeek, seekParams);

        LinearLayout times = new LinearLayout(a);
        times.setPadding(Ui.dp(a, 24), 0, Ui.dp(a, 24), 0);
        mPosition = text(13, R.color.yt_text_secondary);
        mDuration = text(13, R.color.yt_text_secondary);
        times.addView(mPosition, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(mDuration);
        page.addView(times);

        // controls: speed, previous, play, next, sleep timer
        LinearLayout controls = new LinearLayout(a);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
        mSpeedButton = text(18, R.color.yt_text);
        mSpeedButton.setTypeface(Typeface.DEFAULT_BOLD);
        mSpeedButton.setText("1x");
        mSpeedButton.setGravity(Gravity.CENTER);
        mSpeedButton.setOnClickListener(v -> {
            mSpeedIndex = (mSpeedIndex + 1) % SPEEDS.length;
            mHost.setSpeed(SPEEDS[mSpeedIndex]);
            mSpeedButton.setText(SPEEDS[mSpeedIndex] == (int) SPEEDS[mSpeedIndex] ? (int) SPEEDS[mSpeedIndex] + "x" : SPEEDS[mSpeedIndex] + "x");
        });
        controls.addView(mSpeedButton, new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f));

        ImageView prev = icon(R.drawable.ic_prev, 56, 12);
        prev.setOnClickListener(v -> mHost.previous());
        controls.addView(prev, new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1f));

        mPlay = new ImageView(a);
        mPlay.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mPlay.setPadding(Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22));
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Color.WHITE);
        mPlay.setBackground(disc);
        mPlay.setImageTintList(ColorStateList.valueOf(Color.BLACK));
        mPlay.setImageResource(R.drawable.ic_pause);
        mPlay.setOnClickListener(v -> {
            mHost.togglePlay();
            update();
        });
        controls.addView(mPlay, new LinearLayout.LayoutParams(Ui.dp(a, 84), Ui.dp(a, 84)));

        ImageView next = icon(R.drawable.ic_next, 56, 12);
        next.setOnClickListener(v -> mHost.next());
        controls.addView(next, new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1f));

        ImageView moon = icon(R.drawable.ic_moon, 48, 12);
        moon.setOnClickListener(v -> chooseSleepTimer());
        controls.addView(moon, new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f));
        page.addView(controls);

        // handle to the queue
        LinearLayout handle = new LinearLayout(a);
        handle.setOrientation(LinearLayout.VERTICAL);
        handle.setGravity(Gravity.CENTER_HORIZONTAL);
        handle.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 20));
        View bar = new View(a);
        GradientDrawable barShape = new GradientDrawable();
        barShape.setCornerRadius(Ui.dp(a, 2));
        barShape.setColor(a.getColor(R.color.yt_text_secondary));
        bar.setBackground(barShape);
        handle.addView(bar, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 4)));
        TextView label = text(16, R.color.yt_text);
        label.setText("Spelas just nu");
        label.setPadding(0, Ui.dp(a, 16), 0, 0);
        handle.addView(label);
        handle.setOnClickListener(v -> showQueue(true));
        page.addView(handle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return page;
    }

    private ImageView icon(int res, int sizeDp, int padDp) {
        ImageView view = new ImageView(mActivity);
        view.setImageResource(res);
        view.setPadding(Ui.dp(mActivity, padDp), Ui.dp(mActivity, padDp), Ui.dp(mActivity, padDp), Ui.dp(mActivity, padDp));
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(mActivity, sizeDp), Ui.dp(mActivity, sizeDp)));
        return view;
    }

    private ImageView modeButton(int res) {
        ImageView button = new ImageView(mActivity);
        button.setImageResource(res);
        button.setPadding(Ui.dp(mActivity, 12), Ui.dp(mActivity, 7), Ui.dp(mActivity, 12), Ui.dp(mActivity, 7));
        button.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(mActivity, 56), Ui.dp(mActivity, 38)));
        return button;
    }

    private TextView text(int sp, int color) {
        TextView view = new TextView(mActivity);
        view.setTextColor(mActivity.getColor(color));
        view.setTextSize(sp);
        return view;
    }

    private View chip(int iconRes, String label, OnClickListener listener) {
        LinearLayout chip = new LinearLayout(mActivity);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setPadding(Ui.dp(mActivity, 14), Ui.dp(mActivity, 10), Ui.dp(mActivity, 16), Ui.dp(mActivity, 10));
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        chip.addView(icon, new LinearLayout.LayoutParams(Ui.dp(mActivity, 22), Ui.dp(mActivity, 22)));
        TextView text = text(14, R.color.yt_text);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setText(label);
        text.setPadding(Ui.dp(mActivity, 8), 0, 0, 0);
        chip.addView(text);
        chip.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(mActivity, 10));
        chip.setLayoutParams(params);
        return chip;
    }

    private void showDetails() {
        Video video = mHost.video();
        if (video == null) {
            return;
        }
        String text = video.description != null && !video.description.isEmpty() ? video.description : "Ingen beskrivning";
        new AlertDialog.Builder(mActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(video.getTitle())
                .setMessage(text)
                .setPositiveButton("Stäng", null)
                .show();
    }

    private void chooseSleepTimer() {
        final int[] minutes = {0, 15, 30, 45, 60};
        new AlertDialog.Builder(mActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sovtimer")
                .setItems(new String[]{"Av", "15 minuter", "30 minuter", "45 minuter", "60 minuter"}, (d, which) -> {
                    mHandler.removeCallbacks(mSleep);
                    if (minutes[which] > 0) {
                        mHandler.postDelayed(mSleep, minutes[which] * 60_000L);
                    }
                })
                .show();
    }

    ///////////////////// queue page

    public void showQueue(boolean show) {
        if (!show) {
            if (mQueueSheet != null) {
                removeView(mQueueSheet);
                mQueueSheet = null;
            }
            return;
        }

        if (mQueueSheet != null) {
            refreshQueue();
            return;
        }

        mQueueSheet = new FrameLayout(mActivity);
        mQueueSheet.setBackgroundColor(mActivity.getColor(R.color.yt_bg));
        mQueueSheet.setClickable(true);
        addView(mQueueSheet, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshQueue();
    }

    public boolean isQueueShown() {
        return mQueueSheet != null;
    }

    /** The queue changed (song added, moved or the next song started). */
    public void refreshQueue() {
        if (mQueueSheet == null) {
            return;
        }

        mQueueSheet.removeAllViews();
        LinearLayout column = new LinearLayout(mActivity);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(mActivity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView close = icon(R.drawable.ic_chevron_down, 48, 12);
        close.setOnClickListener(v -> showQueue(false));
        bar.addView(close);
        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(mActivity, 52)));

        View queue = QueueView.create(mActivity, mHost.video(), mHost.queued(), mHost.rest(), mHost.queueListener());
        column.addView(queue, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mQueueSheet.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    ///////////////////// state

    /** A new song: cover, title, artist. */
    public void bind(Video video) {
        if (video == null) {
            return;
        }

        if (mShown == null || !video.equals(mShown) || mShown.getCardImageUrl() == null) {
            String image = video.getCardImageUrl();
            if (image != null) {
                Glide.with(mArt).load(image).into(mArt);
            }
        }
        mShown = video;
        mTitle.setText(video.getTitle());
        mAuthor.setText(video.getAuthor() != null ? video.getAuthor() : "");
        if (mQueueSheet != null) {
            refreshQueue();
        }
    }

    private void update() {
        long duration = mHost.duration();
        long position = mHost.position();
        if (!mSeeking) {
            mPosition.setText(format(position));
            if (duration > 0 && duration < 24L * 60 * 60 * 1000) {
                mSeek.setProgress((int) (position * 1000 / duration));
            }
        }
        mDuration.setText(duration > 0 && duration < 24L * 60 * 60 * 1000 ? format(duration) : "");
        mPlay.setImageResource(mHost.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
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

    private static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
