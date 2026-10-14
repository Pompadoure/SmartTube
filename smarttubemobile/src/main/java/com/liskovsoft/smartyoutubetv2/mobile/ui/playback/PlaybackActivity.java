package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private LinearLayout mInfoBox;
    private TextView mTitleView;
    private TextView mMetaView;
    private ImageView mAvatarView;
    private LinearLayout mActionRow;
    private RecyclerView mSuggestionsList;
    private VideoAdapter mSuggestionsAdapter;
    private final List<VideoGroup> mGroups = new ArrayList<>();
    private final Map<Integer, Integer> mButtonStates = new HashMap<>();
    private boolean mEngineBlocked;
    private LinearLayout mRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);

        setContentView(createContent());

        mPlayerInitializer = new ExoPlayerInitializer(this);
        mPlaybackPresenter = PlaybackPresenter.instance(this);
        mPlaybackPresenter.setView(this);
        mExoPlayerController = new ExoPlayerController(this, mPlaybackPresenter);

        mPlaybackPresenter.onViewInitialized();

        applyOrientation(getResources().getConfiguration());
    }

    private View createContent() {
        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setBackgroundColor(getColor(R.color.yt_bg));

        mPlayerBox = new RatioFrameLayout(this);
        mPlayerBox.setBackgroundColor(0xFF000000);
        mPlayerView = new PlayerView(this);
        mPlayerView.setUseController(true);
        mPlayerView.setControllerShowTimeoutMs(3000);
        mPlayerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        mPlayerView.setShowNextButton(false);
        mPlayerView.setShowPreviousButton(false);
        mPlayerBox.addView(mPlayerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        back.setOnClickListener(v -> finish());
        mPlayerBox.addView(back, new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.TOP | Gravity.START));

        ImageView hq = new ImageView(this);
        hq.setImageResource(R.drawable.ic_hq);
        hq.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        // short press: resolution bubble, long press: the advanced video menu (same as the TV button)
        hq.setOnClickListener(v -> mPlaybackPresenter.onButtonClicked(com.liskovsoft.smartyoutubetv2.common.R.id.lb_control_high_quality, 0));
        hq.setOnLongClickListener(v -> {
            mPlaybackPresenter.onButtonLongClicked(com.liskovsoft.smartyoutubetv2.common.R.id.lb_control_high_quality, 0);
            return true;
        });
        mPlayerBox.addView(hq, new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.TOP | Gravity.END));

        mRoot.addView(mPlayerBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mInfoBox = new LinearLayout(this);
        mInfoBox.setOrientation(LinearLayout.VERTICAL);
        mInfoBox.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 4));

        mTitleView = new TextView(this);
        mTitleView.setTextColor(getColor(R.color.yt_text));
        mTitleView.setTextSize(17);
        mTitleView.setMaxLines(2);
        mTitleView.setEllipsize(TextUtils.TruncateAt.END);
        mInfoBox.addView(mTitleView);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        mAvatarView = new ImageView(this);
        mAvatarView.setBackgroundResource(R.drawable.bg_avatar);
        mAvatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.makeCircle(mAvatarView);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        avatarParams.setMarginEnd(Ui.dp(this, 12));
        metaRow.addView(mAvatarView, avatarParams);
        mMetaView = new TextView(this);
        mMetaView.setTextColor(getColor(R.color.yt_text_secondary));
        mMetaView.setTextSize(13);
        metaRow.addView(mMetaView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mInfoBox.addView(metaRow);

        mActionRow = new LinearLayout(this);
        mActionRow.setOrientation(LinearLayout.HORIZONTAL);
        addAction("Gilla", com.liskovsoft.smartyoutubetv2.common.R.id.action_thumbs_up);
        addAction("Ogilla", com.liskovsoft.smartyoutubetv2.common.R.id.action_thumbs_down);
        addAction("Prenumerera", com.liskovsoft.smartyoutubetv2.common.R.id.action_subscribe);
        addAction("Spara", com.liskovsoft.smartyoutubetv2.common.R.id.action_playlist_add);
        android.widget.HorizontalScrollView actionScroll = new android.widget.HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(mActionRow);
        mInfoBox.addView(actionScroll);

        mRoot.addView(mInfoBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        mSuggestionsList = new RecyclerView(this);
        GridLayoutManager manager = new GridLayoutManager(this, 1);
        mSuggestionsList.setLayoutManager(manager);
        mSuggestionsList.setAdapter(mSuggestionsAdapter);
        mSuggestionsList.setItemAnimator(null);
        mRoot.addView(mSuggestionsList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return mRoot;
    }

    private void addAction(String title, int buttonId) {
        TextView button = new TextView(this);
        button.setText(title);
        button.setTextColor(getColor(R.color.yt_text));
        button.setTextSize(13);
        button.setBackgroundResource(R.drawable.bg_chip);
        button.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        button.setOnClickListener(v -> mPlaybackPresenter.onButtonClicked(buttonId, getButtonState(buttonId)));
        button.setOnLongClickListener(v -> {
            mPlaybackPresenter.onButtonLongClicked(buttonId, getButtonState(buttonId));
            return true;
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(this, 8));
        mActionRow.addView(button, params);
    }

    private void applyOrientation(Configuration config) {
        boolean landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        View decor = getWindow().getDecorView();

        if (landscape) {
            // the video fills the screen
            mPlayerBox.setHeightPerWidth(0f);
            mPlayerBox.setFullscreen(true);
            mInfoBox.setVisibility(View.GONE);
            mSuggestionsList.setVisibility(View.GONE);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            mPlayerBox.setFullscreen(false);
            mPlayerBox.setHeightPerWidth(9f / 16f);
            mInfoBox.setVisibility(View.VISIBLE);
            mSuggestionsList.setVisibility(View.VISIBLE);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
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

        if (Util.SDK_INT > 23) {
            initializePlayer();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

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

        if (Util.SDK_INT > 23) {
            maybeReleasePlayer();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

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

        mExoPlayerController.setPlayer(mPlayer);
        mExoPlayerController.setPreloadManager(mPlayerInitializer.getPreloadManager(), mPlayerInitializer.getPreloadControl());
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

        mPlaybackPresenter.setView(this);
        mPlaybackPresenter.onEngineInitialized();
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
        mPlayerInitializer.release();
        mExoPlayerController.release();
        mPlayer = null;
    }

    private void showVideoInfo(Video video) {
        if (video == null) {
            return;
        }

        mTitleView.setText(video.getTitleFull() != null ? video.getTitleFull() : "...");

        CharSequence second = video.getSecondTitleFull();
        String author = video.getAuthor();
        mMetaView.setText(author != null && !TextUtils.isEmpty(second) ? author + " • " + second : (author != null ? author : second));
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
        super.finish();
    }

    @Override
    public void finishReally() {
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

    private void rebuildSuggestions() {
        mSuggestionsAdapter.clear();

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
    }

    @Override
    public boolean isSuggestionsEmpty() {
        return mGroups.isEmpty();
    }

    @Override
    public void clearSuggestions() {
        mGroups.clear();
        mSuggestionsAdapter.clear();
    }

    @Override
    public void showOverlay(boolean show) {
        showControls(show);
    }

    @Override
    public boolean isOverlayShown() {
        return mPlayerView.isControllerFullyVisible();
    }

    @Override
    public void showSuggestions(boolean show) {
        if (show) {
            mSuggestionsList.scrollToPosition(0);
        }
    }

    @Override
    public boolean isSuggestionsShown() {
        return mSuggestionsList.getVisibility() == View.VISIBLE;
    }

    @Override
    public void showControls(boolean show) {
        if (show) {
            mPlayerView.showController();
        } else {
            mPlayerView.hideController();
        }
    }

    @Override
    public boolean isControlsShown() {
        return mPlayerView.isControllerFullyVisible();
    }

    @Override
    public int getButtonState(int buttonId) {
        Integer state = mButtonStates.get(buttonId);
        return state != null ? state : BUTTON_OFF;
    }

    @Override
    public void setButtonState(int buttonId, int buttonState) {
        mButtonStates.put(buttonId, buttonState);
    }

    @Override
    public void setChannelIcon(String iconUrl) {
        if (iconUrl != null) {
            Glide.with(mAvatarView).load(iconUrl).into(mAvatarView);
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
        mPlayerView.setShowSubtitleButton(show);
    }

    @Override
    public void loadStoryboard() {
        // seek thumbnails: later
    }

    @Override
    public void setTitle(String title) {
        mTitleView.setText(title);
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
