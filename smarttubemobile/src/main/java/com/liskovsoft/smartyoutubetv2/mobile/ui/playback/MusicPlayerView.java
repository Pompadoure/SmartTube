package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicApi;
import com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicMenu;
import com.liskovsoft.smartyoutubetv2.mobile.ui.music.QueueView;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The player screen of music and podcasts, like YouTube Music: the cover with the top bar on it, the title and the
 * artist under it, a row of pill buttons, the progress, the controls and the queue at the bottom. The page takes the
 * color of the cover. It only draws; the playback is driven through {@link Host} (the music player and the podcast
 * player each have one, so both look and work the same).
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

        /** Podcast: no video mode, no likes or lyrics; -10 s / +30 s instead of shuffle and repeat. */
        default boolean isPodcast() {
            return false;
        }

        default long buffered() {
            return 0;
        }

        /** The steps of the speed button. */
        default float[] speeds() {
            return new float[]{1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f};
        }

        /** The speed the player has now (the music player starts each song at 1x). */
        default float speed() {
            return 1f;
        }

        default boolean hasLikes() {
            return false;
        }

        /** 1 = liked, -1 = disliked, 0 = neither. */
        default int likeState() {
            return 0;
        }

        default String likeCount() {
            return null;
        }

        default void like(boolean up) {
        }

        default boolean shuffle() {
            return false;
        }

        default void setShuffle(boolean on) {
        }

        default boolean repeatOne() {
            return false;
        }

        default void setRepeatOne(boolean on) {
        }

        /** The name of what plays ("GRUSVÄG – mix", an album); null = "Nästa: ...". */
        default String queueTitle() {
            return null;
        }

        /** The queue page; null = the music queue ({@link QueueView}). */
        default View createQueue(Activity activity, Runnable close) {
            return null;
        }

        /** The ⋮ menu; false = the song menu. */
        default boolean more() {
            return false;
        }

        /** A light cross at the top (stops the podcast). */
        default boolean closable() {
            return false;
        }

        default void close() {
        }

        default void startRadio() {
        }
    }

    private static final int CHIP_COLOR = 0x26FFFFFF;
    private static final int INACTIVE = 0x99FFFFFF;

    private final Activity mActivity;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(); // lyrics (shared: the screens come and go)
    private final LinearLayout mPage;
    private ImageView mArt;
    private View mScrim;
    private TextView mTitle, mAuthor, mPosition, mDuration, mQueueLabel, mSpeedChip;
    private SeekBar mSeek;
    private ImageView mPlay, mShuffle, mRepeat;
    private ImageView mLikeIcon, mDislikeIcon;
    private TextView mLikeCount;
    private LinearLayout mQueueBar;
    // the three-step queue drawer (like YouTube Music): 0 player, 1 song + queue, 2 the queue (mini player on top)
    private FrameLayout mCover;
    private LinearLayout mTitleRow;
    private HorizontalScrollView mChipScroll;
    private LinearLayout mControlsRow;
    private LinearLayout mDrawer;
    private TextView mDrawerTitle;
    private ImageView mDrawerAdd;
    private FrameLayout mDrawerBody;
    private View mQueueContent;
    private LinearLayout mMini;
    private ImageView mMiniArt, mMiniPlay;
    private TextView mMiniTitle, mMiniAuthor;
    private int mState;
    private float mDrawerY = Float.NaN;
    private float mCoverFull, mCoverS1, mTitleH, mChipH, mY1;
    private float mProgress1;
    private android.animation.ValueAnimator mAnim;
    private float mDownX, mDownY, mDragStartY, mDragStartTouch;
    private boolean mDragging, mDownOnSeek, mDownInHeader;
    private int mDragStartState;
    private android.view.VelocityTracker mVelocity;
    private FrameLayout mSheet;
    private boolean mSeeking;
    private Video mShown;
    private String mShownImage;
    private int mTint;
    private String mLyricsFor;
    private MusicApi.Lyrics mLyrics;
    private LinearLayout mLyricsLines;
    private ScrollView mLyricsScroll;
    private int mLyricsCurrent = -1;
    private boolean mReleased;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mHandler.postDelayed(this, mLyricsLines != null ? 200 : 500);
        }
    };
    // The sleep timer outlives this screen (minimized player, closed podcast screen)
    private static final Handler sSleepHandler = new Handler(Looper.getMainLooper());
    private static Runnable sSleep;

    public MusicPlayerView(Activity activity, Host host) {
        super(activity);
        mActivity = activity;
        mHost = host;
        mTint = activity.getColor(R.color.yt_bg);
        setClickable(true); // nothing behind it gets the touches

        mPage = new LinearLayout(activity);
        mPage.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(createPage(), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mPage.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(mPage, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(createMiniBar(), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 72), Gravity.TOP));
        addView(createDrawer(), new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyTint(mTint);
    }

    private View createPage() {
        Activity a = mActivity;
        LinearLayout page = new LinearLayout(a);
        page.setOrientation(LinearLayout.VERTICAL);

        // the cover with the top bar on it
        FrameLayout cover = new FrameLayout(a);
        mCover = cover;
        int width = a.getResources().getDisplayMetrics().widthPixels;
        int size = Math.min(width, (int) (a.getResources().getDisplayMetrics().heightPixels * 0.5f));
        mArt = new ImageView(a);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(mArt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mScrim = new View(a);
        cover.addView(mScrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 190), Gravity.BOTTOM)); // the cover melts into the page color
        View topShade = new View(a);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x00000000}));
        cover.addView(topShade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 88), Gravity.TOP));

        FrameLayout top = new FrameLayout(a);
        ImageView collapse = icon(R.drawable.ic_chevron_down, 48, 12);
        collapse.setOnClickListener(v -> mHost.minimize());
        top.addView(collapse, new FrameLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48), Gravity.START | Gravity.CENTER_VERTICAL));

        if (!mHost.isPodcast()) {
            LinearLayout pill = new LinearLayout(a);
            pill.setBackgroundResource(R.drawable.bg_mode_pill);
            pill.setPadding(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3));
            ImageView audio = modeButton(R.drawable.ic_headphones);
            audio.setBackgroundResource(R.drawable.bg_mode_selected);
            ImageView video = modeButton(R.drawable.ic_video_mode);
            video.setOnClickListener(v -> mHost.showVideo());
            pill.addView(audio);
            pill.addView(video);
            top.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }

        LinearLayout right = new LinearLayout(a);
        if (mHost.closable()) {
            ImageView close = icon(R.drawable.ic_close_thin, 48, 13);
            close.setImageTintList(ColorStateList.valueOf(0xDDFFFFFF));
            close.setOnClickListener(v -> mHost.close());
            right.addView(close);
        }
        ImageView more = icon(R.drawable.ic_more_vert, 48, 12);
        more.setOnClickListener(v -> {
            if (!mHost.more()) {
                MusicMenu.show(a, mHost.video(), null, null);
            }
        });
        right.addView(more);
        top.addView(right, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        top.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0);
        cover.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 56), Gravity.TOP));

        mCoverFull = size;
        mCoverS1 = size * 0.86f;
        page.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, size));

        // title and artist under the cover, a chevron for the details
        LinearLayout titleRow = new LinearLayout(a);
        mTitleRow = titleRow;
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(Ui.dp(a, 24), Ui.dp(a, 18), Ui.dp(a, 12), 0);
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        mTitle = text(24, Color.WHITE);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        mTitle.setMarqueeRepeatLimit(-1);
        mTitle.setSelected(true);
        mTitle.setHorizontalFadingEdgeEnabled(true);
        titles.addView(mTitle);
        mAuthor = text(16, 0xB3FFFFFF);
        mAuthor.setSingleLine(true);
        mAuthor.setEllipsize(TextUtils.TruncateAt.END);
        mAuthor.setPadding(0, Ui.dp(a, 2), 0, 0);
        titles.addView(mAuthor);
        titleRow.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView details = icon(R.drawable.ic_chevron_right, 40, 8);
        details.setOnClickListener(v -> showDetails());
        titleRow.addView(details);
        titleRow.setOnClickListener(v -> showDetails());
        page.addView(titleRow);

        // pill buttons
        HorizontalScrollView chipScroll = new HorizontalScrollView(a);
        mChipScroll = chipScroll;
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipToPadding(false);
        chipScroll.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, 4));
        LinearLayout chips = new LinearLayout(a);
        if (mHost.hasLikes()) {
            chips.addView(createLikePill());
        }
        if (!mHost.isPodcast()) {
            chips.addView(chip(R.drawable.ic_lyrics, "Sångtexter", v -> showLyrics()));
            chips.addView(chip(R.drawable.ic_save, "Spara", v -> MusicMenu.addToPlaylist(a, mHost.video())));
        }
        chips.addView(chip(R.drawable.ic_share, "Dela", v -> mHost.share()));
        if (!mHost.isPodcast()) {
            chips.addView(chip(R.drawable.ic_radio, "Radio", v -> mHost.startRadio()));
        }
        View speed = chip(R.drawable.ic_speed, speedLabel(mHost.speed()), v -> cycleSpeed());
        mSpeedChip = (TextView) ((LinearLayout) speed).getChildAt(1);
        chips.addView(speed);
        chips.addView(chip(R.drawable.ic_moon, "Sovtimer", v -> chooseSleepTimer()));
        chipScroll.addView(chips);
        page.addView(chipScroll);

        // progress
        mSeek = new SeekBar(a);
        mSeek.setMax(1000);
        mSeek.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        mSeek.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        mSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0x4DFFFFFF));
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
        seekParams.setMargins(Ui.dp(a, 8), Ui.dp(a, 18), Ui.dp(a, 8), 0);
        page.addView(mSeek, seekParams);

        LinearLayout times = new LinearLayout(a);
        times.setPadding(Ui.dp(a, 24), Ui.dp(a, 2), Ui.dp(a, 24), 0);
        mPosition = text(13, 0xB3FFFFFF);
        mDuration = text(13, 0xB3FFFFFF);
        times.addView(mPosition, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(mDuration);
        page.addView(times);

        // controls
        LinearLayout controls = new LinearLayout(a);
        mControlsRow = controls;
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 20));
        if (mHost.isPodcast()) {
            controls.addView(skipButton(false), new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1f));
        } else {
            mShuffle = icon(R.drawable.ic_shuffle, 48, 12);
            mShuffle.setOnClickListener(v -> {
                mHost.setShuffle(!mHost.shuffle());
                refreshButtons();
            });
            controls.addView(mShuffle, new LinearLayout.LayoutParams(0, Ui.dp(a, 52), 1f));
        }

        ImageView prev = icon(R.drawable.ic_prev, 56, 12);
        prev.setOnClickListener(v -> mHost.previous());
        controls.addView(prev, new LinearLayout.LayoutParams(0, Ui.dp(a, 60), 1.2f));

        mPlay = new ImageView(a);
        mPlay.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mPlay.setPadding(Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22), Ui.dp(a, 22));
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Color.WHITE);
        mPlay.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33000000), disc, null));
        mPlay.setImageTintList(ColorStateList.valueOf(Color.BLACK));
        mPlay.setImageResource(R.drawable.ic_pause);
        mPlay.setOnClickListener(v -> {
            mHost.togglePlay();
            update();
        });
        controls.addView(mPlay, new LinearLayout.LayoutParams(Ui.dp(a, 80), Ui.dp(a, 80)));

        ImageView next = icon(R.drawable.ic_next, 56, 12);
        next.setOnClickListener(v -> mHost.next());
        controls.addView(next, new LinearLayout.LayoutParams(0, Ui.dp(a, 60), 1.2f));

        if (mHost.isPodcast()) {
            controls.addView(skipButton(true), new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1f));
        } else {
            mRepeat = icon(R.drawable.ic_repeat, 48, 12);
            mRepeat.setOnClickListener(v -> {
                mHost.setRepeatOne(!mHost.repeatOne());
                refreshButtons();
            });
            controls.addView(mRepeat, new LinearLayout.LayoutParams(0, Ui.dp(a, 52), 1f));
        }
        page.addView(controls);
        refreshButtons();
        return page;
    }

    /** -10 s / +30 s: the round arrow with the seconds in it. */
    private View skipButton(boolean forward) {
        FrameLayout box = new FrameLayout(mActivity);
        box.setBackground(Ui.roundRipple());
        ImageView arrow = new ImageView(mActivity);
        arrow.setImageResource(R.drawable.ic_replay);
        arrow.setScaleX(forward ? -1f : 1f);
        box.addView(arrow, new FrameLayout.LayoutParams(Ui.dp(mActivity, 36), Ui.dp(mActivity, 36), Gravity.CENTER));
        TextView seconds = text(10, Color.WHITE);
        seconds.setTypeface(Typeface.DEFAULT_BOLD);
        seconds.setText(forward ? "30" : "10");
        seconds.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        params.topMargin = Ui.dp(mActivity, 3);
        box.addView(seconds, params);
        box.setOnClickListener(v -> {
            long target = mHost.position() + (forward ? 30_000 : -10_000);
            long duration = mHost.duration();
            mHost.seekTo(Math.max(0, duration > 0 ? Math.min(target, duration - 1000) : target));
            update();
        });
        return box;
    }

    /** The like pill: thumb up with the count | thumb down. */
    private View createLikePill() {
        Activity a = mActivity;
        LinearLayout pill = new LinearLayout(a);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.pill(a, CHIP_COLOR));

        LinearLayout up = new LinearLayout(a);
        up.setGravity(Gravity.CENTER_VERTICAL);
        up.setPadding(Ui.dp(a, 16), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10));
        mLikeIcon = new ImageView(a);
        mLikeIcon.setImageResource(R.drawable.ic_like);
        up.addView(mLikeIcon, new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)));
        mLikeCount = text(14, Color.WHITE);
        mLikeCount.setTypeface(Typeface.DEFAULT_BOLD);
        mLikeCount.setPadding(Ui.dp(a, 8), 0, 0, 0);
        up.addView(mLikeCount);
        up.setOnClickListener(v -> {
            mHost.like(true);
            mHandler.postDelayed(this::refreshButtons, 300);
        });
        pill.addView(up);

        View divider = new View(a);
        divider.setBackgroundColor(0x40FFFFFF);
        pill.addView(divider, new LinearLayout.LayoutParams(Ui.dp(a, 1), Ui.dp(a, 24)));

        mDislikeIcon = new ImageView(a);
        mDislikeIcon.setImageResource(R.drawable.ic_dislike);
        mDislikeIcon.setPadding(Ui.dp(a, 12), Ui.dp(a, 10), Ui.dp(a, 16), Ui.dp(a, 10));
        mDislikeIcon.setOnClickListener(v -> {
            mHost.like(false);
            mHandler.postDelayed(this::refreshButtons, 300);
        });
        pill.addView(mDislikeIcon, new LinearLayout.LayoutParams(Ui.dp(a, 50), Ui.dp(a, 42)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(a, 10));
        pill.setLayoutParams(params);
        return pill;
    }

    /** Likes, shuffle and repeat as they are now. */
    public void refreshButtons() {
        if (mLikeIcon != null) {
            int state = mHost.likeState();
            mLikeIcon.setImageResource(state > 0 ? R.drawable.ic_like_fill : R.drawable.ic_like);
            mDislikeIcon.setImageResource(state < 0 ? R.drawable.ic_dislike_fill : R.drawable.ic_dislike);
            mLikeIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            mDislikeIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            String count = mHost.likeCount();
            mLikeCount.setText(count != null ? count : "");
            mLikeCount.setVisibility(count != null && !count.isEmpty() ? VISIBLE : GONE);
        }
        if (mShuffle != null) {
            mShuffle.setImageTintList(ColorStateList.valueOf(mHost.shuffle() ? Color.WHITE : INACTIVE));
            mShuffle.setBackground(mHost.shuffle() ? Ui.pill(mActivity, 0x26FFFFFF) : Ui.roundRipple());
        }
        if (mRepeat != null) {
            boolean one = mHost.repeatOne();
            mRepeat.setImageResource(one ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
            mRepeat.setImageTintList(ColorStateList.valueOf(one ? Color.WHITE : INACTIVE));
            mRepeat.setBackground(one ? Ui.pill(mActivity, 0x26FFFFFF) : Ui.roundRipple());
        }
    }

    private static final int HEADER_DP = 84;
    private static final int MINI_DP = 72;

    /** The mini player that fades in on top when the queue is pulled all the way up. */
    private View createMiniBar() {
        Activity a = mActivity;
        mMini = new LinearLayout(a);
        mMini.setGravity(Gravity.CENTER_VERTICAL);
        mMini.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 8), 0);
        mMini.setAlpha(0f);
        mMini.setVisibility(GONE);
        mMini.setClickable(true);
        mMini.setOnClickListener(v -> animateTo(1));
        mMiniArt = new ImageView(a);
        mMiniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.makeRounded(mMiniArt, Ui.dp(a, 4));
        mMini.addView(mMiniArt, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 8), 0);
        mMiniTitle = text(17, Color.WHITE);
        mMiniTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mMiniTitle.setSingleLine(true);
        mMiniTitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(mMiniTitle);
        mMiniAuthor = text(15, 0xB3FFFFFF);
        mMiniAuthor.setSingleLine(true);
        mMiniAuthor.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(mMiniAuthor);
        mMini.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mMiniPlay = icon(R.drawable.ic_pause, 56, 14);
        mMiniPlay.setOnClickListener(v -> {
            mHost.togglePlay();
            update();
        });
        mMini.addView(mMiniPlay);
        return mMini;
    }

    /** The queue drawer: always there at the bottom (handle + what plays), pulled up in two steps. */
    private View createDrawer() {
        Activity a = mActivity;
        mDrawer = new LinearLayout(a);
        mDrawer.setOrientation(LinearLayout.VERTICAL);
        mDrawer.setClickable(true);

        FrameLayout header = new FrameLayout(a);
        mQueueBar = null;
        View handle = new View(a);
        handle.setBackground(Ui.pill(a, 0x66FFFFFF));
        header.addView(handle, new FrameLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 4), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) handle.getLayoutParams()).topMargin = Ui.dp(a, 8);

        mQueueLabel = text(15, Color.WHITE);
        mQueueLabel.setTypeface(Typeface.DEFAULT_BOLD);
        mQueueLabel.setSingleLine(true);
        mQueueLabel.setEllipsize(TextUtils.TruncateAt.END);
        mQueueLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        labelParams.topMargin = Ui.dp(a, 30);
        labelParams.leftMargin = Ui.dp(a, 24);
        labelParams.rightMargin = Ui.dp(a, 24);
        header.addView(mQueueLabel, labelParams);

        LinearLayout titleRow = new LinearLayout(a);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 8), 0);
        mDrawerTitle = text(22, Color.WHITE);
        mDrawerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mDrawerTitle.setSingleLine(true);
        mDrawerTitle.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(mDrawerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mDrawerAdd = icon(R.drawable.ic_queue_add, 48, 11);
        mDrawerAdd.setOnClickListener(v -> MusicMenu.addToPlaylist(a, mHost.video()));
        titleRow.addView(mDrawerAdd);
        titleRow.setAlpha(0f);
        titleRow.setTag("drawerTitleRow");
        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 56), Gravity.TOP);
        rowParams.topMargin = Ui.dp(a, 22);
        header.addView(titleRow, rowParams);

        header.setOnClickListener(v -> animateTo(mState == 0 ? 1 : mState == 1 ? 2 : 1));
        mDrawer.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, HEADER_DP)));

        mDrawerBody = new FrameLayout(a);
        mDrawer.addView(mDrawerBody, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return mDrawer;
    }

    private View drawerTitleRow() {
        return ((ViewGroup) mDrawer.getChildAt(0)).getChildAt(2);
    }

    private void paintDrawer() {
        if (mDrawer == null) {
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        int base = blend(mTint, 0xFF0F0F0F, 0.30f);
        bg.setColor((base & 0x00FFFFFF) | 0xF2000000); // slightly see-through
        float r = Ui.dp(mActivity, 16);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        mDrawer.setBackground(bg);
        if (mMini != null) {
            mMini.setBackgroundColor(blend(mTint, 0xFF000000, 0.35f));
        }
    }

    ///////////////////// the drawer positions and gestures

    private float y0() {
        return getHeight() - Ui.dp(mActivity, HEADER_DP);
    }

    private float y1() {
        return mY1 > 0 ? mY1 : getHeight() * 0.58f;
    }

    private float y2() {
        return Ui.dp(mActivity, MINI_DP);
    }

    private float stateY(int state) {
        return state == 0 ? y0() : state == 1 ? y1() : y2();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h > 0) {
            ViewGroup.LayoutParams lp = mDrawer.getLayoutParams();
            lp.height = h - Ui.dp(mActivity, MINI_DP); // so the end of the list is reachable when it is pulled all the way up
            mDrawer.setLayoutParams(lp);
            setDrawerY(stateY(mState));
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void setDrawerY(float y) {
        if (mDrawer == null || getHeight() == 0) {
            return;
        }
        float top = y2();
        float bottom = y0();
        y = Math.max(top, Math.min(bottom, y));
        mDrawerY = y;
        mDrawer.setTranslationY(y);

        float p1 = clamp01((bottom - y) / Math.max(1f, bottom - y1()));
        float p2 = y < y1() ? clamp01((y1() - y) / Math.max(1f, y1() - top)) : 0f;
        applyCompress(p1);

        mQueueLabel.setAlpha(1f - clamp01(p1 * 2f));
        drawerTitleRow().setAlpha(clamp01(p1 * 2f - 0.6f));
        mPage.setAlpha(1f - p2);
        mPage.setVisibility(p2 >= 1f ? INVISIBLE : VISIBLE);
        mMini.setAlpha(p2);
        mMini.setVisibility(p2 > 0f ? VISIBLE : GONE);
    }

    /** Step 0 to 1: the cover gets lower with the title on it, the pill row goes away. */
    private void applyCompress(float p) {
        if (mCover == null || Math.abs(p - mProgress1) < 0.002f && p != 0f && p != 1f) {
            return;
        }
        mProgress1 = p;
        if (mTitleH <= 0) {
            mTitleH = Ui.dp(mActivity, 76);
        }
        if (mChipH <= 0) {
            mChipH = Ui.dp(mActivity, 76);
        }
        LinearLayout.LayoutParams cover = (LinearLayout.LayoutParams) mCover.getLayoutParams();
        cover.height = Math.round(mCoverFull + (mCoverS1 - mCoverFull) * p);
        mCover.setLayoutParams(cover);
        LinearLayout.LayoutParams title = (LinearLayout.LayoutParams) mTitleRow.getLayoutParams();
        title.topMargin = Math.round(-(mTitleH + Ui.dp(mActivity, 14)) * p);
        mTitleRow.setLayoutParams(title);
        ViewGroup.LayoutParams chips = mChipScroll.getLayoutParams();
        chips.height = p <= 0f ? ViewGroup.LayoutParams.WRAP_CONTENT : Math.round(mChipH * (1f - p));
        mChipScroll.setLayoutParams(chips);
        mChipScroll.setAlpha(1f - clamp01(p * 1.6f));
        mChipScroll.setVisibility(p >= 1f ? INVISIBLE : VISIBLE);
    }

    private void measureStepOne() {
        // right under the controls when the page is compressed
        if (mControlsRow != null && mProgress1 >= 0.99f && mControlsRow.getHeight() > 0) {
            int[] c = new int[2];
            int[] me = new int[2];
            mControlsRow.getLocationOnScreen(c);
            getLocationOnScreen(me);
            float bottom = c[1] - me[1] + mControlsRow.getHeight();
            if (Math.abs(bottom - mY1) > 2f) {
                mY1 = bottom;
                if (mState == 1 && mAnim == null && !mDragging) {
                    setDrawerY(y1());
                }
            }
        }
        if (mTitleRow != null && mTitleRow.getHeight() > 0 && mProgress1 == 0f) {
            mTitleH = mTitleRow.getHeight();
        }
        if (mChipScroll != null && mChipScroll.getHeight() > 0 && mProgress1 == 0f) {
            mChipH = mChipScroll.getHeight();
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        measureStepOne();
    }

    public void animateTo(int state) {
        if (getHeight() == 0) {
            mState = state;
            return;
        }
        if (state != 0) {
            applyCompress(1f); // measure the compressed page once so the drawer sits right under the controls
        }
        mState = state;
        if (mAnim != null) {
            mAnim.cancel();
        }
        float from = Float.isNaN(mDrawerY) ? y0() : mDrawerY;
        float to = stateY(state);
        mAnim = android.animation.ValueAnimator.ofFloat(from, to);
        mAnim.setDuration(240);
        mAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        mAnim.addUpdateListener(an -> setDrawerY((float) an.getAnimatedValue()));
        mAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mAnim = null;
                setDrawerY(stateY(mState));
                post(MusicPlayerView.this::measureStepOne);
            }
        });
        mAnim.start();
    }

    private View findScrollable(View v) {
        if (v == null) {
            return null;
        }
        if (v instanceof androidx.recyclerview.widget.RecyclerView || v instanceof ScrollView || v instanceof android.widget.ListView) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findScrollable(g.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean queueAtTop() {
        View scrollable = findScrollable(mQueueContent);
        return scrollable == null || !scrollable.canScrollVertically(-1);
    }

    private boolean inside(View v, float x, float y) {
        int[] loc = new int[2];
        int[] me = new int[2];
        v.getLocationOnScreen(loc);
        getLocationOnScreen(me);
        float left = loc[0] - me[0];
        float top = loc[1] - me[1];
        return x >= left && x <= left + v.getWidth() && y >= top && y <= top + v.getHeight();
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        if (mSheet != null || mDrawer == null) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mDragging = false;
                mDownOnSeek = mSeek != null && inside(mSeek, ev.getX(), ev.getY());
                mDownInHeader = ev.getY() >= mDrawerY && ev.getY() <= mDrawerY + Ui.dp(mActivity, HEADER_DP);
                if (mVelocity != null) {
                    mVelocity.recycle();
                }
                mVelocity = android.view.VelocityTracker.obtain();
                mVelocity.addMovement(ev);
                return false;
            case android.view.MotionEvent.ACTION_MOVE: {
                if (mVelocity != null) {
                    mVelocity.addMovement(ev);
                }
                if (mDragging) {
                    return true;
                }
                float dy = ev.getY() - mDownY;
                float dx = ev.getX() - mDownX;
                int slop = android.view.ViewConfiguration.get(mActivity).getScaledTouchSlop();
                if (Math.abs(dy) < slop || Math.abs(dy) < Math.abs(dx) * 1.5f || mDownOnSeek) {
                    return false;
                }
                boolean take;
                if (mState == 0) {
                    take = dy < 0;
                } else if (mState == 1) {
                    take = true;
                } else {
                    take = dy > 0 && (mDownInHeader || queueAtTop());
                }
                if (take) {
                    mDragging = true;
                    if (mAnim != null) {
                        mAnim.cancel();
                        mAnim = null;
                    }
                    mDragStartState = mState;
                    mDragStartY = Float.isNaN(mDrawerY) ? y0() : mDrawerY;
                    mDragStartTouch = ev.getY();
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (mVelocity != null) {
            mVelocity.addMovement(ev);
        }
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    setDrawerY(mDragStartY + (ev.getY() - mDragStartTouch));
                    // measure the compressed page as soon as it is there so the middle stop is exact
                    if (mProgress1 >= 0.99f) {
                        measureStepOne();
                    }
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    mDragging = false;
                    float vy = 0f;
                    if (mVelocity != null) {
                        mVelocity.computeCurrentVelocity(1000);
                        vy = mVelocity.getYVelocity();
                    }
                    int target;
                    if (vy < -900f) {
                        target = Math.min(2, mDragStartState + 1);
                    } else if (vy > 900f) {
                        target = Math.max(0, mDragStartState - 1);
                    } else {
                        float y = mDrawerY;
                        target = 0;
                        float best = Math.abs(y - y0());
                        if (Math.abs(y - y1()) < best) {
                            best = Math.abs(y - y1());
                            target = 1;
                        }
                        if (Math.abs(y - y2()) < best) {
                            target = 2;
                        }
                    }
                    animateTo(target);
                }
                if (mVelocity != null) {
                    mVelocity.recycle();
                    mVelocity = null;
                }
                return true;
            default:
                return true;
        }
    }

    private void updateQueueBar() {
        if (mQueueLabel == null) {
            return;
        }
        String drawerTitle = mHost.queueTitle();
        mDrawerTitle.setText(drawerTitle != null && !drawerTitle.isEmpty() ? drawerTitle : (mHost.isPodcast() ? "Kö" : "Upp härnäst"));
        String title = mHost.queueTitle();
        if (title != null && !title.isEmpty()) {
            mQueueLabel.setText(title);
            return;
        }
        List<Video> queued = mHost.queued();
        Video next = !queued.isEmpty() ? queued.get(0) : null;
        if (next == null) {
            List<Video> rest = mHost.rest();
            next = rest.isEmpty() ? null : rest.get(0);
        }
        if (next == null) {
            mQueueLabel.setText(mHost.isPodcast() ? "Kö" : "Inga fler låtar i kön");
        } else {
            String author = next.getAuthor();
            mQueueLabel.setText("Nästa: " + next.getTitle() + (author != null && !mHost.isPodcast() ? " – " + author : ""));
        }
    }

    private ImageView icon(int res, int sizeDp, int padDp) {
        ImageView view = new ImageView(mActivity);
        view.setImageResource(res);
        view.setPadding(Ui.dp(mActivity, padDp), Ui.dp(mActivity, padDp), Ui.dp(mActivity, padDp), Ui.dp(mActivity, padDp));
        view.setBackground(Ui.roundRipple());
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
        view.setTextColor(color);
        view.setTextSize(sp);
        return view;
    }

    private View chip(int iconRes, String label, OnClickListener listener) {
        LinearLayout chip = new LinearLayout(mActivity);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Ui.pill(mActivity, CHIP_COLOR));
        chip.setPadding(Ui.dp(mActivity, 16), Ui.dp(mActivity, 10), Ui.dp(mActivity, 18), Ui.dp(mActivity, 10));
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        chip.addView(icon, new LinearLayout.LayoutParams(Ui.dp(mActivity, 22), Ui.dp(mActivity, 22)));
        TextView text = text(14, Color.WHITE);
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

    private static String speedLabel(float speed) {
        return (speed == Math.floor(speed) ? String.format(Locale.US, "%.0f", speed) : String.format(Locale.US, "%.2f", speed).replaceAll("0+$", "")) + "x";
    }

    private void cycleSpeed() {
        float[] speeds = mHost.speeds();
        float current = mHost.speed();
        float next = speeds[0];
        for (int i = 0; i < speeds.length; i++) {
            if (Math.abs(speeds[i] - current) < 0.01f) {
                next = speeds[(i + 1) % speeds.length];
                break;
            }
        }
        mHost.setSpeed(next);
        mSpeedChip.setText(speedLabel(next));
    }

    private void showDetails() {
        Video video = mHost.video();
        if (video == null) {
            return;
        }
        String text = video.description != null && !video.description.isEmpty() ? video.description : "Ingen beskrivning";
        new AlertDialog.Builder(mActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(video.getTitle())
                .setMessage(android.text.Html.fromHtml(text.replace("\n", "<br>")))
                .setPositiveButton("Stäng", null)
                .show();
    }

    private void chooseSleepTimer() {
        final int[] minutes = {0, 15, 30, 45, 60, 90};
        new AlertDialog.Builder(mActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Sovtimer")
                .setItems(new String[]{"Av", "15 minuter", "30 minuter", "45 minuter", "60 minuter", "90 minuter"}, (d, which) -> {
                    if (sSleep != null) {
                        sSleepHandler.removeCallbacks(sSleep);
                        sSleep = null;
                    }
                    if (minutes[which] > 0) {
                        final boolean podcast = mHost.isPodcast();
                        sSleep = () -> {
                            sSleep = null;
                            if (podcast) {
                                com.liskovsoft.smartyoutubetv2.mobile.ui.podcast.PodcastPlayer.pause();
                            } else {
                                androidx.media3.common.Player player = MediaPlaybackService.getPlayer();
                                if (player != null) {
                                    player.setPlayWhenReady(false);
                                }
                            }
                        };
                        sSleepHandler.postDelayed(sSleep, minutes[which] * 60_000L);
                        android.widget.Toast.makeText(mActivity, "Pausar om " + minutes[which] + " minuter", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    ///////////////////// the page color from the cover

    private void loadArt(String image) {
        if (image == null || image.equals(mShownImage)) {
            return;
        }
        mShownImage = image;
        Glide.with(mArt).load(image).into(mArt);
        Glide.with(mMiniArt).load(image).into(mMiniArt);
        Glide.with(mArt).asBitmap().load(image).override(48, 48).disallowHardwareConfig().into(new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                if (image.equals(mShownImage)) {
                    applyTint(tintOf(bitmap));
                }
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
    }

    /** A dark, slightly saturated version of the cover's main color (like YouTube Music's page). */
    private static int tintOf(Bitmap bitmap) {
        long r = 0, g = 0, b = 0, weight = 0;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int pixel = bitmap.getPixel(x, y);
                float[] hsv = new float[3];
                Color.colorToHSV(pixel, hsv);
                long wgt = 1 + (long) (hsv[1] * hsv[2] * 8); // colorful pixels count more than grey ones
                r += Color.red(pixel) * wgt;
                g += Color.green(pixel) * wgt;
                b += Color.blue(pixel) * wgt;
                weight += wgt;
            }
        }
        if (weight == 0) {
            return 0xFF1A1A1A;
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / weight), (int) (g / weight), (int) (b / weight), hsv);
        hsv[1] = Math.min(hsv[1], 0.65f);
        hsv[2] = Math.min(0.30f, Math.max(0.12f, hsv[2] * 0.4f));
        return Color.HSVToColor(hsv);
    }

    private void applyTint(int tint) {
        mTint = tint;
        int bottom = blend(tint, 0xFF0F0F0F, 0.55f);
        mPage.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{tint, bottom}));
        mScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{tint & 0x00FFFFFF, (tint & 0x00FFFFFF) | 0x99000000, (tint & 0x00FFFFFF) | 0xE6000000, tint}));
        paintDrawer();
        if (mSheet != null) {
            mSheet.setBackgroundColor(blend(tint, 0xFF0F0F0F, 0.35f));
        }
    }

    private static int blend(int a, int b, float amountOfB) {
        float keep = 1f - amountOfB;
        return Color.rgb(
                (int) (Color.red(a) * keep + Color.red(b) * amountOfB),
                (int) (Color.green(a) * keep + Color.green(b) * amountOfB),
                (int) (Color.blue(a) * keep + Color.blue(b) * amountOfB));
    }

    ///////////////////// sheets: the queue, the lyrics

    private FrameLayout openSheet(String title) {
        closeSheet();
        mSheet = new FrameLayout(mActivity);
        mSheet.setBackgroundColor(blend(mTint, 0xFF0F0F0F, 0.35f));
        mSheet.setClickable(true);
        addView(mSheet, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return mSheet;
    }

    private LinearLayout sheetColumn(String title) {
        LinearLayout column = new LinearLayout(mActivity);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(mActivity, 4), 0, Ui.dp(mActivity, 16), 0);
        ImageView close = icon(R.drawable.ic_chevron_down, 48, 12);
        close.setOnClickListener(v -> closeSheet());
        bar.addView(close);
        TextView label = text(18, Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setText(title);
        label.setPadding(Ui.dp(mActivity, 8), 0, 0, 0);
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(mActivity, 56)));
        return column;
    }

    private void closeSheet() {
        if (mSheet != null) {
            removeView(mSheet);
            mSheet = null;
        }
        mLyricsLines = null;
        mLyricsScroll = null;
        mLyricsCurrent = -1;
    }

    public void showQueue(boolean show) {
        if (!show) {
            if (mSheet != null) {
                closeSheet();
            } else if (mState != 0) {
                animateTo(0);
            }
            return;
        }
        animateTo(2);
    }

    /** The queue drawer is pulled up or a lyrics page is shown (back closes it). */
    public boolean isQueueShown() {
        return mSheet != null || mState != 0;
    }

    /** Only the "Nästa: ..." line at the bottom. */
    public void refreshQueueBar() {
        updateQueueBar();
    }

    /** The queue changed (song added, moved or the next song started). */
    public void refreshQueue() {
        updateQueueBar();
        if (mDrawerBody == null) {
            return;
        }

        mDrawerBody.removeAllViews();
        View queue = mHost.createQueue(mActivity, () -> animateTo(0));
        if (queue == null) {
            queue = QueueView.create(mActivity, mHost.video(), mHost.queued(), mHost.rest(), mHost.queueListener());
            if (queue instanceof ViewGroup && ((ViewGroup) queue).getChildCount() > 1 && ((ViewGroup) queue).getChildAt(0) instanceof TextView) {
                ((ViewGroup) queue).removeViewAt(0); // "Spelas just nu": the drawer has its own title
            }
        }
        mQueueContent = queue;
        mDrawerBody.addView(queue, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showLyrics() {
        Video video = mHost.video();
        if (video == null || video.videoId == null) {
            return;
        }
        openSheet(null);
        LinearLayout column = sheetColumn("Sångtexter");
        FrameLayout body = new FrameLayout(mActivity);
        column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mSheet.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (video.videoId.equals(mLyricsFor)) {
            showLyricsBody(body, mLyrics);
            return;
        }

        ProgressBar progress = new ProgressBar(mActivity);
        body.addView(progress, new FrameLayout.LayoutParams(Ui.dp(mActivity, 40), Ui.dp(mActivity, 40), Gravity.CENTER));
        final String videoId = video.videoId;
        final String title = video.getTitle();
        final String artist = video.getAuthor();
        final long duration = mHost.duration();
        final FrameLayout sheet = mSheet;
        sExecutor.execute(() -> {
            MusicApi.Lyrics lyrics = MusicApi.lyrics(videoId, title, artist, duration);
            mHandler.post(() -> {
                if (mReleased || mSheet != sheet || mSheet == null) {
                    return; // closed, another song, or the screen is gone
                }
                mLyricsFor = videoId;
                mLyrics = lyrics;
                {
                    body.removeAllViews();
                    showLyricsBody(body, lyrics);
                }
            });
        });
    }

    private void showLyricsBody(FrameLayout body, MusicApi.Lyrics lyrics) {
        if (lyrics == null) {
            TextView none = text(16, 0xB3FFFFFF);
            none.setGravity(Gravity.CENTER);
            none.setText("Inga sångtexter hittades för den här låten");
            body.addView(none, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            return;
        }

        mLyricsScroll = new ScrollView(mActivity);
        mLyricsScroll.setVerticalScrollBarEnabled(false);
        LinearLayout lines = new LinearLayout(mActivity);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(Ui.dp(mActivity, 24), Ui.dp(mActivity, 16), Ui.dp(mActivity, 24), Ui.dp(mActivity, 160));
        if (lyrics.isTimed()) {
            for (MusicApi.LyricLine line : lyrics.lines) {
                TextView view = text(24, 0x80FFFFFF);
                view.setTypeface(Typeface.DEFAULT_BOLD);
                view.setText(line.text.isEmpty() ? "♪" : line.text);
                view.setPadding(0, Ui.dp(mActivity, 8), 0, Ui.dp(mActivity, 8));
                view.setOnClickListener(v -> {
                    mHost.seekTo(line.startMs);
                    if (!mHost.isPlaying()) {
                        mHost.togglePlay();
                    }
                });
                lines.addView(view);
            }
            mLyricsLines = lines;
            mLyricsCurrent = -1;
        } else {
            TextView view = text(20, Color.WHITE);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setLineSpacing(Ui.dp(mActivity, 6), 1f);
            view.setText(lyrics.plain);
            lines.addView(view);
        }
        if (lyrics.source != null && !lyrics.source.isEmpty()) {
            TextView source = text(12, 0x99FFFFFF);
            source.setText(lyrics.source);
            source.setPadding(0, Ui.dp(mActivity, 24), 0, 0);
            lines.addView(source);
        }
        mLyricsScroll.addView(lines);
        body.addView(mLyricsScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Timed lyrics: the line being sung lights up and stays in the middle. */
    private void updateLyrics(long position) {
        if (mLyricsLines == null || mLyrics == null || !mLyrics.isTimed()) {
            return;
        }
        int current = -1;
        for (int i = 0; i < mLyrics.lines.size(); i++) {
            if (mLyrics.lines.get(i).startMs <= position + 150) {
                current = i;
            } else {
                break;
            }
        }
        if (current == mLyricsCurrent) {
            return;
        }
        mLyricsCurrent = current;
        for (int i = 0; i < mLyricsLines.getChildCount() && i < mLyrics.lines.size(); i++) {
            ((TextView) mLyricsLines.getChildAt(i)).setTextColor(i == current ? Color.WHITE : i < current ? 0x99FFFFFF : 0x66FFFFFF);
        }
        if (current >= 0 && current < mLyricsLines.getChildCount() && mLyricsScroll != null) {
            View line = mLyricsLines.getChildAt(current);
            int target = line.getTop() - mLyricsScroll.getHeight() / 3;
            mLyricsScroll.smoothScrollTo(0, Math.max(0, target));
        }
    }

    ///////////////////// state

    /** A new song or episode: cover, title, artist. */
    public void bind(Video video) {
        if (video == null) {
            return;
        }

        boolean changed = mShown == null || !video.equals(mShown);
        mShown = video;
        loadArt(video.getCardImageUrl());
        updateQueueBar();
        mTitle.setText(video.getTitle());
        mAuthor.setText(video.getAuthor() != null ? video.getAuthor() : "");
        if (changed) {
            refreshButtons();
            if (mSheet != null) {
                closeSheet(); // the lyrics were the last song's
            }
        }
        if (changed || mQueueContent == null) {
            refreshQueue(); // not for metadata updates of the same song (it would end a drag)
        }
        mMiniTitle.setText(video.getTitle());
        mMiniAuthor.setText(video.getAuthor() != null ? video.getAuthor() : "");
    }

    private void update() {
        long duration = mHost.duration();
        long position = mHost.position();
        boolean known = duration > 0 && duration < 24L * 60 * 60 * 1000;
        if (!mSeeking) {
            mPosition.setText(format(position));
            mSeek.setProgress(known ? (int) (position * 1000 / duration) : 0);
            mSeek.setSecondaryProgress(known ? (int) (mHost.buffered() * 1000 / duration) : 0);
        }
        mDuration.setText(known ? format(duration) : "");
        mPlay.setImageResource(mHost.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        mMiniPlay.setImageResource(mHost.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        updateLyrics(position);
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        mHandler.removeCallbacks(mTick);
        if (visibility == VISIBLE && isAttachedToWindow()) {
            if (!mHost.isPodcast()) {
                mHost.setSpeed(1f); // music: each time at normal speed (the video player shares the speed)
            }
            mSpeedChip.setText(speedLabel(mHost.isPodcast() ? mHost.speed() : 1f));
            refreshButtons();
            mHandler.post(mTick);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) {
            mHandler.post(mTick);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(mTick);
    }

    /** The host is destroyed: no more callbacks. */
    public void release() {
        mReleased = true;
        mHandler.removeCallbacksAndMessages(null);
    }

    private static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }
}
