package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.annotation.SuppressLint;

import com.liskovsoft.media3.sabr.parser.exceptions.SabrReloadPlayerError;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ShortsQueue;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector;
import com.liskovsoft.smartyoutubetv2.common.misc.BufferingDetector.OnLongBuffering;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;

public class ErrorFixerController extends BasePlayerController implements OnLongBuffering {
    private static final String TAG = ErrorFixerController.class.getSimpleName();
    private static final long STREAM_END_THRESHOLD_MS = 180_000;
    // Upstream (yuliskov 17b09643): how many times a SABR "reload the player response" request is honored per video
    private static final int MAX_SABR_RELOAD_ATTEMPTS = 3;
    private final BufferingDetector mBufferingDetector = new BufferingDetector(this);
    private VideoLoaderController mVideoLoaderController;
    private String mSabrReloadVideoId;
    private int mSabrReloadAttempts;

    @Override
    public void onInit() {
        mVideoLoaderController = getController(VideoLoaderController.class);
    }

    @Override
    public void onEngineError(int type, int rendererIndex, Throwable error) {
        Log.e(TAG, "Player error occurred: %s. Trying to fix…", type);

        // Upstream (e2436a44): the error is being handled, the long buffering fix mustn't run on top of it
        mBufferingDetector.reset();
        runEngineErrorAction(type, rendererIndex, error);
    }

    @Override
    public void onLongBuffering() {
        if (getPlayer() == null) {
            return;
        }

        if (isStreamEnded()) {
            getMainController().onPlayEnd();
        } else if (isOfflineVideo() && isSubtitlesEnabled()) {
            // Long loading subtitles cause hangs
            disableSubtitles();
            mVideoLoaderController.reloadVideo();
        } else if (!mBufferingDetector.isPlayable()) {
            if (getPlayerTweaksData().getPlayerDataSource() != PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP
                && getPlayerTweaksData().getPreferredDnsType() != PlayerTweaksData.DNS_TYPE_SYSTEM
                && !getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
                // Wrong DNS resolving could cause hanging at start
                // Do switch to only engine that respects custom DNS settings
                MessageHelpers.showLongMessage(getContext(), "Fixing wrong DNS resolving...");
                getPlayerTweaksData().setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP);
                mVideoLoaderController.restartEngine();
            } else {
                // Also, some clients like ANDROID_REEL may just hang at start
                MessageHelpers.showLongMessage(getContext(), "Fixing stalled client...");
                YouTubeServiceManager.instance().switchNextClientNow();
                mVideoLoaderController.reloadVideo();
            }
        } else {
            // NOTE: The bug. Avoid calling reloadVideo() after lowering the quality.
            // This will change current format to 'Disabled'. Do restartEngine() instead.
            //lowerVideoQuality();
            //mVideoLoaderController.restartEngine();

            // SABR may hang if the server issues a high backoffTime
            mVideoLoaderController.restartEngine();
        }
    }

    @Override
    public void onBuffering() {
        mBufferingDetector.onStartBuffering();
    }

    @Override
    public void onSeekEnd() {
        mBufferingDetector.reset();
        // Needed to detect additional buffering (e.g. hanged clients).
        // Don't worry this event will be canceled by subsequent onPlay() or onPause() if everything is ok.
        mBufferingDetector.onStartBuffering();
    }

    @Override
    public void onPlay() {
        mBufferingDetector.onStopBuffering();
        mSabrReloadAttempts = 0;
    }

    @Override
    public void onPause() {
        mBufferingDetector.onStopBuffering();
    }

    @Override
    public void onNewVideo(Video item) {
        mBufferingDetector.start();
    }

    @Override
    public void onFinish() {
        mBufferingDetector.reset();
        Utils.removeCallbacks(mNetRetry);
    }

    @Override
    public void onEngineReleased() {
        mBufferingDetector.reset();
    }

    private void runEngineErrorAction(int type, int rendererIndex, Throwable error) {
        // Hide begin errors in embed mode (e.g. wrong date/time: unable to connect to...)
        if (isEmbedPlayer() && getPlayer() != null && getPlayer().getPositionMs() == 0) {
            getPlayer().finish();
            return;
        }

        if (isStreamEnded()) {
            // Url no longer works (e.g. live stream ended)
            getMainController().onPlayEnd();
            return;
        }

        if (applySabrReloadPlayerAction(error)) {
            return;
        }

        applyEngineErrorAction(type, rendererIndex, error);
    }

    /**
     * Upstream (yuliskov 17b09643, Thioby): the SABR server sent ReloadPlayerResponse. It won't send any media until
     * the player response is re-fetched with its token (ignoring it looped on the init chunk forever).
     * After {@link #MAX_SABR_RELOAD_ATTEMPTS} the common error handling takes over, like before.
     */
    private boolean applySabrReloadPlayerAction(Throwable error) {
        SabrReloadPlayerError reloadError = findSabrReloadPlayerError(error);
        Video video = getVideo();

        if (reloadError == null || video == null || mVideoLoaderController == null) {
            return false;
        }

        if (!Helpers.equals(video.videoId, mSabrReloadVideoId)) {
            mSabrReloadVideoId = video.videoId;
            mSabrReloadAttempts = 0;
        }

        if (++mSabrReloadAttempts > MAX_SABR_RELOAD_ATTEMPTS) {
            return false;
        }

        Log.e(TAG, "SABR requested a player response reload. Attempt: %s", mSabrReloadAttempts);

        YouTubeServiceManager.instance().reloadPlayerResponse(video.videoId, reloadError.reloadPlaybackToken);
        // A queued Short would be reloaded from its stored format info, without the token
        ShortsQueue.invalidate(video.videoId);
        mVideoLoaderController.reloadVideo();

        return true;
    }

    private static SabrReloadPlayerError findSabrReloadPlayerError(Throwable error) {
        // Media3 wraps it: PlaybackException -> UnexpectedLoaderException -> SabrReloadPlayerError
        for (int depth = 0; error != null && depth < 10; depth++) {
            if (error instanceof SabrReloadPlayerError) {
                return (SabrReloadPlayerError) error;
            }

            error = error.getCause();
        }

        return null;
    }

    private void applyEngineErrorAction(int type, int rendererIndex, Throwable error) {
        boolean restartEngine = true;
        boolean showMessage = true;
        String errorContent = error != null ? error.getMessage() : null;
        String errorTitle = getErrorTitle(type, rendererIndex);
        String errorMessage = errorTitle + "\n" + errorContent;

        if (Helpers.startsWithAny(errorContent, "Unable to connect to")) {
            // No internet connection or WRONG DATE on the device
            // Recently this message starting to show for other unknown reasons
            if (SuggestionsController.sMusicOnly) {
                // Music in background: don't tear down the engine/service on a network blip, just retry later
                restartEngine = false;
                Utils.postDelayed(mNetRetry, 5_000);
            } else if (!getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
                switchNextEngine();
            }
        } else if (error instanceof OutOfMemoryError || (error != null && error.getCause() instanceof OutOfMemoryError)) {
            if (getPlayerTweaksData().getPlayerDataSource() == PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP) {
                // OkHttp has memory leak problems
                enableFasterDataSource();
            } else if (getPlayerData().getVideoBufferType() == PlayerData.BUFFER_HIGH || getPlayerData().getVideoBufferType() == PlayerData.BUFFER_HIGHEST) {
                getPlayerData().setVideoBufferType(PlayerData.BUFFER_MEDIUM);
            } else {
                lowerVideoQuality(); // NOTE: restart engine is required after lower the quality
            }
        } else if (Helpers.containsAny(errorContent, "Exception in CronetUrlRequest") && !getPlayerTweaksData().isNetworkErrorFixingDisabled()) {
            if (getVideo() != null && !getVideo().isLive) { // Finished live stream may provoke errors in Cronet
                getPlayerTweaksData().setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT);
            } else {
                restartEngine = false;
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_SOURCE && rendererIndex == PlayerEventListener.RENDERER_INDEX_UNKNOWN) {
            // NOTE: Starts with any (url deciphered incorrectly)
            // "Response code: 403" (poToken error, forbidden)
            // "Response code: 404" (not sure whether below helps)
            // "Response code: 503" (not sure whether below helps)
            // "Response code: 400" (not sure whether below helps)
            // "Response code: 429" (subtitle error, too many requests)
            // "Response code: 500" (subtitle error, generic server error)

            // NOTE: Fixing too many requests or network issues
            // NOTE: All these errors have unknown renderer (-1)
            // "Unable to connect to", "Invalid NAL length", "Response code: 421",
            // "Response code: 404", "Response code: 429", "Invalid integer size",
            // "Unexpected ArrayIndexOutOfBoundsException", "Unexpected IndexOutOfBoundsException"

            restartEngine = false;
            showMessage = false;

            boolean isGeneralError = Helpers.startsWithAny(errorContent, "Response code: 429", "Response code: 500");
            if (isGeneralError && isSubtitlesEnabled()) {
                disableSubtitles(); // Response code: 429
            } else if (isGeneralError && getPlayerTweaksData().isHighBitrateFormatsEnabled()) {
                getPlayerTweaksData().setHighBitrateFormatsEnabled(false); // Response code: 429
            } else if (!mBufferingDetector.isPlayable()) { // Response code: 403
                // The stream fails instantly if nParam isn't correct.
                // Note, nParam generation strictly tied to the client but some reported that OkHttp could help.
                YouTubeServiceManager.instance().switchNextClientNow();
                showMessage = true;
            } else {
                YouTubeServiceManager.instance().switchNextClient(); // Response code: 403
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_SUBTITLE) {
            // "Response code: 429" (subtitle error)
            // "Response code: 500" (subtitle error)
            disableSubtitles();
            restartEngine = false;
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_VIDEO) {
            getPlayerData().setFormat(FormatItem.VIDEO_FHD_AVC_30);
            if (getPlayerTweaksData().isSWDecoderForced()) {
                getPlayerTweaksData().setSWDecoderForced(false);
            } else {
                restartEngine = false;
            }
        } else if (type == PlayerEventListener.ERROR_TYPE_RENDERER && rendererIndex == PlayerEventListener.RENDERER_INDEX_AUDIO) {
            getPlayerData().setFormat(FormatItem.AUDIO_HQ_MP4A);
            restartEngine = false;
        } else if (type == PlayerEventListener.ERROR_TYPE_UNEXPECTED) {
            // IllegalStateException: Buffer too small (5242880 < 7208383)
            if (Helpers.startsWithAny(errorContent, "Buffer too small", "Invalid to call at Released state; only valid in executing state")) {
                // NOTE: The bug. Avoid calling reloadVideo() after lowering the quality.
                // This will change current format to 'Disabled'. Do restartEngine() instead.
                lowerVideoQuality();
            }
        }

        if (showMessage) {
            MessageHelpers.showLongMessage(getContext(), errorMessage);
            if (getPlayer() != null) {
                getPlayer().setTitle(errorContent);
            }
        }

        if (restartEngine) {
            mVideoLoaderController.restartEngine();
        } else {
            // Need at least to reload the video because the player becomes idle after error
            mVideoLoaderController.reloadVideo();
        }
    }

    @SuppressLint("StringFormatMatches")
    private String getErrorTitle(int type, int rendererIndex) {
        String errorTitle;
        int msgResId;

        switch (type) {
            // Some ciphered data could be outdated.
            // Might happen when the app wasn't used quite a long time.
            case PlayerEventListener.ERROR_TYPE_SOURCE:
                switch (rendererIndex) {
                    case PlayerEventListener.RENDERER_INDEX_VIDEO:
                        msgResId = R.string.msg_player_error_video_source;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_AUDIO:
                        msgResId = R.string.msg_player_error_audio_source;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_SUBTITLE:
                        msgResId = R.string.msg_player_error_subtitle_source;
                        break;
                    default:
                        msgResId = R.string.unknown_source_error;
                }
                errorTitle = getContext().getString(msgResId);
                break;
            case PlayerEventListener.ERROR_TYPE_RENDERER:
                switch (rendererIndex) {
                    case PlayerEventListener.RENDERER_INDEX_VIDEO:
                        msgResId = R.string.msg_player_error_video_renderer;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_AUDIO:
                        msgResId = R.string.msg_player_error_audio_renderer;
                        break;
                    case PlayerEventListener.RENDERER_INDEX_SUBTITLE:
                        msgResId = R.string.msg_player_error_subtitle_renderer;
                        break;
                    default:
                        msgResId = R.string.unknown_renderer_error;
                }
                errorTitle = getContext().getString(msgResId);
                break;
            case PlayerEventListener.ERROR_TYPE_UNEXPECTED:
                errorTitle = getContext().getString(R.string.player_unexpected_error);
                break;
            default:
                errorTitle = getContext().getString(R.string.msg_player_error, type);
                break;
        }

        return errorTitle;
    }

    private final Runnable mNetRetry = () -> { if (getPlayer() != null) mVideoLoaderController.reloadVideo(); };
    private int mFormatErrorCount;
    private long mLastFormatErrorMs;

    public void runFormatErrorAction(Throwable error) {
        if (getPlayer() == null) {
            return;
        }

        if (isEmbedPlayer()) {
            getPlayer().finish();
            return;
        }

        String message = error.getMessage();
        String className = error.getClass().getSimpleName();
        String fullMsg = String.format("loadFormatInfo error: %s: %s", className, Utils.getStackTraceAsString(error));
        Log.e(TAG, fullMsg);

        if (!Helpers.containsAny(message, "fromNullable result is null")) {
            MessageHelpers.showLongMessage(getContext(), fullMsg);
            if (getPlayer() != null) {
                getPlayer().setTitle(fullMsg);
            }
        }

        // Network hiccup while something already plays (e.g. mobile data): never restart the app, just back off and retry
        boolean hadProgress = getPlayer() != null && getPlayer().getPositionMs() > 0;
        long now = System.currentTimeMillis();
        if (now - mLastFormatErrorMs > 60_000) {
            mFormatErrorCount = 0;
        }
        mLastFormatErrorMs = now;
        mFormatErrorCount++;

        if (!hadProgress && !SuggestionsController.sMusicOnly && Utils.fixRetrofitErrors(getContext(), error)) {
            return;
        }

        if (mFormatErrorCount > 3 && Helpers.containsAny(message, "UnknownHost", "No address associated", "Unable to resolve", "timeout", "Timeout")) {
            // Offline: stop the reload loop, wait longer
            Log.e(TAG, "Network unavailable. Retrying in 15 sec");
            Utils.postDelayed(mNetRetry, 15_000);
            return;
        }

        if (Helpers.containsAny(message, "Unexpected token", "Syntax error", "invalid argument") || // temporal fix
                Helpers.equalsAny(className, "PoTokenException", "BadWebViewException")) {
            YouTubeServiceManager.instance().switchNextClient();
            mVideoLoaderController.reloadVideo();
        } else if (Helpers.containsAny(message, "is not defined")) {
            YouTubeServiceManager.instance().invalidateCache();
            mVideoLoaderController.reloadVideo();
        } else {
            Log.e(TAG, "Probably no internet connection");
            mVideoLoaderController.reloadVideo();
        }
    }

    /**
     * Bad idea. Faster source is different among devices
     */
    private void enableFasterDataSource() {
        if (isFasterDataSourceEnabled()) {
            return;
        }

        getPlayerTweaksData().setPlayerDataSource(Utils.getFasterDataSource());
    }

    /**
     * Bad idea. Faster source is different among devices
     */
    private boolean isFasterDataSourceEnabled() {
        int fasterDataSource = Utils.getFasterDataSource();
        return getPlayerTweaksData().getPlayerDataSource() == fasterDataSource;
    }

    private void switchNextEngine() {
        getPlayerTweaksData().setPlayerDataSource(getNextEngine());
    }

    private int getNextEngine() {
        int currentEngine = getPlayerTweaksData().getPlayerDataSource();
        Integer[] engineList = Utils.skipCronet() ?
                new Integer[] { PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP } :
                new Integer[] { PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET, PlayerTweaksData.PLAYER_DATA_SOURCE_DEFAULT, PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP };
        return Helpers.getNextValue(engineList, currentEngine);
    }

    private boolean isSubtitlesEnabled() {
        return getPlayer() != null && !FormatItem.SUBTITLE_NONE.equals(getPlayer().getSubtitleFormat());
    }

    private void disableSubtitles() {
        getPlayerData().setSubtitlesPerChannelEnabled(false); // Important!
        getPlayerData().setFormat(FormatItem.SUBTITLE_NONE);
    }

    private boolean isStreamEnded() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return getVideo().isLiveEnd && getPlayer().getDurationMs() > 0
                && getPlayer().getDurationMs() - getPlayer().getPositionMs() < STREAM_END_THRESHOLD_MS;
    }

    private boolean isOfflineVideo() {
        if (getPlayer() == null || getVideo() == null) {
            return false;
        }

        return !getVideo().isLive && !getVideo().isLiveEnd;
    }

    /**
     * NOTE: The bug. Avoid calling reloadVideo() after lowering the quality.<br/>
     * This will change current format to 'Disabled'. Do reloadEngine() instead.
     */
    private void lowerVideoQuality() {
        if (getPlayer() == null) {
            return;
        }

        List<FormatItem> videoFormats = getPlayer().getVideoFormats();

        if (videoFormats == null) {
            return;
        }

        FormatItem videoFormat = getPlayer().getVideoFormat();

        // Limit by 720p
        if (Math.max(videoFormat.getWidth(), videoFormat.getHeight()) <= 1280) {
            return;
        }

        int idx = videoFormats.indexOf(videoFormat);
        int nextIdx = idx + 1;

        if (videoFormats.size() > nextIdx) {
            FormatItem formatItem = videoFormats.get(nextIdx);
            getPlayer().setFormat(formatItem);
            // This helps to persist the format between engine restart
            getPlayerData().setTempVideoFormat(formatItem);
        }
    }
}
