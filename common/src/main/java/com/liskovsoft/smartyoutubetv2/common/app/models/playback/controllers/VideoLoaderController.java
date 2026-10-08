package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.Build.VERSION;

import com.bumptech.glide.Glide;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ExoPlayerController;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ShortsQueue;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.disposables.Disposable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VideoLoaderController extends BasePlayerController {
    private static final String TAG = VideoLoaderController.class.getSimpleName();
    private static final int MIN_SHUFFLE_SIZE = 30;
    private static final int PREFETCH_DELAY_MS = 300; // right after the current Short is loaded
    private static final int PREFETCH_MAX_RETRIES = 10;
    private static final int PREFETCH_CACHE_SIZE = 8;
    private static final int PREFETCH_LOOKAHEAD = 4; // Shorts preloaded ahead of the current one
    private final Playlist mPlaylist;
    private Video mPendingVideo;
    private SuggestionsController mSuggestionsController;
    private ErrorFixerController mErrorFixerController;
    private Disposable mFormatInfoAction;
    private Disposable mPrefetchAction;
    private String mPrefetchingVideoId; // video of the running prefetch request
    private String mWaitingForPrefetchId; // the user opened the video that is being prefetched right now
    // Format info of upcoming Shorts, fetched in the background while the current one plays
    private final Map<String, MediaItemFormatInfo> mPrefetchedFormats = new LinkedHashMap<String, MediaItemFormatInfo>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, MediaItemFormatInfo> eldest) {
            return size() > PREFETCH_CACHE_SIZE;
        }
    };
    private final Runnable mPrefetchNext = this::prefetchNext;
    private int mPrefetchRetries;
    private final Runnable mReloadVideo = () -> {
        getMainController().onNewVideo(getVideo());
    };
    private final Runnable mLoadNext = this::loadNext;
    private final Runnable mMetadataSync = () -> {
        if (getPlayer() != null) {
            waitMetadataSync(getVideo(), false);
        }
    };
    private final Runnable mRestartEngine = () -> {
        if (getPlayer() != null) {
            getPlayer().restartEngine(); // properly save position of the current track
        }
    };
    private final Runnable mOnApplyPlaybackMode = () -> {
        if (getPlayer() != null && getPlayer().getPositionMs() >= getPlayer().getDurationMs()) {
            applyPlaybackMode(getPlaybackMode());
        }
    };
    private final Runnable mShowProgressBar = () -> {
        if (getPlayer() != null) {
            getPlayer().showProgressBar(true);
        }
    };

    public VideoLoaderController() {
        mPlaylist = Playlist.instance();
    }

    @Override
    public void onInit() {
        mSuggestionsController = getController(SuggestionsController.class);
        mErrorFixerController = getController(ErrorFixerController.class);
    }

    @Override
    public void onNewVideo(Video item) {
        if (item == null) {
            return;
        }

        item.isShuffled = false;

        if (!item.fromQueue && !item.belongsToPlaybackQueue()) {
            mPlaylist.add(item);
        } else {
            item.fromQueue = false;
        }

        if (getPlayer() != null && getPlayer().isEngineInitialized()) { // player is initialized
            // Fix improperly resized video after exit from PIP (Device Formuler Z8 Pro)
            loadVideo(item); // force play immediately even the same video
        } else {
            mPendingVideo = item;
        }
    }

    @Override
    public void onEngineInitialized() {
        if (getPlayer() == null) {
            return;
        }
        
        loadVideo(Helpers.firstNonNull(mPendingVideo, getVideo()));
        getPlayer().setButtonState(R.id.action_repeat, getPlayerData().getPlaybackMode());
        mPendingVideo = null;
    }

    @Override
    public void onEngineReleased() {
        disposeActions();
        disposePrefetch();
        mPrefetchedFormats.clear();
    }

    @Override
    public void onVideoLoaded(Video video) {
        if (getPlayer() == null) {
            return;
        }

        getPlayer().setButtonState(R.id.action_repeat, video.finishOnEnded ? PlayerConstants.PLAYBACK_MODE_CLOSE : getPlayerData().getPlaybackMode());
        // Can't set title at this point
        //checkSleepTimer();

        schedulePrefetch();
    }

    @Override
    public boolean onPreviousClicked() {
        loadPrevious();

        return true;
    }

    @Override
    public boolean onNextClicked() {
        if (getGeneralData().isChildModeEnabled()) {
            onPlayEnd();
        } else {
            loadNext();
        }

        return true;
    }

    public void loadPrevious() {
        if (getPlayer() == null) {
            return;
        }

        ShortsTransitionState.setDirection(ShortsTransitionState.DIRECTION_PREVIOUS);
        openVideoInt(mSuggestionsController.getPrevious());

        if (getPlayerTweaksData().isPlayerUiOnNextEnabled()) {
            getPlayer().showOverlay(true);
        }
    }

    public void loadNext() {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        Video next = mSuggestionsController.getNext();

        // Shorts: stay in the Shorts feed. The list is known up front, so don't wait for the suggestions
        // ("Please wait while data is loading") and never fall through to the suggested regular video.
        if (getVideo().isShorts) {
            List<Video> ahead = getShortsLookahead(getVideo());
            Video feedNext = ahead.isEmpty() ? null : ahead.get(0);

            if (feedNext != null) {
                next = feedNext;
            } else if (next != null && !next.isShorts) {
                next = null;
            }
        }

        ShortsTransitionState.setDirection(ShortsTransitionState.DIRECTION_NEXT);

        if (next != null) {
            openVideoInt(next);
        } else if (getVideo().isShorts) {
            // End of the loaded part of the feed: the continuation is being loaded
            MessageHelpers.showMessage(getContext(), R.string.wait_data_loading);
        } else {
            waitMetadataSync(getVideo(), true);
        }

        if (getPlayerTweaksData().isPlayerUiOnNextEnabled()) {
            getPlayer().showOverlay(true);
        }
    }

    @Override
    public void onPlayEnd() {
        if (getPlayer() == null) {
            return;
        }

        // Stop the playback if the user is browsing options or reading comments
        int playbackMode = getPlaybackMode();
        if (getAppDialogPresenter().isDialogShown() && !getAppDialogPresenter().isOverlay() && playbackMode != PlayerConstants.PLAYBACK_MODE_ONE) {
            getAppDialogPresenter().setOnFinish(mOnApplyPlaybackMode);
        } else {
            applyPlaybackMode(playbackMode);
        }
    }

    @Override
    public void onSuggestionItemClicked(Video item) {
        openVideoInt(item);

        if (getPlayer() != null)
            getPlayer().showControls(false);
    }

    @Override
    public boolean onKeyDown(int keyCode) {
        Utils.removeCallbacks(mRestartEngine);

        return false;
    }

    /**
     * Force load and play!
     */
    private void loadVideo(Video item) {
        if (getPlayer() != null && item != null) {
            ShortsTransitionState.setShortsMode(item.isShorts && !item.isLive);
            ShortsTransitionState.setLive(item.isLive);
            mPlaylist.setCurrent(item);
            getPlayer().setVideo(item);
            getPlayer().resetPlayerState();
            loadFormatInfo(item);
        }
    }

    /**
     * Force load suggestions.
     */
    private void loadSuggestions(Video item) {
        if (getPlayer() == null) {
            return;
        }

        if (item != null) {
            mPlaylist.setCurrent(item);
            getPlayer().setVideo(item);
            mSuggestionsController.loadSuggestions(item);
        }
    }

    private void waitMetadataSync(Video current, boolean showLoadingMsg) {
        if (current == null) {
            return;
        }

        if (current.nextMediaItem != null) {
            openVideoInt(Video.from(current.nextMediaItem));
        } else if (!current.isSynced) { // Maybe there's nothing left. E.g. when casting from phone
            // Wait in a loop while suggestions have been loaded...
            if (showLoadingMsg) {
                MessageHelpers.showMessage(getContext(), R.string.wait_data_loading);
            }
            // Short videos next fix (suggestions aren't loaded yet)
            boolean isEnded = getPlayer() != null && Math.abs(getPlayer().getDurationMs() - getPlayer().getPositionMs()) < 100;
            if (isEnded) {
                Utils.postDelayed(mMetadataSync, 1_000);
            }
        }
    }

    private void loadFormatInfo(Video video) {
        if (getPlayer() == null) {
            return;
        }

        // Fix no progress on next video (the engine may still buffering a bit)
        //getPlayer().showProgressBar(true);
        Utils.post(mShowProgressBar);
        disposeActions();

        mWaitingForPrefetchId = null;

        // Use the format info fetched in the background (Shorts feed), or the one of a Short that is
        // already in the player (e.g. the previous one). Skips the network round trip.
        MediaItemFormatInfo cached = takePrefetched(video);
        final MediaItemFormatInfo prefetched = cached != null ? cached : ShortsQueue.getQueuedFormatInfo(video.videoId);
        if (prefetched != null) {
            Log.d(TAG, "Using prefetched format info for %s", video.videoId);
            // Keep the original async order (the player state is reset at this point)
            Utils.post(() -> {
                Video current = getVideo();
                if (current != null && Helpers.equals(current.videoId, prefetched.getVideoId())) {
                    processFormatInfo(prefetched);
                }
            });
            return;
        }

        if (video.videoId != null && video.videoId.equals(mPrefetchingVideoId) && RxHelper.isAnyActionRunning(mPrefetchAction)) {
            // Scrolled faster than the prefetch: this video is being fetched right now. Don't start over, wait for it.
            Log.d(TAG, "Waiting for the running prefetch of %s", video.videoId);
            mWaitingForPrefetchId = video.videoId;
            return;
        }

        // Don't let a background prefetch compete with the video the user is waiting for.
        // The prefetch is scheduled again once this video is loaded.
        disposePrefetch();

        ServiceManager service = YouTubeServiceManager.instance();
        MediaItemService mediaItemManager = service.getMediaItemService();
        mFormatInfoAction = mediaItemManager.getFormatInfoObserve(video.videoId)
                .subscribe(this::processFormatInfo,
                           error -> {
                               getPlayer().showProgressBar(false);
                               mErrorFixerController.runFormatErrorAction(error);
                           });
    }

    private void processFormatInfo(MediaItemFormatInfo formatInfo) {
        PlaybackView player = getPlayer();

        if (player == null || getVideo() == null) {
            return;
        }

        String bgImageUrl = null;

        getVideo().sync(formatInfo);
        ShortsTransitionState.setLive(getVideo().isLive || formatInfo.isLive());

        // Fix stretched video for a couple milliseconds (before the onVideoSizeChanged gets called)
        applyAspectRatio(formatInfo);

        if (formatInfo.getPaidContentText() != null && getSponsorBlockData().isPaidContentNotificationEnabled()) {
            MessageHelpers.showMessage(getContext(), formatInfo.getPaidContentText());
        }

        if (formatInfo.isUnplayable()) {
            if (isEmbedPlayer()) {
                player.finish();
                return;
            }

            player.setTitle(formatInfo.getPlayabilityReason());
            player.showProgressBar(false);
            mSuggestionsController.loadSuggestions(getVideo());
            bgImageUrl = getVideo().getBackgroundUrl();

            // 18+ video or the video is hidden/removed
            player.showOverlay(true);
            loadNextVideo(5_000);

            //if (formatInfo.isUnknownError()) { // the bot error or the video not available
            //    scheduleRebootAppTimer(5_000);
            //} else { // 18+ video or the video is hidden/removed
            //    scheduleNextVideoTimer(5_000);
            //}
        } else if (acceptAdaptiveFormats(formatInfo) && formatInfo.containsDashFormats()) {
            Log.d(TAG, "Loading regular video in dash format...");

            if (getPlayerTweaksData().isHighBitrateFormatsEnabled() && formatInfo.hasExtendedHlsFormats()) {
                player.openMerged(formatInfo, formatInfo.getHlsManifestUrl());
            } else {
                player.openDash(formatInfo);
            }
        } else if (acceptAdaptiveFormats(formatInfo) && formatInfo.containsSabrFormats() && !formatInfo.isLive()) { // TODO: SABR live not implemented yet
            Log.d(TAG, "Loading video in sabr format...");
            player.openSabr(formatInfo);
        } else if (acceptDashLive(formatInfo)) {
            Log.d(TAG, "Loading live video (current or past live stream) in dash format...");
            player.openDashUrl(formatInfo.getDashManifestUrl());
        } else if (formatInfo.isLive() && formatInfo.containsHlsUrl()) {
            Log.d(TAG, "Loading live video (current or past live stream) in hls format...");
            player.openHlsUrl(formatInfo.getHlsManifestUrl());
        } else if (formatInfo.containsUrlFormats()) {
            Log.d(TAG, "Loading url list video. This is always LQ...");
            player.openUrlList(formatInfo.createUrlList());
        } else {
            Log.d(TAG, "Empty format info received. Seems future live translation. No video data to pass to the player.");
            player.setTitle(formatInfo.getPlayabilityReason());
            player.showProgressBar(false);
            mSuggestionsController.loadSuggestions(getVideo());
            bgImageUrl = getVideo().getBackgroundUrl();
            player.showOverlay(true);
            reloadVideo(30 * 1_000);
        }

        player.showBackground(bgImageUrl); // remove bg (if video playing) or set another bg
    }

    private void reloadVideo(int delayMs) {
        if (getPlayer() == null) {
            return;
        }

        if (getPlayer().isEngineInitialized()) {
            Log.d(TAG, "Reloading the video...");
            Utils.postDelayed(mReloadVideo, delayMs);
        }
    }

    private void loadNextVideo(int delayMs) {
        if (getPlayer() == null) {
            return;
        }

        if (getPlayer().isEngineInitialized()) {
            Log.d(TAG, "Starting the next video...");
            Utils.postDelayed(mLoadNext, delayMs);
        }
    }

    private void restartEngine(int delayMs) {
        if (getPlayer() != null) {
            Log.d(TAG, "Restarting the engine...");
            Utils.postDelayed(mRestartEngine, delayMs);
        }
    }

    private void openVideoInt(Video item) {
        if (item == null) {
            return;
        }

        disposeActions();

        if (item.hasVideo()) {
            // NOTE: Next clicked: instant playback even a mix
            // NOTE: Bypass PIP fullscreen on next caused by startView
            getMainController().onNewVideo(item);
            //getPlayer().showOverlay(true);
        } else {
            VideoActionPresenter.instance(getContext()).apply(item);
        }
    }

    private boolean isActionsRunning() {
        return RxHelper.isAnyActionRunning(mFormatInfoAction);
    }

    private void disposeActions() {
        MediaServiceManager.instance().disposeActions();
        RxHelper.disposeActions(mFormatInfoAction);
        Utils.removeCallbacks(mReloadVideo, mLoadNext, mRestartEngine, mMetadataSync);
    }

    public void restartEngine() {
        restartEngine(1_000);
    }

    public void reloadVideo() {
        reloadVideo(1_000);
    }

    private void applyPlaybackMode(int playbackMode) {
        if (getPlayer() == null) {
            return;
        }

        Video video = getVideo();
        // Fix simultaneous videos loading (e.g. when playback ends and user opens new video)
        if (video == null || isActionsRunning()) {
            return;
        }

        if (isEmbedPlayer()) {
            playbackMode = PlayerConstants.PLAYBACK_MODE_CLOSE;
        }

        switch (playbackMode) {
            case PlayerConstants.PLAYBACK_MODE_REVERSE_LIST:
                if (video.hasPlaylist() || video.belongsToChannelUploads() || video.belongsToChannel()) {
                    VideoGroup group = video.getGroup();
                    if (group != null && group.indexOf(video) != 0) { // stop after first
                        onPreviousClicked();
                    }
                    break;
                }
            case PlayerConstants.PLAYBACK_MODE_ALL:
            case PlayerConstants.PLAYBACK_MODE_SHUFFLE:
                loadNext();
                break;
            case PlayerConstants.PLAYBACK_MODE_ONE:
                if (VERSION.SDK_INT <= 19) {
                    // Fix frozen image on Android 4
                    restartEngine();
                } else {
                    getPlayer().setPositionMs(0);
                    getPlayer().setPlayWhenReady(true); // Shorts queue pauses at the end of an item
                }
                break;
            case PlayerConstants.PLAYBACK_MODE_CLOSE:
                // Close player if suggestions not shown
                // Except when playing from queue
                if (mPlaylist.getNext() != null && !getPlayerTweaksData().isQueueRespectsPlaybackMode()) {
                    loadNext();
                } else {
                    AppDialogPresenter dialog = getAppDialogPresenter();
                    if (!getPlayer().isSuggestionsShown() && (!dialog.isDialogShown() || dialog.isOverlay())) {
                        dialog.closeDialog();
                        getPlayer().finishReally();
                    }
                }
                break;
            case PlayerConstants.PLAYBACK_MODE_PAUSE:
                // Stop player after each video.
                // Except when playing from queue
                if (mPlaylist.getNext() != null && !getPlayerTweaksData().isQueueRespectsPlaybackMode()) {
                    loadNext();
                } else {
                    stopPlayback();
                }
                break;
            case PlayerConstants.PLAYBACK_MODE_LIST:
                // if video has a playlist load next or restart playlist
                if (video.hasNextPlaylist() || mPlaylist.getNext() != null) {
                    loadNext();
                } else {
                    //restartPlaylistIfNeeded();
                    stopPlayback();
                }
                break;
            default:
                Log.e(TAG, "Undetected repeat mode " + playbackMode);
                break;
        }
    }

    private void stopPlayback() {
        if (getPlayer() == null) {
            return;
        }

        getPlayer().setPositionMs(getPlayer().getDurationMs());
        getPlayer().setPlayWhenReady(false);
        getPlayer().showSuggestions(true);
    }

    private void restartPlaylistIfNeeded() {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }
        
        VideoGroup group = getVideo().getGroup(); // Get the VideoGroup (playlist)

        if (group != null && !group.isEmpty() && getVideo().belongsToSamePlaylistGroup()) {
            openVideoInt(group.get(0));
        } else {
            Log.e(TAG, "VideoGroup is null or empty. Can't restart playlist.");
            stopPlayback();
        }
    }

    private boolean acceptAdaptiveFormats(MediaItemFormatInfo formatInfo) {
        if (getPlayerData().isLegacyCodecsForced() && formatInfo.containsUrlFormats()) {
            return false;
        }

        if (getPlayerTweaksData().isHlsStreamsForced() && formatInfo.isLive() && formatInfo.containsHlsUrl()) {
            return false;
        }

        // Not enough info for full length live streams
        if (formatInfo.isLive() && formatInfo.getStartTimeMs() == 0) {
            return false;
        }

        // Live dash url doesn't work with None buffer
        //if (formatInfo.isLive() && (getPlayerTweaksData().isDashUrlStreamsForced() || getPlayerData().getVideoBufferType() == PlayerData.BUFFER_NONE)) {
        if (formatInfo.isLive() && getPlayerTweaksData().isDashUrlStreamsForced() && formatInfo.containsDashUrl()) {
            return false;
        }

        if (formatInfo.isLive() && getPlayerTweaksData().isHlsStreamsForced() && formatInfo.containsHlsUrl()) {
            return false;
        }

        return true;
    }

    private boolean acceptDashLive(MediaItemFormatInfo formatInfo) {
        if (getPlayerTweaksData().isHlsStreamsForced() && formatInfo.isLive() && formatInfo.containsHlsUrl()) {
            return false;
        }

        return formatInfo.isLive() && formatInfo.containsDashUrl();
    }

    @Override
    public void onMetadata(MediaItemMetadata metadata) {
        initRandomNext();
        schedulePrefetch();
    }

    /**
     * Shorts feed: fetch the next video's format info (incl. stream urls) while the current one plays,
     * so switching with up/down doesn't wait for the network.
     */
    private void schedulePrefetch() {
        Utils.removeCallbacks(mPrefetchNext);
        mPrefetchRetries = 0;

        Video current = getVideo();

        if (!isPrefetchAllowed(current)) {
            return;
        }

        Utils.postDelayed(mPrefetchNext, PREFETCH_DELAY_MS);
    }

    /**
     * Start prefetching once the current Short's own data is loaded (don't compete with it).
     */
    private boolean isCurrentLoaded() {
        return getPlayer() != null && !isActionsRunning();
    }

    private boolean isPrefetchAllowed(Video current) {
        return !isEmbedPlayer() && getPlayer() != null && current != null && !current.isLive && current.isShorts;
    }

    /**
     * Next Shorts straight from the feed list the user is in (known up front), or the suggested next video.
     */
    private List<Video> getShortsLookahead(Video current) {
        List<Video> result = new ArrayList<>();

        if (current == null) {
            return result;
        }

        VideoGroup group = current.getGroup();

        if (group != null && !group.isEmpty()) {
            boolean found = false;

            for (Video item : group.getVideos()) {
                if (found && item.isShorts && item.hasVideo() && !item.isUpcoming && !item.isLive && !Helpers.equals(item.videoId, current.videoId)) {
                    result.add(item);

                    if (result.size() >= PREFETCH_LOOKAHEAD) {
                        break;
                    }
                }

                if (item.equals(current)) {
                    found = true;
                }
            }
        }

        if (result.isEmpty()) {
            Video next = mSuggestionsController.getNext();

            if (next != null && next.isShorts && next.hasVideo() && !next.isLive && !Helpers.equals(next.videoId, current.videoId)) {
                result.add(next);
            }
        }

        return result;
    }

    /**
     * Prefetches the next Shorts one by one (in order) and appends them to the player queue,
     * so ExoPlayer can buffer them in the background.
     */
    private void prefetchNext() {
        Video current = getVideo();

        if (!isPrefetchAllowed(current) || RxHelper.isAnyActionRunning(mPrefetchAction)) {
            return;
        }

        if (!isCurrentLoaded()) {
            if (++mPrefetchRetries <= PREFETCH_MAX_RETRIES) {
                Utils.postDelayed(mPrefetchNext, PREFETCH_DELAY_MS);
            }
            return;
        }

        List<Video> ahead = getShortsLookahead(current);

        for (int i = 0; i < ahead.size(); i++) {
            Video next = ahead.get(i);
            int distance = i + 1;

            MediaItemFormatInfo cached = mPrefetchedFormats.get(next.videoId);
            if (cached != null && cached.isCacheActual()) {
                ExoPlayerController.enqueueShort(cached, distance); // no-op if already queued
                continue;
            }

            String nextVideoId = next.videoId;
            Log.d(TAG, "Prefetching format info for %s (+%s)", nextVideoId, distance);

            // Warm up the image used by the transition animation
            try {
                Glide.with(getContext()).load(ShortsTransitionState.getThumbnailUrl(nextVideoId)).preload();
            } catch (IllegalArgumentException e) {
                // Activity destroyed
            }

            mPrefetchingVideoId = nextVideoId;
            mPrefetchAction = YouTubeServiceManager.instance().getMediaItemService().getFormatInfoObserve(nextVideoId)
                    .subscribe(formatInfo -> {
                        mPrefetchingVideoId = null;

                        if (isWaitingForPrefetch(nextVideoId)) {
                            // The user is already on this video
                            mWaitingForPrefetchId = null;
                            processFormatInfo(formatInfo);
                            return;
                        }

                        if (formatInfo != null && !formatInfo.isUnplayable() && !formatInfo.isLive()) {
                            mPrefetchedFormats.put(nextVideoId, formatInfo);

                            // Real preload: the player buffers it in the background
                            Video now = getVideo();
                            if (now != null && Helpers.equals(now.videoId, current.videoId)) {
                                ExoPlayerController.enqueueShort(formatInfo, distance);
                            }
                        }

                        // Continue with the next one in the chain
                        Utils.post(mPrefetchNext);
                    }, error -> {
                        Log.e(TAG, "Prefetch failed for %s: %s", nextVideoId, error.getMessage());
                        onPrefetchFinished(nextVideoId);
                    }, () -> onPrefetchFinished(nextVideoId));

            return; // one request at a time
        }
    }

    private boolean isWaitingForPrefetch(String videoId) {
        Video now = getVideo();
        return videoId != null && videoId.equals(mWaitingForPrefetchId) && now != null && videoId.equals(now.videoId);
    }

    /**
     * The prefetch request ended without a result (error or empty).
     */
    private void onPrefetchFinished(String videoId) {
        if (videoId == null || !videoId.equals(mPrefetchingVideoId) && !isWaitingForPrefetch(videoId)) {
            return; // already handled in onNext
        }

        mPrefetchingVideoId = null;

        if (isWaitingForPrefetch(videoId)) {
            // The user is on this video: load it the regular way (with the regular error handling)
            mWaitingForPrefetchId = null;
            loadFormatInfo(getVideo());
        }
    }

    private MediaItemFormatInfo takePrefetched(Video video) {
        if (video == null || video.videoId == null) {
            return null;
        }

        MediaItemFormatInfo formatInfo = mPrefetchedFormats.remove(video.videoId);

        if (formatInfo == null || !formatInfo.isCacheActual() || !Helpers.equals(formatInfo.getVideoId(), video.videoId)) {
            return null;
        }

        return formatInfo;
    }

    private void disposePrefetch() {
        Utils.removeCallbacks(mPrefetchNext);
        RxHelper.disposeActions(mPrefetchAction);
        mPrefetchingVideoId = null;
        mWaitingForPrefetchId = null;
    }

    private void initRandomNext() {
        MediaServiceManager.instance().disposeActions();

        PlaybackView player = getPlayer();
        PlayerData playerData = getPlayerData();
        Video current = getVideo();

        if (player == null || playerData == null || current == null || current.playlistInfo == null ||
                playerData.getPlaybackMode() != PlayerConstants.PLAYBACK_MODE_SHUFFLE) {
            return;
        }

        // NOTE: Shuffle only user created playlists (size != -1)
        if (current.playlistInfo.getSize() > MIN_SHUFFLE_SIZE) {
            Video video = new Video();
            video.playlistId = current.playlistId;
            video.playlistIndex = Utils.getRandomIndex(current.playlistInfo.getCurrentIndex(), current.playlistInfo.getSize());
            MediaServiceManager.instance().loadMetadata(video, randomMetadata -> {
                if (randomMetadata.getNextVideo() == null) {
                    return;
                }

                current.nextMediaItem = SimpleMediaItem.from(randomMetadata);
                current.isShuffled = true;
                player.setNextTitle(Video.from(current.nextMediaItem));
            });
        }
        //else {
        //    VideoGroup topRow = player.getSuggestionsByIndex(0); // the playlist row
        //
        //    if (topRow != null && topRow.isChapters()) {
        //        topRow = player.getSuggestionsByIndex(1);
        //    }
        //
        //    if (topRow != null) {
        //        int currentIdx = topRow.indexOf(current);
        //        int randomIndex = Utils.getRandomIndex(currentIdx, topRow.getSize());
        //
        //        if (randomIndex != -1) {
        //            Video nextVideo = topRow.get(randomIndex);
        //            current.nextMediaItem = SimpleMediaItem.from(nextVideo);
        //            current.isShuffled = true;
        //            player.setNextTitle(nextVideo);
        //        }
        //    }
        //}
    }

    private int getPlaybackMode() {
        int playbackMode = getPlayerData().getPlaybackMode();

        Video video = getVideo();
        if (video != null && video.finishOnEnded) {
            playbackMode = PlayerConstants.PLAYBACK_MODE_CLOSE;
        } else if (video != null && video.belongsToShortsGroup() && getPlayerTweaksData().isLoopShortsEnabled()) {
            playbackMode = PlayerConstants.PLAYBACK_MODE_ONE;
        }
        return playbackMode;
    }

    /**
     * Fix stretched video for a couple milliseconds (before the onVideoSizeChanged gets called)
     */
    private void applyAspectRatio(MediaItemFormatInfo formatInfo) {
        if (getPlayer() == null) {
            return;
        }

        // Fix stretched video for a couple milliseconds (before the onVideoSizeChanged gets called)
        if (formatInfo.containsDashFormats()) {
            MediaFormat format = formatInfo.getAdaptiveFormats().get(0);
            int width = format.getWidth();
            int height = format.getHeight();
            boolean isShorts = width < height;
            if (width > 0 && height > 0 && (getPlayerData().getAspectRatio() == PlayerData.ASPECT_RATIO_DEFAULT || isShorts)) {
                getPlayer().setAspectRatio((float) width / height);
            } else {
                getPlayer().setAspectRatio(getPlayerData().getAspectRatio());
            }
        }
    }

    private void preloadNextVideoIfNeeded() {
        if (isEmbedPlayer() || getPlayer() == null || getVideo() == null || getVideo().isLive) {
            return;
        }

        if (getPlayer().getDurationMs() - getPlayer().getPositionMs() < 50_000) {
            MediaServiceManager.instance().loadFormatInfo(mSuggestionsController.getNext(), formatInfo -> {});
        }
    }
}
