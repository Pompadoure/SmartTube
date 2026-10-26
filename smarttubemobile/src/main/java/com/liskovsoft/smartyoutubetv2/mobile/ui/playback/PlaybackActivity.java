package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.ChatReceiver;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.ExoPlayerInitializer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer.CustomOverridesRenderersFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.browse.VideoAdapter;
import com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.RatioFrameLayout;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone watch page: the video on top (16:9 in portrait, the whole screen in landscape), then title, the actions and
 * the suggestions. The engine is the same as on TV (ExoPlayerController, the presenters' controllers).
 */
public class PlaybackActivity extends MobileActivity implements PlaybackView {
    private static final String TAG = PlaybackActivity.class.getSimpleName();
    private PlaybackPresenter mPlaybackPresenter;
    private ExoPlayerController mExoPlayerController;
    private ExoPlayerInitializer mPlayerInitializer;
    private ExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private RatioFrameLayout mPlayerBox;
    private View mHeader;
    private View mShortsLayer;
    private View mControls;
    private View mControlsTop, mControlsActions;
    private TextView mControlsTitle, mControlsAuthor, mTimeView;
    private ImageView mPlayPauseButton, mFullscreenButton;
    private android.widget.SeekBar mSeekBar;
    private boolean mSeeking;
    private boolean mInPip;
    private Video mPendingChannel; // opened once the floating window is up, so the video is not stopped half-way
    private boolean mPipSession; // the floating window was shown and the screen has not been opened again
    private boolean mFastForward;
    private float mSpeedBeforeFastForward = 1f;
    private TextView mSeekHint;
    private final Runnable mHideSeekHint = () -> mSeekHint.setVisibility(View.GONE);
    private final Runnable mHideControls = () -> showControls(false);
    private TextView mAuthorView, mSubsCountView, mDescView, mSubscribeButton;
    private LinearLayout mLikeChip, mDislikeChip;
    private TextView mShortLikeLabel, mShortAuthor, mShortTitle, mShortSubscribe;
    private ImageView mShortLikeIcon;
    private ImageView mShortAvatar;
    private android.widget.ProgressBar mShortProgress;
    private boolean mShortsMode;
    private boolean mDescExpanded;
    private long mLastSwipeMs;
    private Video mLastScrollEndVideo;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mTitleView;
    private TextView mMetaView;
    private ImageView mAvatarView;
    private LinearLayout mActionRow;
    private RecyclerView mSuggestionsList;
    private MusicPlayerView mMusicView;
    private boolean mMinimized;
    private VideoAdapter mSuggestionsAdapter;
    private final List<VideoGroup> mGroups = new ArrayList<>();
    private final Map<Integer, Integer> mButtonStates = new HashMap<>();
    private boolean mEngineBlocked;

    // Background playback / audio mode (music)
    public static final String EXTRA_FROM_NOTIFICATION = "from_notification";
    private static boolean sAskedNotifications;
    private static Boolean sPendingAudioMode; // set by the feed on a click, consumed by the screen
    private static PlaybackActivity sOrphan;
    private static java.lang.ref.WeakReference<PlaybackActivity> sActive;

    /** The queue changed (play next / add to queue): update the list of what comes next. */
    public static void refreshQueue() {
        PlaybackActivity active = sActive != null ? sActive.get() : null;
        if (active != null && !active.isDestroyed() && active.mMusicView != null) {
            active.mMusicView.refreshQueue();
        }
    }
    private boolean mAudioMode;
    private boolean mFinishRequested;
    private Player.Listener mPlayerListener;
    private boolean mBackgrounded;
    private LinearLayout mModePill;
    private ImageView mModeAudioButton, mModeVideoButton, mLandscapeAudioButton;
    private RatioFrameLayout mArtBox;
    private ImageView mArtView;
    private View mControlsCenter;

    /** The sound plays without a screen (swiped away / music screen closed). */
    public static boolean hasOrphan() {
        return sOrphan != null;
    }

    /** Stops the sound that plays without a screen. */
    public static void closeOrphan() {
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController.sMusicOnly = false;
        PlaybackActivity orphan = sOrphan;
        sOrphan = null;
        if (orphan != null) {
            orphan.destroyOrphan();
        }
    }

    /** The next playback starts as audio only (music) or as video. */
    public static void setAudioModeForNextPlayback(boolean audio) {
        sPendingAudioMode = audio;
    }

    private static boolean sMusicContext; // the user is in the music part of the app (artist pages inherit it)

    public static void setMusicContext(boolean music) {
        sMusicContext = music;
    }

    public static boolean isMusicContext() {
        return sMusicContext;
    }

    private boolean consumePendingAudioMode(boolean fallback) {
        Boolean pending = sPendingAudioMode;
        sPendingAudioMode = null;
        return pending != null ? pending : fallback;
    }
    private LinearLayout mRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);

        FrameLayout frame = new FrameLayout(this);
        frame.addView(createContent(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mMusicView = createMusicView();
        mMusicView.setVisibility(View.GONE);
        frame.addView(mMusicView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);

        sActive = new java.lang.ref.WeakReference<>(this);
        mAudioMode = consumePendingAudioMode(false);
        mPlaybackPresenter = PlaybackPresenter.instance(this);

        // Android 13+: the media notification (lock screen controls) needs this permission
        if (!sAskedNotifications && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sAskedNotifications = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        PlaybackActivity orphan = sOrphan;
        sOrphan = null;
        boolean fromNotification = getIntent().getBooleanExtra(EXTRA_FROM_NOTIFICATION, false);
        boolean adopt = orphan != null && orphan.mPlayer != null;

        if (adopt) {
            // The sound kept playing after the screen was closed: take the engine over (no restart; a video that was
            // just opened from the feed is already loading in it)
            mPlayerInitializer = orphan.mPlayerInitializer;
            mExoPlayerController = orphan.mExoPlayerController;
            mPlayer = orphan.mPlayer;
            if (orphan.mPlayerListener != null) {
                mPlayer.removeListener(orphan.mPlayerListener);
            }
            orphan.mPlayer = null;
            // from the notification: the music screen as it was; from the feed: the mode of the new start
            mAudioMode = consumePendingAudioMode(orphan.mAudioMode);
            wirePlayerView();
            mPlaybackPresenter.setView(this);
            mHandler.post(mShortsTick);
            Video current = mPlaybackPresenter.getVideo();
            if (current != null) {
                showVideoInfo(current);
            }
            applyOrientation(getResources().getConfiguration());
            applyAudioMode();
            return;
        }

        if (orphan != null) {
            orphan.destroyOrphan();
        } else if (fromNotification && mPlaybackPresenter.getVideo() == null) {
            // the notification outlived the playback: nothing to show
            MediaPlaybackService.unbind();
            finish();
            return;
        }

        mPlayerInitializer = new ExoPlayerInitializer(this);
        mPlaybackPresenter.setView(this);
        mExoPlayerController = new ExoPlayerController(this, mPlaybackPresenter);

        mPlaybackPresenter.onViewInitialized();
        mHandler.post(mShortsTick);

        applyOrientation(getResources().getConfiguration());
        applyAudioMode();
    }

    /** A screen that was closed while the music kept playing is thrown away when something else starts. */
    private void destroyOrphan() {
        releasePlayer();
        if (mPlaybackPresenter.getView() == this) {
            mPlaybackPresenter.onViewDestroyed();
        }
    }

    private static final int ID_LIKE = com.liskovsoft.smartyoutubetv2.common.R.id.action_thumbs_up;
    private static final int ID_DISLIKE = com.liskovsoft.smartyoutubetv2.common.R.id.action_thumbs_down;
    private static final int ID_SUBSCRIBE = com.liskovsoft.smartyoutubetv2.common.R.id.action_subscribe;
    private static final int ID_SAVE = com.liskovsoft.smartyoutubetv2.common.R.id.action_playlist_add;
    private static final int ID_COMMENTS = com.liskovsoft.smartyoutubetv2.common.R.id.action_chat;

    private View createContent() {
        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setBackgroundColor(getColor(R.color.yt_bg));

        mPlayerBox = new RatioFrameLayout(this);
        mPlayerBox.setBackgroundColor(0xFF000000);
        mPlayerView = new PlayerView(this);
        mPlayerView.setUseController(false);
        mPlayerView.setControllerShowTimeoutMs(3000);
        mPlayerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        mPlayerBox.addView(mPlayerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Audio mode: the cover instead of the video
        mArtBox = new RatioFrameLayout(this);
        mArtBox.setHeightPerWidth(1f);
        mArtBox.setVisibility(View.GONE);
        mArtView = new ImageView(this);
        mArtView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArtView.setBackgroundColor(getColor(R.color.yt_surface));
        Ui.makeRounded(mArtView, Ui.dp(this, 14));
        mArtBox.addView(mArtView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams artParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        artParams.setMargins(Ui.dp(this, 24), Ui.dp(this, 64), Ui.dp(this, 24), 0);
        mPlayerBox.addView(mArtBox, artParams);

        mShortsLayer = createShortsLayer();
        mPlayerBox.addView(mShortsLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mSeekHint = text(18, R.color.yt_text, true);
        mSeekHint.setVisibility(View.GONE);
        mSeekHint.setGravity(Gravity.CENTER);
        mSeekHint.setShadowLayer(6, 0, 0, 0xFF000000);
        mSeekHint.setPadding(Ui.dp(this, 36), Ui.dp(this, 12), Ui.dp(this, 36), Ui.dp(this, 12));

        mControls = createControls();
        mPlayerBox.addView(mControls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPlayerBox.addView(mSeekHint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.END));
        mPlayerBox.setOnTouchListener(createPlayerGestures());

        mRoot.addView(mPlayerBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mHeader = createHeader();

        mSuggestionsAdapter = new VideoAdapter(new VideoAdapter.Listener() {
            @Override
            public void onClick(Video video) {
                mPlaybackPresenter.onSuggestionItemClicked(video);
            }

            @Override
            public void onLongClick(Video video) {
                mPlaybackPresenter.onSuggestionItemLongClicked(video);
            }
        });
        mSuggestionsAdapter.setShortsAsWide(true);
        mSuggestionsList = new RecyclerView(this);
        GridLayoutManager manager = new GridLayoutManager(this, 1);
        mSuggestionsList.setLayoutManager(manager);
        mSuggestionsList.setAdapter(mSuggestionsAdapter);
        mSuggestionsList.setItemAnimator(null);
        mSuggestionsList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0) {
                    return;
                }

                int last = manager.findLastVisibleItemPosition();
                if (last >= mSuggestionsAdapter.size() - 4) {
                    Video lastVideo = mSuggestionsAdapter.lastVideo();
                    if (lastVideo != null && lastVideo != mLastScrollEndVideo) {
                        mLastScrollEndVideo = lastVideo;
                        mPlaybackPresenter.onScrollEnd(lastVideo);
                    }
                }
            }
        });
        mRoot.addView(mSuggestionsList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rebuildSuggestions();

        return mRoot;
    }

    private TextView text(int sizeSp, int colorRes, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(sizeSp);
        view.setTextColor(getColor(colorRes));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private ImageView avatar(int dpSize) {
        ImageView avatar = new ImageView(this);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.makeCircle(avatar);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, dpSize), Ui.dp(this, dpSize)));
        return avatar;
    }

    private TextView pill(String label) {
        TextView pill = text(14, R.color.yt_bg, true);
        pill.setText(label);
        pill.setGravity(Gravity.CENTER);
        pill.setBackgroundResource(R.drawable.bg_chip_selected);
        pill.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        return pill;
    }

    /** Title, meta, channel row, actions, comments card: the YouTube watch page block above the suggestions. */
    private View createHeader() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 4));

        mTitleView = text(18, R.color.yt_text, true);
        mTitleView.setMaxLines(2);
        mTitleView.setEllipsize(TextUtils.TruncateAt.END);
        mTitleView.setOnClickListener(v -> toggleDescription());
        box.addView(mTitleView);

        mMetaView = text(13, R.color.yt_text_secondary, false);
        mMetaView.setPadding(0, Ui.dp(this, 4), 0, 0);
        mMetaView.setOnClickListener(v -> toggleDescription());
        box.addView(mMetaView);

        mDescView = text(14, R.color.yt_text, false);
        mDescView.setPadding(0, Ui.dp(this, 8), 0, 0);
        mDescView.setVisibility(View.GONE);
        box.addView(mDescView);

        LinearLayout channelRow = new LinearLayout(this);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        channelRow.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
        mAvatarView = avatar(40);
        mAvatarView.setOnClickListener(v -> onChannelTapped());
        ((LinearLayout.LayoutParams) mAvatarView.getLayoutParams()).setMarginEnd(Ui.dp(this, 12));
        channelRow.addView(mAvatarView);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        mAuthorView = text(15, R.color.yt_text, true);
        mAuthorView.setOnClickListener(v -> onChannelTapped());
        mAuthorView.setMaxLines(1);
        mAuthorView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(mAuthorView);
        mSubsCountView = text(12, R.color.yt_text_secondary, false);
        names.addView(mSubsCountView);
        channelRow.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mSubscribeButton = pill("Prenumerera");
        mSubscribeButton.setOnClickListener(v -> mPlaybackPresenter.onButtonClicked(ID_SUBSCRIBE, getButtonState(ID_SUBSCRIBE)));
        mSubscribeButton.setOnLongClickListener(v -> {
            mPlaybackPresenter.onButtonLongClicked(ID_SUBSCRIBE, getButtonState(ID_SUBSCRIBE));
            return true;
        });
        channelRow.addView(mSubscribeButton);
        box.addView(channelRow);

        mActionRow = new LinearLayout(this);
        mActionRow.setOrientation(LinearLayout.HORIZONTAL);
        mLikeChip = addAction(R.drawable.ic_like, "", ID_LIKE);
        mDislikeChip = addAction(R.drawable.ic_dislike, "", ID_DISLIKE);
        addActionWith(R.drawable.ic_share, "Dela", v -> shareVideo());
        addAction(R.drawable.ic_save, "Spara", ID_SAVE);
        android.widget.HorizontalScrollView actionScroll = new android.widget.HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(mActionRow);
        box.addView(actionScroll);

        LinearLayout comments = new LinearLayout(this);
        comments.setOrientation(LinearLayout.VERTICAL);
        comments.setBackgroundResource(R.drawable.bg_chip);
        comments.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        TextView commentsTitle = text(15, R.color.yt_text, true);
        commentsTitle.setText("Kommentarer");
        comments.addView(commentsTitle);
        TextView commentsHint = text(13, R.color.yt_text_secondary, false);
        commentsHint.setText("Tryck för att läsa kommentarerna");
        commentsHint.setPadding(0, Ui.dp(this, 4), 0, 0);
        comments.addView(commentsHint);
        comments.setOnClickListener(v -> mPlaybackPresenter.onButtonClicked(ID_COMMENTS, 0));
        LinearLayout.LayoutParams commentsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        commentsParams.topMargin = Ui.dp(this, 12);
        box.addView(comments, commentsParams);

        return box;
    }


    private ImageView icon(int res, int sizeDp, int padDp) {
        ImageView view = new ImageView(this);
        view.setImageResource(res);
        view.setPadding(Ui.dp(this, padDp), Ui.dp(this, padDp), Ui.dp(this, padDp), Ui.dp(this, padDp));
        view.setBackground(null);
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        return view;
    }

    /** Pill with an icon and a short label (the YouTube action row). */
    private LinearLayout iconChip(int iconRes, String label) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        ImageView image = new ImageView(this);
        image.setImageResource(iconRes);
        chip.addView(image, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        TextView text = text(14, R.color.yt_text, false);
        text.setText(label);
        text.setPadding(Ui.dp(this, 6), 0, 0, 0);
        text.setVisibility(TextUtils.isEmpty(label) ? View.GONE : View.VISIBLE);
        chip.addView(text);
        chip.setTag(text);
        return chip;
    }

    private void setChipLabel(LinearLayout chip, String label) {
        TextView text = (TextView) chip.getTag();
        text.setText(label);
        text.setVisibility(TextUtils.isEmpty(label) ? View.GONE : View.VISIBLE);
    }

    private void setChipActive(LinearLayout chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        ((ImageView) chip.getChildAt(0)).setColorFilter(active ? 0xFF0F0F0F : 0xFFFFFFFF);
        ((TextView) chip.getTag()).setTextColor(active ? 0xFF0F0F0F : 0xFFF1F1F1);
    }

    private void shareVideo() {
        Video video = getVideo();
        if (video == null || video.videoId == null) {
            return;
        }

        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(android.content.Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=" + video.videoId);
        startActivity(android.content.Intent.createChooser(intent, null));
    }

    ///////////////////// player controls (YouTube style overlay)

    private View createControls() {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0x66000000);
        box.setVisibility(View.GONE);

        // top: collapse, title + channel, CC, settings
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView collapse = icon(R.drawable.ic_chevron_down, 48, 10);
        collapse.setOnClickListener(v -> enterMiniPlayer());
        top.addView(collapse);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        mControlsTitle = text(15, R.color.yt_text, true);
        mControlsTitle.setMaxLines(1);
        mControlsTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(mControlsTitle);
        mControlsAuthor = text(12, R.color.yt_text_secondary, false);
        mControlsAuthor.setMaxLines(1);
        titles.addView(mControlsAuthor);
        mControlsTop = titles;
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView cc = icon(R.drawable.ic_cc, 48, 12);
        cc.setOnClickListener(v -> mPlaybackPresenter.onButtonClicked(com.liskovsoft.smartyoutubetv2.common.R.id.lb_control_closed_captioning, 0));
        top.addView(cc);
        ImageView gear = icon(R.drawable.ic_settings, 48, 12);
        gear.setOnClickListener(v -> mPlaybackPresenter.onButtonClicked(com.liskovsoft.smartyoutubetv2.common.R.id.lb_control_high_quality, 0));
        gear.setOnLongClickListener(v -> {
            mPlaybackPresenter.onButtonLongClicked(com.liskovsoft.smartyoutubetv2.common.R.id.lb_control_high_quality, 0);
            return true;
        });
        top.addView(gear);
        box.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // audio / video switch (like YouTube Music), top centre in portrait
        mModePill = new LinearLayout(this);
        mModePill.setBackgroundResource(R.drawable.bg_mode_pill);
        mModePill.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        mModeAudioButton = modeButton(R.drawable.ic_headphones, true);
        mModeVideoButton = modeButton(R.drawable.ic_video_mode, false);
        mModePill.addView(mModeAudioButton);
        mModePill.addView(mModeVideoButton);
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pillParams.topMargin = Ui.dp(this, 6);
        box.addView(mModePill, pillParams);

        // center: previous, play/pause, next
        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER);
        ImageView prev = icon(R.drawable.ic_prev, 56, 12);
        prev.setOnClickListener(v -> mPlaybackPresenter.onPreviousClicked());
        center.addView(prev);
        mPlayPauseButton = icon(R.drawable.ic_pause, 72, 12);
        mPlayPauseButton.setOnClickListener(v -> {
            mExoPlayerController.setPlayWhenReady(!mExoPlayerController.getPlayWhenReady());
            scheduleHide();
        });
        LinearLayout.LayoutParams ppParams = (LinearLayout.LayoutParams) mPlayPauseButton.getLayoutParams();
        ppParams.setMargins(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
        center.addView(mPlayPauseButton);
        ImageView next = icon(R.drawable.ic_next, 56, 12);
        next.setOnClickListener(v -> mPlaybackPresenter.onNextClicked());
        center.addView(next);
        mControlsCenter = center;
        box.addView(center, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // bottom: time + fullscreen, seek bar, action icons
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        timeRow.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 4), 0);
        mTimeView = text(12, R.color.yt_text, false);
        timeRow.addView(mTimeView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mFullscreenButton = icon(R.drawable.ic_fullscreen, 40, 8);
        mFullscreenButton.setOnClickListener(v -> toggleFullscreen());
        timeRow.addView(mFullscreenButton);
        bottom.addView(timeRow);

        mSeekBar = new android.widget.SeekBar(this);
        mSeekBar.setMax(1000);
        mSeekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFF0000));
        mSeekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFF0000));
        mSeekBar.setSecondaryProgressTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
        mSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    long duration = mExoPlayerController.getDurationMs();
                    mTimeView.setText(formatTime(duration * progress / 1000) + " / " + formatTime(duration));
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                mSeeking = true;
                mHandler.removeCallbacks(mHideControls);
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                long duration = mExoPlayerController.getDurationMs();
                if (duration > 0) {
                    mExoPlayerController.setPositionMs(duration * seekBar.getProgress() / 1000);
                }
                mSeeking = false;
                scheduleHide();
            }
        });
        bottom.addView(mSeekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 4));
        addControlAction(actions, R.drawable.ic_like, ID_LIKE);
        addControlAction(actions, R.drawable.ic_dislike, ID_DISLIKE);
        addControlAction(actions, R.drawable.ic_comment, ID_COMMENTS);
        addControlAction(actions, R.drawable.ic_save, ID_SAVE);
        ImageView share = icon(R.drawable.ic_share, 48, 12);
        share.setOnClickListener(v -> shareVideo());
        actions.addView(share);
        mLandscapeAudioButton = icon(R.drawable.ic_headphones, 48, 12);
        mLandscapeAudioButton.setOnClickListener(v -> setAudioMode(!mAudioMode));
        actions.addView(mLandscapeAudioButton);
        ImageView more = icon(R.drawable.ic_more, 48, 12);
        more.setOnClickListener(v -> mPlaybackPresenter.onButtonLongClicked(com.liskovsoft.smartyoutubetv2.common.R.id.lb_control_high_quality, 0));
        actions.addView(more);
        mControlsActions = actions;
        bottom.addView(actions);
        box.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        return box;
    }

    private ImageView modeButton(int iconRes, boolean audio) {
        ImageView button = new ImageView(this);
        button.setImageResource(iconRes);
        button.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        button.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 38)));
        button.setOnClickListener(v -> setAudioMode(audio));
        return button;
    }

    private void setAudioMode(boolean audio) {
        if (audio == mAudioMode) {
            return;
        }

        mAudioMode = audio;
        applyAudioMode();
    }

    /** Audio mode: no video track (saves data and battery), the cover is shown, the controls stay. */
    private void applyAudioMode() {
        boolean audio = mAudioMode && !mShortsMode;
        com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController.sMusicOnly = audio;
        if (mSuggestionsAdapter != null) {
            rebuildSuggestions();
        }
        mModeAudioButton.setBackground(audio ? getDrawable(R.drawable.bg_mode_selected) : null);
        mModeVideoButton.setBackground(audio ? null : getDrawable(R.drawable.bg_mode_selected));
        applyVideoTrack();
        applyOrientation(getResources().getConfiguration());
        updateArt();
        if (mMusicView != null) {
            mMusicView.setVisibility(audio && !mInPip ? View.VISIBLE : View.GONE);
            mRoot.setVisibility(audio && !mInPip ? View.GONE : View.VISIBLE);
            if (audio) {
                mMusicView.bind(getVideo());
            } else {
                mMusicView.showQueue(false);
            }
        }

        if (audio && !mInPip) {
            showControls(true);
        } else if (!audio) {
            showControls(false);
        }
    }

    private void updateArt() {
        if (isDestroyed()) {
            return; // an orphan: its views are gone
        }

        Video video = mPlaybackPresenter.getVideo();
        if (video != null && video.getCardImageUrl() != null) {
            Glide.with(mArtView).load(video.getCardImageUrl()).into(mArtView);
        }
    }

    /** The video track is off in audio mode and while the screen is away. */
    private void applyVideoTrack() {
        if (mPlayer == null) {
            return;
        }

        boolean disable = (mAudioMode && !mShortsMode) || mBackgrounded;
        if (mPlayer.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) != disable) {
            mPlayer.setTrackSelectionParameters(mPlayer.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disable).build());
        }
    }

    private void addControlAction(LinearLayout row, int iconRes, int buttonId) {
        ImageView view = icon(iconRes, 48, 12);
        view.setOnClickListener(v -> mPlaybackPresenter.onButtonClicked(buttonId, getButtonState(buttonId)));
        view.setOnLongClickListener(v -> {
            mPlaybackPresenter.onButtonLongClicked(buttonId, getButtonState(buttonId));
            return true;
        });
        row.addView(view);
    }

    private View.OnTouchListener createPlayerGestures() {
        final GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // Hold the right half: fast forward (2x) until the finger is lifted
                if (mShortsMode || mInPip || e.getX() <= mPlayerBox.getWidth() / 2f) {
                    return;
                }

                mFastForward = true;
                mSpeedBeforeFastForward = mExoPlayerController.getSpeed();
                mExoPlayerController.setSpeed(2.0f);
                mHandler.removeCallbacks(mHideSeekHint);
                mSeekHint.setText("2x  Snabbspolning");
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mSeekHint.getLayoutParams();
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                params.topMargin = Ui.dp(PlaybackActivity.this, 48);
                mSeekHint.setLayoutParams(params);
                mSeekHint.setVisibility(View.VISIBLE);
                mPlayerBox.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mShortsMode) {
                    return false;
                }

                boolean forward = e.getX() > mPlayerBox.getWidth() / 2f;
                seekBy(forward ? 10_000 : -10_000, forward);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null || mShortsMode) {
                    return false;
                }

                float dy = e2.getY() - e1.getY();
                if (dy > Ui.dp(PlaybackActivity.this, 80) && Math.abs(dy) > Math.abs(e2.getX() - e1.getX())) {
                    enterMiniPlayer();
                    return true;
                }

                return false;
            }
        });
        return (v, event) -> {
            int action = event.getActionMasked();
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && mFastForward) {
                mFastForward = false;
                mExoPlayerController.setSpeed(mSpeedBeforeFastForward);
                mSeekHint.setVisibility(View.GONE);
                ((FrameLayout.LayoutParams) mSeekHint.getLayoutParams()).topMargin = 0;
            }
            return detector.onTouchEvent(event);
        };
    }

    private void seekBy(long deltaMs, boolean forward) {
        long duration = mExoPlayerController.getDurationMs();
        long target = mExoPlayerController.getPositionMs() + deltaMs;
        target = Math.max(0, duration > 0 && duration < Video.MAX_LIVE_DURATION_MS ? Math.min(target, duration) : target);
        mExoPlayerController.setPositionMs(target);

        mSeekHint.setText((forward ? "+" : "-") + "10 s");
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mSeekHint.getLayoutParams();
        params.gravity = Gravity.CENTER_VERTICAL | (forward ? Gravity.END : Gravity.START);
        mSeekHint.setLayoutParams(params);
        mSeekHint.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideSeekHint);
        mHandler.postDelayed(mHideSeekHint, 700);
    }

    /** Swipe down / chevron: the video goes to a small floating window and the grid behind it can be scrolled. */
    private void enterMiniPlayer() {
        if (mShortsMode || mPlayer == null) {
            finish();
            return;
        }

        if (!mAudioMode) {
            enterPip(); // regular videos: the small floating window as before
            return;
        }

        // Music: the sound goes on without this screen and a mini player sits at the bottom of the music part; a tap
        // on it brings the song back in full screen.
        if (!MediaPlaybackService.isActive()) {
            finish();
            return;
        }

        mMinimized = true;
        finish();
    }

    /**
     * The channel avatar or name was tapped (watch page or Shorts overlay): the video goes to the small floating
     * window and the channel page opens behind it.
     */
    private void onChannelTapped() {
        Video video = mPlaybackPresenter.getVideo();
        if (video == null) {
            return;
        }

        // Regular video: the floating window keeps playing. Shorts and music have no floating window; they just
        // end up behind the channel page.
        if (!mShortsMode && !mAudioMode && mPlayer != null && !mInPip && android.os.Build.VERSION.SDK_INT >= 26) {
            // The channel page opens from onPictureInPictureModeChanged, not before: opening it first would stop the
            // video track. The timer is the way out if the system never reports the window.
            mPendingChannel = video;
            enterPip();
            if (!isFinishing()) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::openPendingChannel, 800);
            } else {
                openPendingChannel(); // no floating window possible: the channel page still opens
            }
            return;
        }

        mPendingChannel = video;
        openPendingChannel();
    }

    private void openPendingChannel() {
        Video video = mPendingChannel;
        mPendingChannel = null;
        if (video != null) {
            com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter.instance(this).openChannel(video);
        }
    }

    /** Leaving the app with a video playing: the small floating window. */
    private void enterPip() {
        if (android.os.Build.VERSION.SDK_INT < 26 || mShortsMode) {
            finish();
            return;
        }

        try {
            enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(new android.util.Rational(16, 9))
                    .build());
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't enter PiP: " + e.getMessage());
            finish();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        if (!mShortsMode && !mAudioMode && mPlayer != null && mPlayer.isPlaying() && android.os.Build.VERSION.SDK_INT >= 26) {
            enterPip();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        mInPip = isInPictureInPictureMode;
        if (isInPictureInPictureMode) {
            mPipSession = true;
            openPendingChannel();
        }

        if (mInPip) {
            showControls(false);
            mSeekHint.setVisibility(View.GONE);
        }

        applyOrientation(newConfig);
    }

    private void toggleControls() {
        if (mShortsMode) {
            return;
        }

        showControls(mControls.getVisibility() != View.VISIBLE);
    }

    private void scheduleHide() {
        mHandler.removeCallbacks(mHideControls);
        if (mControls.getVisibility() == View.VISIBLE) {
            mHandler.postDelayed(mHideControls, 3500);
        }
    }

    private void toggleFullscreen() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // hand the rotation back to the sensor once it happened
        mHandler.postDelayed(() -> setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED), 2500);
    }

    private static String formatTime(long ms) {
        long total = Math.max(ms, 0) / 1000;
        long h = total / 3600;
        long m = (total / 60) % 60;
        long sec = total % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(java.util.Locale.US, "%d:%02d", m, sec);
    }

    private void toggleDescription() {
        mDescExpanded = !mDescExpanded;
        updateDescription();
    }

    private void updateDescription() {
        Video video = getVideo();
        String desc = video != null ? video.description : null;
        boolean has = !TextUtils.isEmpty(desc);
        mDescView.setVisibility(mDescExpanded && has ? View.VISIBLE : View.GONE);
        mDescView.setText(desc);
        mTitleView.setMaxLines(mDescExpanded ? 6 : 2);
    }

    private LinearLayout addAction(int iconRes, String title, int buttonId) {
        LinearLayout chip = addActionWith(iconRes, title, v -> mPlaybackPresenter.onButtonClicked(buttonId, getButtonState(buttonId)));
        chip.setOnLongClickListener(v -> {
            mPlaybackPresenter.onButtonLongClicked(buttonId, getButtonState(buttonId));
            return true;
        });
        return chip;
    }

    private LinearLayout addActionWith(int iconRes, String title, View.OnClickListener click) {
        LinearLayout chip = iconChip(iconRes, title);
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(this, 8));
        mActionRow.addView(chip, params);
        return chip;
    }

    ///////////////////// Shorts

    private View createShortsLayer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setVisibility(View.GONE);

        // swipe up/down = next/previous Short, tap = pause/play
        final GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                mExoPlayerController.setPlayWhenReady(!mExoPlayerController.getPlayWhenReady());
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) {
                    return false;
                }

                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();

                if (Math.abs(dy) < Ui.dp(PlaybackActivity.this, 60) || Math.abs(dy) < Math.abs(dx)) {
                    return false;
                }

                long now = SystemClock.elapsedRealtime();
                if (now - mLastSwipeMs < 350) {
                    return true;
                }
                mLastSwipeMs = now;

                if (dy < 0) {
                    mPlaybackPresenter.onNextClicked();
                } else {
                    mPlaybackPresenter.onPreviousClicked();
                }
                return true;
            }
        });
        View touch = new View(this);
        touch.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
        layer.addView(touch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // top: back, search, more
        LinearLayout shortsTop = new LinearLayout(this);
        shortsTop.setGravity(Gravity.CENTER_VERTICAL);
        ImageView shortsBack = icon(R.drawable.ic_back, 48, 12);
        shortsBack.setOnClickListener(v -> finish());
        shortsTop.addView(shortsBack, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        shortsTop.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView shortsSearch = icon(R.drawable.ic_search, 48, 12);
        shortsSearch.setOnClickListener(v -> com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter.instance(this).startSearch(null));
        shortsTop.addView(shortsSearch);
        ImageView shortsMore = icon(R.drawable.ic_more, 48, 12);
        shortsMore.setOnClickListener(v -> mPlaybackPresenter.onButtonLongClicked(com.liskovsoft.smartyoutubetv2.common.R.id.lb_control_high_quality, 0));
        shortsTop.addView(shortsMore);
        layer.addView(shortsTop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // right column: like, dislike, comments, save, share
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        mShortLikeIcon = shortAction(column, R.drawable.ic_heart, "", ID_LIKE);
        mShortLikeLabel = (TextView) mShortLikeIcon.getTag();
        shortAction(column, R.drawable.ic_dislike, "", ID_DISLIKE);
        shortAction(column, R.drawable.ic_comment, "", ID_COMMENTS);
        shortAction(column, R.drawable.ic_save, "", ID_SAVE);
        ImageView shareIcon = shortAction(column, R.drawable.ic_share, "", 0);
        shareIcon.setOnClickListener(v -> shareVideo());
        ((View) shareIcon.getParent()).setOnClickListener(v -> shareVideo());
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
        columnParams.setMargins(0, 0, Ui.dp(this, 8), Ui.dp(this, 100));
        layer.addView(column, columnParams);

        // bottom left: channel + subscribe + title
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 80), Ui.dp(this, 16));
        LinearLayout channel = new LinearLayout(this);
        channel.setGravity(Gravity.CENTER_VERTICAL);
        mShortAvatar = avatar(36);
        mShortAvatar.setOnClickListener(v -> onChannelTapped());
        ((LinearLayout.LayoutParams) mShortAvatar.getLayoutParams()).setMarginEnd(Ui.dp(this, 10));
        channel.addView(mShortAvatar);
        mShortAuthor = text(15, R.color.yt_text, true);
        mShortAuthor.setOnClickListener(v -> onChannelTapped());
        mShortAuthor.setMaxLines(1);
        mShortAuthor.setEllipsize(TextUtils.TruncateAt.END);
        mShortAuthor.setShadowLayer(4, 0, 0, 0xFF000000);
        LinearLayout.LayoutParams authorParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        authorParams.setMarginEnd(Ui.dp(this, 10));
        channel.addView(mShortAuthor, authorParams);
        mShortSubscribe = pill("Prenumerera");
        mShortSubscribe.setOnClickListener(v -> mPlaybackPresenter.onButtonClicked(ID_SUBSCRIBE, getButtonState(ID_SUBSCRIBE)));
        channel.addView(mShortSubscribe);
        bottom.addView(channel);
        mShortTitle = text(14, R.color.yt_text, false);
        mShortTitle.setMaxLines(2);
        mShortTitle.setEllipsize(TextUtils.TruncateAt.END);
        mShortTitle.setShadowLayer(4, 0, 0, 0xFF000000);
        mShortTitle.setPadding(0, Ui.dp(this, 10), 0, 0);
        bottom.addView(mShortTitle);
        layer.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        mShortProgress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mShortProgress.setMax(1000);
        mShortProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        layer.addView(mShortProgress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3), Gravity.BOTTOM));

        return layer;
    }

    private ImageView shortAction(LinearLayout column, int iconRes, String label, int buttonId) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 6));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        item.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView text = text(12, R.color.yt_text, false);
        text.setText(label);
        text.setShadowLayer(4, 0, 0, 0xFF000000);
        text.setVisibility(TextUtils.isEmpty(label) ? View.GONE : View.VISIBLE);
        item.addView(text);
        icon.setTag(text);
        if (buttonId != 0) {
            item.setOnClickListener(v -> mPlaybackPresenter.onButtonClicked(buttonId, getButtonState(buttonId)));
            item.setOnLongClickListener(v -> {
                mPlaybackPresenter.onButtonLongClicked(buttonId, getButtonState(buttonId));
                return true;
            });
        }
        column.addView(item);
        return icon;
    }

    private void setShortsMode(boolean on) {
        if (on == mShortsMode) {
            return;
        }

        mShortsMode = on;
        mShortsLayer.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && mMusicView != null) {
            mMusicView.setVisibility(View.GONE); // a Short is never shown over the music screen
            mRoot.setVisibility(View.VISIBLE);
        }
        if (mPlayer != null) {
            applyShortsResize(mPlayer.getVideoSize());
        }
        if (on) {
            mControls.setVisibility(View.GONE);
        }
        applyOrientation(getResources().getConfiguration());


    }

    private final Runnable mShortsTick = new Runnable() {
        @Override
        public void run() {
            if (mExoPlayerController != null && mPlayer != null) {
                long duration = mExoPlayerController.getDurationMs();
                long position = mExoPlayerController.getPositionMs();
                boolean known = duration > 0 && duration < Video.MAX_LIVE_DURATION_MS;
                int progress = known ? (int) (1000L * position / duration) : 0;

                if (mShortsMode) {
                    mShortProgress.setProgress(progress);
                } else if (mControls.getVisibility() == View.VISIBLE && !mSeeking) {
                    mSeekBar.setProgress(progress);
                    mSeekBar.setSecondaryProgress(known ? (int) (1000L * mPlayer.getBufferedPosition() / duration) : 0);
                    mTimeView.setText(formatTime(position) + " / " + formatTime(known ? duration : position));
                    mPlayPauseButton.setImageResource(mExoPlayerController.getPlayWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play);
                }
            }
            mHandler.postDelayed(this, 250);
        }
    };

    /** A Short fills the screen (zoom); an ordinary wide video that is opened in this mode keeps its edges. */
    private void applyShortsResize(androidx.media3.common.VideoSize size) {
        if (!mShortsMode) {
            mPlayerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            return;
        }

        // fit: the whole picture, full width (zoom cut the on-screen texts at the edges)
        mPlayerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    private void refreshButtons() {
        boolean subscribed = getButtonState(ID_SUBSCRIBE) == BUTTON_ON;
        for (TextView pill : new TextView[]{mSubscribeButton, mShortSubscribe}) {
            if (pill != null) {
                pill.setText(subscribed ? "Prenumererar" : "Prenumerera");
                pill.setBackgroundResource(subscribed ? R.drawable.bg_chip : R.drawable.bg_chip_selected);
                pill.setTextColor(getColor(subscribed ? R.color.yt_text : R.color.yt_bg));
            }
        }

        boolean liked = getButtonState(ID_LIKE) == BUTTON_ON;
        boolean disliked = getButtonState(ID_DISLIKE) == BUTTON_ON;
        Video video = getVideo();
        String likes = video != null && video.likeCount != null ? video.likeCount : "";
        setChipLabel(mLikeChip, likes);
        setChipActive(mLikeChip, liked);
        setChipActive(mDislikeChip, disliked);
        mShortLikeLabel.setText(likes);
        mShortLikeLabel.setVisibility(likes.isEmpty() ? View.GONE : View.VISIBLE);
        mShortLikeIcon.setColorFilter(liked ? 0xFFFF4E45 : 0xFFFFFFFF);
        if (mMusicView != null) {
            mMusicView.refreshButtons();
        }
    }

    private void applyOrientation(Configuration config) {
        boolean landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean fullscreen = landscape || mShortsMode || mInPip;
        mPlayerBox.setFullscreen(fullscreen);
        mShortsLayer.setVisibility(mShortsMode && !mInPip ? View.VISIBLE : View.GONE);
        boolean audioLayout = mAudioMode && !mShortsMode && !landscape && !mInPip;
        mPlayerBox.setHeightPerWidth(audioLayout ? 1.42f : 9f / 16f);
        mArtBox.setVisibility(audioLayout ? View.VISIBLE : View.GONE);
        mModePill.setVisibility(landscape || mShortsMode ? View.GONE : View.VISIBLE);
        mLandscapeAudioButton.setVisibility(landscape ? View.VISIBLE : View.GONE);
        mLandscapeAudioButton.setAlpha(mAudioMode ? 1f : 0.6f);
        FrameLayout.LayoutParams centerParams = (FrameLayout.LayoutParams) mControlsCenter.getLayoutParams();
        centerParams.gravity = audioLayout ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
        centerParams.bottomMargin = audioLayout ? Ui.dp(this, 96) : 0;
        mControlsCenter.setLayoutParams(centerParams);
        mSuggestionsList.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        mControlsTop.setVisibility(landscape ? View.VISIBLE : View.INVISIBLE);
        mControlsActions.setVisibility(landscape ? View.VISIBLE : View.GONE);
        mFullscreenButton.setImageResource(landscape ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);

        // Only the landscape player hides the phone's status/navigation bars. A Short keeps them (the video
        // doesn't play behind the status bar), the watch page too.
        mImmersive = landscape;
        applySystemUi();
    }

    private boolean mImmersive;

    private void applySystemUi() {
        android.view.Window window = getWindow();
        View decor = window.getDecorView();
        window.setStatusBarColor(0xFF0F0F0F);
        window.setNavigationBarColor(0xFF0F0F0F);

        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.layoutInDisplayCutoutMode = mImmersive
                    ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            window.setAttributes(params);
        }

        if (mImmersive) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            if (android.os.Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
                window.getInsetsController().setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                window.getInsetsController().hide(android.view.WindowInsets.Type.systemBars());
            }
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            if (android.os.Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
                window.getInsetsController().show(android.view.WindowInsets.Type.systemBars());
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            applySystemUi(); // the bars come back after a dialog / gesture: hide them again in landscape
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyOrientation(newConfig);
    }

    ///////////////////// player lifecycle

    @Override
    protected void onStart() {
        super.onStart();

        if (sPendingAudioMode != null) {
            boolean pending = consumePendingAudioMode(mAudioMode);
            if (pending != mAudioMode) {
                mAudioMode = pending;
                applyAudioMode();
            }
        }

        if (mPlayer != null) {
            // back from the background with a living engine: the video comes back
            mBackgrounded = false;
            mPlayerView.setPlayer(mPlayer);
            applyVideoTrack();
        }

        if (Util.SDK_INT > 23) {
            initializePlayer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPipSession = false; // opened again from the floating window

        if (Util.SDK_INT <= 23 || mPlayer == null) {
            initializePlayer();
        }

        mPlaybackPresenter.onViewResumed();
        blockEngine(false);
    }

    @Override
    protected void onPause() {
        super.onPause();

        mPlaybackPresenter.onViewPaused();

        if (Util.SDK_INT <= 23) {
            maybeReleasePlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mPipSession && !mAudioMode && !mShortsMode && !isChangingConfigurations()) {
            // The floating window was closed (X): the video ends, no sound is left behind
            mPipSession = false;
            if (mExoPlayerController != null) {
                mExoPlayerController.setPlayWhenReady(false);
            }
            finish();
            return;
        }

        if (keepPlayingInBackground() || (mMinimized && keepForMini())) {
            // Screen off / another app: the sound goes on (media notification), the video is dropped
            mBackgrounded = true;
            applyVideoTrack();
            mPlayerView.setPlayer(null);
            return;
        }

        if (Util.SDK_INT > 23) {
            maybeReleasePlayer();
        }
    }

    /** Minimized (also when paused): the engine lives on for the mini player. */
    private boolean keepForMini() {
        return mPlayer != null && mPlayer.getPlaybackState() != Player.STATE_ENDED && !mShortsMode && !isChangingConfigurations() && MediaPlaybackService.isActive();
    }

    private boolean keepPlayingInBackground() {
        return mPlayer != null && mPlayer.getPlayWhenReady() && mPlayer.getPlaybackState() != Player.STATE_ENDED
                && !mShortsMode && !isChangingConfigurations() && MediaPlaybackService.isActive();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        mHandler.removeCallbacks(mShortsTick);
        mHandler.removeCallbacks(mHideControls);

        // The app was swiped away (or the music screen closed) while playing: the sound goes on, the engine lives on
        // as an "orphan" until the user comes back through the notification or starts something else.
        if (((keepPlayingInBackground() && !mInPip && (!mFinishRequested || mAudioMode)) || (mMinimized && keepForMini())) && !mInPip) {
            sOrphan = this;
            return;
        }

        releasePlayer();

        if (mPlaybackPresenter.getView() == this) {
            mPlaybackPresenter.onViewDestroyed();
        }
    }

    private void initializePlayer() {
        if (mPlayer != null) {
            return;
        }

        DefaultTrackSelector trackSelector = newTrackSelector();
        mExoPlayerController.setTrackSelector(trackSelector);

        DefaultRenderersFactory renderersFactory = new CustomOverridesRenderersFactory(this);
        mPlayer = mPlayerInitializer.createPlayer(this, renderersFactory, trackSelector, ctx -> {
            RestoreTrackSelector preloadSelector = newTrackSelector();
            mExoPlayerController.setPreloadTrackSelector(preloadSelector);
            return preloadSelector;
        });

        mPlayer.setWakeMode(C.WAKE_MODE_NETWORK); // keeps the CPU and Wi-Fi awake while the screen is off
        mExoPlayerController.setPlayer(mPlayer);
        mExoPlayerController.setPreloadManager(mPlayerInitializer.getPreloadManager(), mPlayerInitializer.getPreloadControl());
        wirePlayerView();
        applyVideoTrack();

        mPlaybackPresenter.setView(this);
        mPlaybackPresenter.onEngineInitialized();
    }

    /** Connects the (possibly taken over) engine with this screen's views. */
    private void wirePlayerView() {
        mExoPlayerController.setPlayerView(new com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.PlayerView() {
            @Override
            public void setQualityInfo(String info) {
                // shown by the stats dialog on TV; not on the phone
            }

            @Override
            public void setVideo(Video video) {
                showVideoInfo(video);
            }
        });
        mPlayerView.setPlayer(mPlayer);
        if (mPlayerListener != null) {
            mPlayer.removeListener(mPlayerListener);
        }
        mPlayerListener = new androidx.media3.common.Player.Listener() {
            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                applyShortsResize(videoSize);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    applyVideoTrack(); // a new track must not bring the video back in audio mode
                }
            }
        };
        mPlayer.addListener(mPlayerListener);
        MediaPlaybackService.bind(this, mPlayer);
    }

    private RestoreTrackSelector newTrackSelector() {
        return new RestoreTrackSelector(new AdaptiveTrackSelection.Factory(3_000, 25_000, 25_000, 0.85f));
    }

    private void maybeReleasePlayer() {
        if (mEngineBlocked) {
            return;
        }

        releasePlayer();
    }

    private void releasePlayer() {
        if (mPlayer == null) {
            return;
        }

        Log.d(TAG, "releasePlayer: Start releasing player engine...");
        mPlaybackPresenter.onEngineReleased();
        mPlayerView.setPlayer(null);
        MediaPlaybackService.unbind();
        mPlayerInitializer.release();
        mExoPlayerController.release();
        mPlayer = null;
    }

    private void showVideoInfo(Video video) {
        if (video == null) {
            return;
        }

        MediaPlaybackService.setVideo(video);
        MediaPlaybackService.notifyMetadataChanged();
        if (isDestroyed()) {
            return; // an orphan (sound without a screen) only feeds the notification
        }
        if (mMusicView != null && mAudioMode) {
            mMusicView.bind(video); // cover, title, queue
        }

        String title = video.getTitleFull() != null ? video.getTitleFull() : "...";
        String author = video.getAuthor();
        mTitleView.setText(title);
        mShortTitle.setText(title);
        mControlsTitle.setText(title);
        mControlsAuthor.setText(author);

        // "author • views • date": the author has its own row, don't repeat it
        String second = video.getSecondTitleFull() != null ? video.getSecondTitleFull().toString() : "";
        if (author != null && second.startsWith(author)) {
            second = second.substring(author.length()).replaceFirst("^\\s*[•·-]\\s*", "");
        }
        StringBuilder meta = new StringBuilder();
        if (!TextUtils.isEmpty(second)) {
            meta.append(second);
        }
        mMetaView.setText(meta);
        mMetaView.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);

        mAuthorView.setText(author);
        mShortAuthor.setText(author != null ? "@" + author : "");
        mSubsCountView.setText(video.subscriberCount);
        mSubsCountView.setVisibility(TextUtils.isEmpty(video.subscriberCount) ? View.GONE : View.VISIBLE);

        if (video.authorImageUrl != null) {
            setChannelIcon(video.authorImageUrl);
        }

        updateDescription();
        refreshButtons();
        setShortsMode(video.isShorts && !video.isLive);
        updateArt();
    }

    ///////////////////// PlayerManager

    @Override
    public void setVideo(Video video) {
        mExoPlayerController.setVideo(video);
        showVideoInfo(video);
    }

    @Override
    public Video getVideo() {
        return mExoPlayerController != null ? mExoPlayerController.getVideo() : null;
    }

    @Override
    public void showBackground(String url) {
        // no background behind the video
    }

    @Override
    public void hideTransition() {
        // no transition cover on the phone yet
    }

    @Override
    public void showBackgroundColor(int colorResId) {
        // no background behind the video
    }

    @Override
    public void resetPlayerState() {
        mExoPlayerController.resetPlayerState();
        setChatReceiver(null);
        setSeekBarSegments(null);
    }

    @Override
    public boolean isEmbed() {
        return false;
    }

    @Override
    public void finish() {
        mFinishRequested = true;
        super.finish();
    }

    @Override
    public void finishReally() {
        mFinishRequested = true;
        super.finishReally();
    }

    ///////////////////// PlayerEngine

    @Override
    public void openSabr(MediaItemFormatInfo formatInfo) {
        mExoPlayerController.openSabr(formatInfo);
    }

    @Override
    public void openDash(MediaItemFormatInfo formatInfo) {
        mExoPlayerController.openDash(formatInfo);
    }

    @Override
    public void openDash(InputStream dashManifest) {
        mExoPlayerController.openDash(dashManifest);
    }

    @Override
    public void openDashUrl(String dashManifestUrl) {
        mExoPlayerController.openDashUrl(dashManifestUrl);
    }

    @Override
    public void openHlsUrl(String hlsPlaylistUrl) {
        mExoPlayerController.openHlsUrl(hlsPlaylistUrl);
    }

    @Override
    public void openUrlList(List<String> urlList) {
        mExoPlayerController.openUrlList(urlList);
    }

    @Override
    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        mExoPlayerController.openMerged(formatInfo, hlsPlaylistUrl);
    }

    @Override
    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        mExoPlayerController.openMerged(dashManifest, hlsPlaylistUrl);
    }

    @Override
    public long getPositionMs() {
        return mExoPlayerController.getPositionMs();
    }

    @Override
    public void setPositionMs(long positionMs) {
        mExoPlayerController.setPositionMs(positionMs);
    }

    @Override
    public void setStartPositionMs(long positionMs) {
        mExoPlayerController.setStartPositionMs(positionMs);
    }

    @Override
    public long getDurationMs() {
        long durationMs = mExoPlayerController.getDurationMs();
        long liveDurationMs = getVideo() != null ? getVideo().getLiveDurationMs() : 0;

        if (durationMs > Video.MAX_LIVE_DURATION_MS && liveDurationMs != 0) {
            durationMs = liveDurationMs;
        }

        return durationMs;
    }

    @Override
    public void setPlayWhenReady(boolean play) {
        mExoPlayerController.setPlayWhenReady(play);
    }

    @Override
    public boolean getPlayWhenReady() {
        return mExoPlayerController.getPlayWhenReady();
    }

    @Override
    public boolean isPlaying() {
        return mExoPlayerController.isPlaying();
    }

    @Override
    public boolean isLoading() {
        return mExoPlayerController.isLoading();
    }

    @Override
    public List<FormatItem> getVideoFormats() {
        return mExoPlayerController.getVideoFormats();
    }

    @Override
    public List<FormatItem> getAudioFormats() {
        return mExoPlayerController.getAudioFormats();
    }

    @Override
    public List<FormatItem> getSubtitleFormats() {
        return mExoPlayerController.getSubtitleFormats();
    }

    @Override
    public void setFormat(FormatItem formatItem) {
        mExoPlayerController.selectFormat(formatItem);
    }

    @Override
    public FormatItem getVideoFormat() {
        return mExoPlayerController.getVideoFormat();
    }

    @Override
    public FormatItem getAudioFormat() {
        return mExoPlayerController.getAudioFormat();
    }

    @Override
    public FormatItem getSubtitleFormat() {
        return mExoPlayerController.getSubtitleFormat();
    }

    @Override
    public boolean isEngineInitialized() {
        return mPlayer != null;
    }

    @Override
    public void restartEngine() {
        releasePlayer();
        initializePlayer();
    }

    @Override
    public void reloadPlayback() {
        if (mPlayer != null) {
            mPlaybackPresenter.onEngineReleased();
            mPlaybackPresenter.onEngineInitialized();
        }
    }

    @Override
    public void blockEngine(boolean block) {
        mEngineBlocked = block;
    }

    @Override
    public boolean isEngineBlocked() {
        return mEngineBlocked;
    }

    @Override
    public boolean isInPIPMode() {
        return isInPictureInPictureMode();
    }

    @Override
    public boolean containsMedia() {
        return mExoPlayerController.containsMedia();
    }

    @Override
    public void setSpeed(float speed) {
        mExoPlayerController.setSpeed(speed);
    }

    @Override
    public float getSpeed() {
        return mExoPlayerController.getSpeed();
    }

    @Override
    public void setPitch(float pitch) {
        mExoPlayerController.setPitch(pitch);
    }

    @Override
    public float getPitch() {
        return mExoPlayerController.getPitch();
    }

    @Override
    public void setVolume(float volume) {
        mExoPlayerController.setVolume(volume);
    }

    @Override
    public float getVolume() {
        return mExoPlayerController.getVolume();
    }

    @Override
    public void setResizeMode(int mode) {
        mPlayerView.setResizeMode(mode);
    }

    @Override
    public int getResizeMode() {
        return mPlayerView.getResizeMode();
    }

    @Override
    public void setZoomPercents(int percents) {
        // not on the phone yet
    }

    @Override
    public void setAspectRatio(float ratio) {
        // the player view keeps the video's own ratio
    }

    @Override
    public void setRotationAngle(int angle) {
        // not on the phone yet
    }

    @Override
    public void setVideoFlipEnabled(boolean enabled) {
        // not on the phone yet
    }

    @Override
    public void setVideoGravity(int gravity) {
        // not on the phone yet
    }

    ///////////////////// PlayerUI

    @Override
    public void updateSuggestions(VideoGroup group) {
        if (group == null) {
            return;
        }

        int index = getSuggestionsIndex(group);

        if (index < 0) {
            mGroups.add(group);
        } else if (group.getAction() == VideoGroup.ACTION_REPLACE) {
            mGroups.set(index, group);
        } else {
            VideoGroup existing = mGroups.get(index);
            if (existing != group) {
                for (Video video : group.getVideos()) {
                    existing.add(video);
                }
            }
        }

        rebuildSuggestions();
    }

    private List<Video> queuedSongs() {
        List<Video> after = com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist.instance().getAllAfterCurrent(); // null at the last song
        return after != null ? new ArrayList<>(after) : new ArrayList<>();
    }

    /** Songs that follow in the list the playing song came from (not in the queue). */
    private List<Video> restSongs() {
        Video current = getVideo();
        List<Video> queued = queuedSongs();
        List<Video> rest = new ArrayList<>();
        VideoGroup origin = current != null && !current.belongsToSuggestions() ? current.getGroup() : null;
        if (origin != null) {
            boolean found = false;
            for (Video item : origin.getVideos()) {
                if (found && item.hasVideo() && !item.isUpcoming && !item.isShorts && !item.isLive && !queued.contains(item)) {
                    rest.add(item);
                }
                if (item.equals(current)) {
                    found = true;
                }
            }
        }
        return rest;
    }

    private com.liskovsoft.smartyoutubetv2.mobile.ui.music.QueueView.Listener createQueueListener() {
        final com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist playlist = com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist.instance();
        return new com.liskovsoft.smartyoutubetv2.mobile.ui.music.QueueView.Listener() {
            @Override
            public void onPlay(Video video) {
                if (video.equals(getVideo())) {
                    return;
                }
                if (playlist.contains(video)) {
                    playlist.setCurrent(video);
                }
                mPlaybackPresenter.openVideo(video);
            }

            @Override
            public void onLongPress(Video video) {
                com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicMenu.show(PlaybackActivity.this, video, null, null);
            }

            @Override
            public void onReordered(List<Video> newOrder) {
                Video playing = playlist.getCurrent();
                if (playing == null) {
                    return;
                }
                List<Video> all = new ArrayList<>(playlist.getAll());
                int index = all.indexOf(playing);
                if (index < 0) {
                    return;
                }
                List<Video> result = new ArrayList<>(all.subList(0, index + 1));
                result.addAll(newOrder);
                playlist.clear();
                playlist.addAll(result);
                playlist.setCurrent(playing);
            }
        };
    }

    private MusicPlayerView createMusicView() {
        return new MusicPlayerView(this, new MusicPlayerView.Host() {
            @Override
            public Video video() {
                return getVideo();
            }

            @Override
            public boolean isPlaying() {
                return mExoPlayerController != null && mExoPlayerController.getPlayWhenReady();
            }

            @Override
            public long position() {
                return mExoPlayerController != null ? mExoPlayerController.getPositionMs() : 0;
            }

            @Override
            public long duration() {
                return mExoPlayerController != null ? mExoPlayerController.getDurationMs() : 0;
            }

            @Override
            public void togglePlay() {
                if (mExoPlayerController == null) {
                    return;
                }
                mExoPlayerController.setPlayWhenReady(!mExoPlayerController.getPlayWhenReady());
            }

            @Override
            public void previous() {
                mPlaybackPresenter.onPreviousClicked();
            }

            @Override
            public void next() {
                mPlaybackPresenter.onNextClicked();
            }

            @Override
            public void seekTo(long ms) {
                if (mExoPlayerController != null) {
                    mExoPlayerController.setPositionMs(ms);
                }
            }

            @Override
            public void setSpeed(float speed) {
                if (mExoPlayerController != null) {
                    mExoPlayerController.setSpeed(speed);
                }
            }

            @Override
            public void minimize() {
                enterMiniPlayer();
            }

            @Override
            public void showVideo() {
                setAudioMode(false);
            }

            @Override
            public void share() {
                shareVideo();
            }

            @Override
            public com.liskovsoft.smartyoutubetv2.mobile.ui.music.QueueView.Listener queueListener() {
                return createQueueListener();
            }

            @Override
            public List<Video> queued() {
                return queuedSongs();
            }

            @Override
            public List<Video> rest() {
                return restSongs();
            }

            @Override
            public long buffered() {
                Player player = MediaPlaybackService.getPlayer();
                return player != null ? player.getBufferedPosition() : 0;
            }

            @Override
            public float speed() {
                return mExoPlayerController != null ? mExoPlayerController.getSpeed() : 1f;
            }

            @Override
            public boolean hasLikes() {
                return true;
            }

            @Override
            public int likeState() {
                return getButtonState(ID_LIKE) == BUTTON_ON ? 1 : getButtonState(ID_DISLIKE) == BUTTON_ON ? -1 : 0;
            }

            @Override
            public String likeCount() {
                Video video = getVideo();
                return video != null ? video.likeCount : null;
            }

            @Override
            public void like(boolean up) {
                int id = up ? ID_LIKE : ID_DISLIKE;
                mPlaybackPresenter.onButtonClicked(id, getButtonState(id));
            }

            @Override
            public boolean shuffle() {
                return com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicMenu.isShuffle();
            }

            @Override
            public void setShuffle(boolean on) {
                com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicMenu.setShuffle(on);
            }

            @Override
            public boolean repeatOne() {
                return com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicMenu.isRepeatOne(PlaybackActivity.this);
            }

            @Override
            public void setRepeatOne(boolean on) {
                com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicMenu.setRepeatOne(PlaybackActivity.this, on);
            }

            @Override
            public String queueTitle() {
                return com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicMenu.queueTitle();
            }

            @Override
            public void startRadio() {
                Video video = getVideo();
                if (video != null) {
                    com.liskovsoft.smartyoutubetv2.mobile.ui.music.MusicMenu.startRadio(PlaybackActivity.this, video, true);
                    android.widget.Toast.makeText(PlaybackActivity.this, "Radio från " + video.getTitle(), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (mMusicView != null && mMusicView.isQueueShown()) {
            mMusicView.showQueue(false);
            return;
        }
        if (mAudioMode && !mShortsMode && mPlayer != null && !mInPip) {
            enterMiniPlayer(); // music: back minimizes to the mini player, like YouTube Music
            return;
        }
        super.onBackPressed();
    }

    private void rebuildSuggestions() {
        mSuggestionsAdapter.clear();
        mSuggestionsAdapter.addView(mHeader);

        if (mAudioMode && !mShortsMode) {
            if (mMusicView != null) {
                mMusicView.refreshQueue(); // the music screen has its own queue page
            }
            return;
        }

        for (VideoGroup group : mGroups) {
            if (mGroups.size() > 1) {
                mSuggestionsAdapter.addHeader(group.getTitle());
            }
            mSuggestionsAdapter.addVideos(group.getVideos());
        }
    }

    @Override
    public void removeSuggestions(VideoGroup group) {
        int index = getSuggestionsIndex(group);

        if (index >= 0) {
            mGroups.remove(index);
            rebuildSuggestions();
        }
    }

    @Override
    public int getSuggestionsIndex(VideoGroup group) {
        if (group == null) {
            return -1;
        }

        for (int i = 0; i < mGroups.size(); i++) {
            VideoGroup existing = mGroups.get(i);
            if (existing == group || existing.getId() == group.getId()) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public VideoGroup getSuggestionsByIndex(int index) {
        return index >= 0 && index < mGroups.size() ? mGroups.get(index) : null;
    }

    @Override
    public void focusSuggestedItem(int index) {
        // no focus on a phone
    }

    @Override
    public void focusSuggestedItem(Video video) {
        // no focus on a phone
    }

    @Override
    public void resetSuggestedPosition() {
        mSuggestionsList.scrollToPosition(0);
        mLastScrollEndVideo = null;
    }

    @Override
    public boolean isSuggestionsEmpty() {
        return mGroups.isEmpty();
    }

    @Override
    public void clearSuggestions() {
        mGroups.clear();
        mLastScrollEndVideo = null;
        rebuildSuggestions();
    }

    @Override
    public void showOverlay(boolean show) {
        showControls(show);
    }

    @Override
    public boolean isOverlayShown() {
        return mControls.getVisibility() == View.VISIBLE;
    }

    @Override
    public void showSuggestions(boolean show) {
        if (show) {
            mSuggestionsList.scrollToPosition(0);
        }
    }

    @Override
    public boolean isSuggestionsShown() {
        // the phone list is always there; the presenter must keep filling it
        return false;
    }

    @Override
    public void showControls(boolean show) {
        if (show && (mShortsMode || mInPip)) {
            return;
        }

        if (!show && mAudioMode && !mShortsMode && !mInPip) {
            return; // the music screen keeps its controls
        }

        mControls.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && !(mAudioMode && !mShortsMode)) {
            scheduleHide();
        } else {
            mHandler.removeCallbacks(mHideControls);
        }
    }

    @Override
    public boolean isControlsShown() {
        return mControls.getVisibility() == View.VISIBLE;
    }

    @Override
    public int getButtonState(int buttonId) {
        Integer state = mButtonStates.get(buttonId);
        return state != null ? state : BUTTON_OFF;
    }

    @Override
    public void setButtonState(int buttonId, int buttonState) {
        mButtonStates.put(buttonId, buttonState);
        refreshButtons();
    }

    @Override
    public void setChannelIcon(String iconUrl) {
        if (iconUrl != null && !isDestroyed()) {
            Glide.with(mAvatarView).load(iconUrl).into(mAvatarView);
            Glide.with(mShortAvatar).load(iconUrl).into(mShortAvatar);
        }
    }

    @Override
    public void setSeekPreviewTitle(String title) {
        // no seek preview title yet
    }

    @Override
    public void setNextTitle(Video nextVideo) {
        // no "next" label yet
    }

    @Override
    public void showDebugInfo(boolean show) {
        // no debug overlay on the phone
    }

    @Override
    public void showSubtitles(boolean show) {
        // CC button of the controls handles subtitles
    }

    @Override
    public void loadStoryboard() {
        // seek thumbnails: later
    }

    @Override
    public void setTitle(String title) {
        mTitleView.setText(title);
        mShortTitle.setText(title);
    }

    @Override
    public void showProgressBar(boolean show) {
        // the player view shows its own buffering spinner
    }

    @Override
    public void setSeekBarSegments(List<SeekBarSegment> segments) {
        // SponsorBlock segments on the seek bar: later
    }

    @Override
    public void updateEndingTime() {
        // no ending time clock on the phone
    }

    @Override
    public void setChatReceiver(ChatReceiver chatReceiver) {
        // live chat: later
    }
}
