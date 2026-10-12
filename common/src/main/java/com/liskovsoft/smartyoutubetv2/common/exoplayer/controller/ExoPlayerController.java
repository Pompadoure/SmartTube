package com.liskovsoft.smartyoutubetv2.common.exoplayer.controller;

import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import com.liskovsoft.smartyoutubetv2.common.utils.JoTubeTiming;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.RestoreTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelectionArray;
import androidx.media3.exoplayer.trackselection.TrackSelection;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.errors.TrackErrorFixer;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.VolumeBooster;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackInfoFormatter2;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.VideoTrack;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.ExoUtils;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;

public class ExoPlayerController implements Player.Listener {
    private static final String TAG = ExoPlayerController.class.getSimpleName();
    private final Context mContext;
    private final ExoMediaSourceFactory mMediaSourceFactory;
    private final TrackSelectorManager mTrackSelectorManager;
    private final TrackInfoFormatter2 mTrackFormatter;
    private final TrackErrorFixer mTrackErrorFixer;
    private boolean mOnSourceChanged;
    private WeakReference<Video> mVideo;
    private final PlayerEventListener mEventListener;
    private ExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private VolumeBooster mVolumeBooster;
    private boolean mIsEnded;
    private Runnable mOnVideoLoaded;
    private static WeakReference<ExoPlayerController> sCurrent;
    // BEGIN SmartTube J: Shorts queue
    private static final int SWITCH_NOT_QUEUED = 0;
    private static final int SWITCH_DONE = 2;
    private static final long ITEM_END_MARGIN_MS = 300;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private PlayerMessage mItemEndMessage;
    private int mItemEndIndex = -1;
    private DefaultPreloadManager mPreloadManager; // JoTube M3: preloads the queued Shorts
    private ShortsPreloadControl mPreloadControl;
    // END SmartTube J

    public ExoPlayerController(Context context, PlayerEventListener eventListener) {
        PlayerTweaksData playerTweaksData = PlayerTweaksData.instance(context);
        mContext = context.getApplicationContext();
        mMediaSourceFactory = new ExoMediaSourceFactory(context);
        mTrackSelectorManager = new TrackSelectorManager(context);
        mTrackFormatter = new TrackInfoFormatter2();
        mTrackFormatter.enableBitrate(PlayerTweaksData.instance(context).isQualityInfoBitrateEnabled());
        mTrackErrorFixer = new TrackErrorFixer(mTrackSelectorManager);

        mMediaSourceFactory.setTrackErrorFixer(mTrackErrorFixer);
        mEventListener = eventListener;
        
        applyShield720pFix();
        VideoTrack.sIsAltPresetsEnabled = playerTweaksData.isAltPresetsEnabled();
        MediaTrack.setAvcOverVp9Preferred(playerTweaksData.isAvcOverVp9Preferred());
    }

    private void applyShield720pFix() {
        PlayerData playerData = PlayerData.instance(mContext);
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_VIDEO)));
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_AUDIO)));
        mTrackSelectorManager.selectTrack(FormatItem.toMediaTrack(playerData.getFormat(FormatItem.TYPE_SUBTITLE)));
    }

    public void openSabr(MediaItemFormatInfo formatInfo) {
        if (openQueued(formatInfo)) {
            return;
        }

        MediaSource mediaSource = mMediaSourceFactory.fromSabrFormatInfo(formatInfo);
        openShortsAware(formatInfo, mediaSource);
    }

    public void openDash(MediaItemFormatInfo formatInfo) {
        if (openQueued(formatInfo)) {
            return;
        }

        MediaSource mediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        openShortsAware(formatInfo, mediaSource);
    }

    // BEGIN SmartTube J: Shorts preloading

    /**
     * JoTube M3: the preload manager the player was built with (null: no Shorts preloading, e.g. the embed player).
     */
    public void setPreloadManager(DefaultPreloadManager preloadManager, ShortsPreloadControl preloadControl) {
        mPreloadManager = preloadManager;
        mPreloadControl = preloadControl;
    }

    /**
     * Shorts are opened as the first item of a queue, so the next ones can be added and preloaded.
     */
    private void openShortsAware(MediaItemFormatInfo formatInfo, MediaSource mediaSource) {
        ShortsQueue.setActive(null); // releases the previous queue (and what it preloaded) before the new one is built
        ShortsQueue queue = null;

        if (ShortsTransitionState.isShortsMode() && formatInfo != null && formatInfo.getVideoId() != null && mPreloadManager != null) {
            queue = new ShortsQueue(mPreloadManager, mPreloadControl, formatInfo, mediaSource);
            mediaSource = queue.getMediaSource(0);
        }

        openMediaSource(mediaSource);
        ShortsQueue.setActive(queue);
        scheduleItemEnd();
    }

    /**
     * @return true if the video is played (or about to be played) from the queue
     */
    private boolean openQueued(MediaItemFormatInfo formatInfo) {
        return switchToQueued(formatInfo) == SWITCH_DONE;
    }

    /**
     * The video is already queued (preloaded next or the previous one): play its source.
     */
    private int switchToQueued(MediaItemFormatInfo formatInfo) {
        ShortsQueue queue = ShortsQueue.getActive();

        if (queue == null || mPlayer == null || formatInfo == null || !ShortsTransitionState.isShortsMode()) {
            return SWITCH_NOT_QUEUED;
        }

        int index = queue.indexOf(formatInfo.getVideoId());

        if (index == -1 || index == queue.getCurrentIndex() || !queue.isActual(index)) {
            return SWITCH_NOT_QUEUED;
        }

        MediaSource source = queue.getMediaSource(index);

        if (source == null) {
            return SWITCH_NOT_QUEUED;
        }

        Log.d(TAG, "Shorts: switching to queued item %s (index %s)", formatInfo.getVideoId(), index);

        setQualityInfo("");
        // The track selection cache is synced with the new item in onTracksChanged (no invalidate here:
        // the playback thread selects tracks for the preloaded items at the same time)
        mTrackSelectorManager.onQueuedSourceSwitch();
        mOnSourceChanged = true;
        mEventListener.onSourceChanged(getVideo());
        sSourceGeneration++;
        cancelItemEnd();
        JoTubeTiming.mark("queued Short: setMediaSource (preloaded)");
        mPlayer.setMediaSource(source);
        mPlayer.prepare();
        mPlayer.setPlayWhenReady(true);
        queue.setCurrentIndex(index); // after the player has the source: the manager re-ranks the others

        return SWITCH_DONE;
    }

    /**
     * Add the next Short to the queue. The preload manager starts loading it in the background.
     */
    public static boolean enqueueShort(MediaItemFormatInfo formatInfo) {
        return enqueueShort(formatInfo, 1);
    }

    /**
     * @param distance position relative to the current Short (1 = next)
     */
    public static boolean enqueueShort(MediaItemFormatInfo formatInfo, int distance) {
        ExoPlayerController controller = sCurrent != null ? sCurrent.get() : null;
        return controller != null && controller.enqueueShortInt(formatInfo, distance);
    }

    private boolean enqueueShortInt(MediaItemFormatInfo formatInfo, int distance) {
        ShortsQueue queue = ShortsQueue.getActive();

        if (queue == null || mPlayer == null || formatInfo == null || formatInfo.getVideoId() == null || !ShortsTransitionState.isShortsMode()) {
            return false;
        }

        if (queue.contains(formatInfo.getVideoId())) {
            return true;
        }

        if (queue.isFull()) {
            return false;
        }

        MediaSource mediaSource;

        if (formatInfo.containsDashFormats()) {
            mediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        } else if (formatInfo.containsSabrFormats()) {
            mediaSource = mMediaSourceFactory.fromSabrFormatInfo(formatInfo);
        } else {
            return false;
        }

        if (!queue.insertAt(distance, formatInfo, mediaSource)) {
            return false;
        }

        Log.d(TAG, "Shorts: preloading %s (+%s)", formatInfo.getVideoId(), distance);

        return true;
    }

    /**
     * Fires shortly before the current Short ends. The app decides what's next, exactly like at the end of a regular
     * video (loop, next, pause...). Not removed after delivery, so it fires again after a loop.
     */
    private void scheduleItemEnd() {
        ShortsQueue queue = ShortsQueue.getActive();

        if (queue == null || mPlayer == null) {
            cancelItemEnd();
            return;
        }

        int index = queue.getCurrentIndex();

        if (mItemEndMessage != null && mItemEndIndex == index) {
            return; // already scheduled
        }

        cancelItemEnd();

        Timeline timeline = mPlayer.getCurrentTimeline();

        if (timeline.isEmpty()) {
            return; // scheduled on the next timeline change
        }

        // JoTube M3: the player plays one Short at a time: it is always window 0
        long durationMs = timeline.getWindow(0, new Timeline.Window()).getDurationMs();

        if (durationMs == C.TIME_UNSET || durationMs <= ITEM_END_MARGIN_MS * 2) {
            return; // duration isn't known yet: scheduled on the next timeline change
        }

        mItemEndIndex = index;
        mItemEndMessage = mPlayer.createMessage((messageType, payload) -> onItemEnding(index))
                .setPosition(0, durationMs - ITEM_END_MARGIN_MS)
                .setLooper(mHandler.getLooper())
                .setDeleteAfterDelivery(false)
                .send();
    }

    private void cancelItemEnd() {
        if (mItemEndMessage != null) {
            mItemEndMessage.cancel();
            mItemEndMessage = null;
        }

        mItemEndIndex = -1;
    }

    private void onItemEnding(int index) {
        ShortsQueue queue = ShortsQueue.getActive();

        if (queue == null || mPlayer == null || queue.getCurrentIndex() != index ||
                !ShortsQueue.isPlayerOn(queue.getVideoId(index), mPlayer.getCurrentMediaItem()) ||
                !mPlayer.getPlayWhenReady() || isLoadingOtherVideo(queue)) {
            return;
        }

        Log.d(TAG, "Shorts: item %s is ending", index);

        mIsEnded = true; // don't handle the end twice (if the player reaches the ENDED state after all)
        mEventListener.onPlayEnd();
    }

    /**
     * Another video is being opened (e.g. its format info is loading), the end of the old one doesn't matter.
     */
    private boolean isLoadingOtherVideo(ShortsQueue queue) {
        Video video = getVideo();
        return video != null && !Helpers.equals(video.videoId, queue.getCurrentVideoId());
    }

    // END SmartTube J

    public void openDash(InputStream dashManifest) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashManifest(dashManifest);
        openMediaSource(mediaSource);
    }

    public void openDashUrl(String dashManifestUrl) {
        MediaSource mediaSource = mMediaSourceFactory.fromDashManifestUrl(dashManifestUrl);
        openMediaSource(mediaSource);
    }

    public void openHlsUrl(String hlsPlaylistUrl) {
        MediaSource mediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(mediaSource);
    }

    public void openUrlList(List<String> urlList) {
        MediaSource mediaSource = mMediaSourceFactory.fromUrlList(urlList);
        openMediaSource(mediaSource);
    }

    public void openMerged(MediaItemFormatInfo formatInfo, String hlsPlaylistUrl) {
        MediaSource dashMediaSource = mMediaSourceFactory.fromDashFormatInfo(formatInfo);
        MediaSource hlsMediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(new MergingMediaSource(dashMediaSource, hlsMediaSource));
    }

    public void openMerged(InputStream dashManifest, String hlsPlaylistUrl) {
        MediaSource dashMediaSource = mMediaSourceFactory.fromDashManifest(dashManifest);
        MediaSource hlsMediaSource = mMediaSourceFactory.fromHlsPlaylist(hlsPlaylistUrl);
        openMediaSource(new MergingMediaSource(dashMediaSource, hlsMediaSource));
    }

    // JoTube: changes with every new video in the player (new source or a queued Short), so a first-frame event
    // that was already on its way for the previous video can be told apart
    private static volatile int sSourceGeneration;

    public static int getSourceGeneration() {
        return sSourceGeneration;
    }

    private void openMediaSource(MediaSource mediaSource) {
        sSourceGeneration++;
        applyTunneling(); // JoTube
        resetPlayerState(); // fixes occasional video artifacts and problems with quality switching
        setQualityInfo("");

        mTrackSelectorManager.setMergedSource(mediaSource instanceof MergingMediaSource);
        mTrackSelectorManager.invalidate();
        mOnSourceChanged = true;
        mEventListener.onSourceChanged(getVideo());
        mPlayer.prepare(mediaSource);
    }

    public long getPositionMs() {
        if (mPlayer == null) {
            return -1;
        }

        return mPlayer.getCurrentPosition();
    }

    /**
     * NOTE: Pos gathered from content block data may slightly exceed video duration
     * (e.g. 302200 when duration is 302000).
     */
    public void setPositionMs(long positionMs) {
        // Url list videos at load stage has undefined (-1) length. So, we need to remove length check.
        // JoTube M3: Media3 keeps a seek made before the timeline is known (pending initial seek position).
        long duration = getDurationMs();
        if (mPlayer != null && positionMs >= 0 && (duration < 0 || positionMs <= duration)) {
            mPlayer.seekTo(positionMs);
        }
    }

    public long getDurationMs() {
        if (mPlayer == null) {
            return -1;
        }

        long duration = mPlayer.getDuration();
        return duration != C.TIME_UNSET ? duration : -1;
    }

    public void setPlayWhenReady(boolean play) {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(play);
        }
    }

    public boolean getPlayWhenReady() {
        if (mPlayer == null) {
            return false;
        }

        return mPlayer.getPlayWhenReady();
    }

    public boolean isPlaying() {
        return ExoUtils.isPlaying(mPlayer);
    }
    
    public boolean isLoading() {
        return ExoUtils.isLoading(mPlayer);
    }

    public boolean isBuffering() {
        return ExoUtils.isBuffering(mPlayer);
    }
    
    public boolean containsMedia() {
        if (mPlayer == null) {
            return false;
        }

        return mPlayer.getPlaybackState() != Player.STATE_IDLE;
    }
    
    public void release() {
        ShortsQueue.setActive(null);
        cancelItemEnd();
        mTrackSelectorManager.release();
        mMediaSourceFactory.release();
        releasePlayer();
        mPlayerView = null;
        // Don't destroy it (needed inside the bridge)!
        //mEventListener = null;
    }
    
    public void setPlayer(ExoPlayer player) {
        mPlayer = player;
        player.addListener(this);
        sCurrent = new WeakReference<>(this);
    }

    //@Override
    //public void setEventListener(PlayerEventListener eventListener) {
    //    mEventListener = eventListener;
    //}
    
    public void setPlayerView(PlayerView playerView) {
        mPlayerView = playerView;
    }
    
    public void setPreloadTrackSelector(RestoreTrackSelector selector) {
        mTrackSelectorManager.setPreloadTrackSelector(selector);
    }

    public void setTrackSelector(DefaultTrackSelector trackSelector) {
        mTrackSelectorManager.setTrackSelector(trackSelector);
        mTunnelingTrackSelector = null;

        // JoTube: tunneled playback is off for every video (the setting is ignored). In tunnel mode each start
        // waited ~0.5 s for the A/V sync on the Streamer, and the video frame can't be captured for transitions.
        // M3: Media3 switches tunneling with Parameters.setTunnelingEnabled (no audio session id any more).
        if (TUNNELING_ALLOWED && mContext != null && trackSelector != null && PlayerTweaksData.instance(mContext).isTunneledPlaybackEnabled()) {
            mTunnelingTrackSelector = trackSelector;
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(true));
        }
    }

    private static final boolean TUNNELING_ALLOWED = false;
    private DefaultTrackSelector mTunnelingTrackSelector; // tunneling is enabled in the settings

    /**
     * JoTube: no tunneled playback for Shorts. In tunnel mode every Short switch re-creates the audio track and
     * waits for the A/V sync (~0.5-0.9 s on the Streamer) before the first frame shows. Regular videos keep it.
     */
    private void applyTunneling() {
        DefaultTrackSelector trackSelector = mTunnelingTrackSelector;

        if (trackSelector == null) {
            return;
        }

        boolean tunneling = !ShortsTransitionState.isShortsMode();

        if (trackSelector.getParameters().tunnelingEnabled != tunneling) {
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(tunneling));
        }
    }
    
    public void setVideo(Video video) {
        mVideo = new WeakReference<>(video);
    }
    
    public Video getVideo() {
        return mVideo != null ? mVideo.get() : null;
    }
    
    public List<FormatItem> getVideoFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getVideoTracks());
    }
    
    public List<FormatItem> getAudioFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getAudioTracks());
    }
    
    public List<FormatItem> getSubtitleFormats() {
        return ExoFormatItem.from(mTrackSelectorManager.getSubtitleTracks());
    }
    
    public void selectFormat(FormatItem formatItem) {
        if (formatItem != null && formatItem.getTrack() != null) {
            FormatItem selectedFormatItem = getSelectedFormat(formatItem.getTrack().rendererIndex);
            if (!formatItem.equals(selectedFormatItem)) {
                mTrackSelectorManager.selectTrack(formatItem.getTrack());
                mEventListener.onTrackSelected(formatItem);
            }
        }
    }

    public FormatItem getVideoFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_VIDEO);
    }

    public FormatItem getAudioFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_AUDIO);
    }

    public FormatItem getSubtitleFormat() {
        return getSelectedFormat(TrackSelectorManager.RENDERER_INDEX_SUBTITLE);
    }

    private FormatItem getSelectedFormat(int rendererIndex) {
        return ExoFormatItem.from(mTrackSelectorManager.getSelectedTrack(rendererIndex));
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onTracksChanged(Tracks tracks) {
        // M3: the 2.10 callback got the renderer track groups and selections; ExoPlayer still exposes both
        // (deprecated getters), which keeps the per-renderer logic below unchanged.
        TrackGroupArray trackGroups = mPlayer != null ? mPlayer.getCurrentTrackGroups() : TrackGroupArray.EMPTY;
        TrackSelectionArray trackSelections = mPlayer != null ? mPlayer.getCurrentTrackSelections() : new TrackSelectionArray();
        Log.d(TAG, "onTracksChanged: start: groups length: " + trackGroups.length);

        if (trackGroups.length == 0) {
            Log.i(TAG, "onTracksChanged: Hmm. Strange. Received empty groups, no selections. Why is this happens only on next/prev videos?");
            return;
        }

        if (ShortsQueue.getActive() != null) {
            // Shorts queue: the playing item may have changed, make the track lists (quality menu etc.) describe it
            mTrackSelectorManager.syncWithPlayingPeriod();
        }

        notifyOnVideoLoad();

        for (TrackSelection selection : trackSelections.getAll()) {
            if (selection != null) {
                // EXO: 2.12.1
                //Format format = selection.getSelectedFormat();

                // EXO: 2.13.1
                Format format = selection.getFormat(0);

                mEventListener.onTrackChanged(ExoFormatItem.from(format));

                mTrackFormatter.setFormat(format);
            }
        }
        
        setQualityInfo(mTrackFormatter.getQualityLabel());

        // Manage audio focus. E.g. use Spotify when audio is disabled. (NOT NEEDED!!!)
        //MediaTrack audioTrack = mTrackSelectorManager.getAudioTrack();
        //ExoPlayerInitializer.enableAudioFocus(mPlayer, audioTrack != null && !audioTrack.isEmpty());
    }

    private void notifyOnVideoLoad() {
        if (mOnSourceChanged) {
            mOnSourceChanged = false;

            mEventListener.onVideoLoaded(getVideo());

            if (mOnVideoLoaded != null) {
                mOnVideoLoaded.run();
            }

            // Produce thread sync problems
            // Attempt to read from field 'java.util.TreeMap$Node java.util.TreeMap$Node.left' on a null object reference
            //mTrackSelectorManager.fixTracksSelection();
        }
    }

    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
        // Shorts queue: the duration of the current item becomes known
        if (ShortsQueue.getActive() != null) {
            scheduleItemEnd();
        }
    }

    @Override
    public void onPlayerError(PlaybackException playbackError) {
        Log.e(TAG, "onPlayerError: " + playbackError);

        // NOTE: Player is released at this point. So, there is no sense to restore the playback here.

        // JoTube M3: a live stream fell behind its window: jump to the live edge, not a client/CDN problem
        if (playbackError.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && mPlayer != null) {
            mPlayer.seekToDefaultPosition();
            mPlayer.prepare();
            return;
        }

        Throwable nested = playbackError.getCause() != null ? playbackError.getCause() : playbackError;

        // M3: Media3 no longer says "Unable to connect to"; map the network errors to the old message for ErrorFixerController
        if (isConnectError(playbackError)) {
            nested = new IOException("Unable to connect to: " + nested.getMessage(), nested);
        }

        // M3: ExoPlayer reports ExoPlaybackException; an OutOfMemoryError is TYPE_UNEXPECTED now (2.10 had its own type)
        int type = ExoPlaybackException.TYPE_UNEXPECTED;
        int rendererIndex = C.INDEX_UNSET;
        if (playbackError instanceof ExoPlaybackException) {
            ExoPlaybackException error = (ExoPlaybackException) playbackError;
            type = error.type;
            rendererIndex = error.rendererIndex;
        }
        if (nested instanceof OutOfMemoryError) {
            type = PlayerEventListener.ERROR_TYPE_OUT_OF_MEMORY;
        }
        // JoTube M3: the fork reported transient MediaCodec IllegalStateExceptions as TYPE_UNEXPECTED (FORK_CHANGES D);
        // Media3 wraps them as ERROR_CODE_DECODING_FAILED / TYPE_RENDERER, which would lower the saved preset.
        if (type == ExoPlaybackException.TYPE_RENDERER && playbackError.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) {
            Throwable root = nested;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            if (root instanceof IllegalStateException && !(root instanceof android.media.MediaCodec.CodecException)) {
                type = ExoPlaybackException.TYPE_UNEXPECTED;
                nested = root;
            }
        }

        mEventListener.onEngineError(type, rendererIndex, nested);
    }

    /**
     * 2.10 said "Unable to connect to" only when opening a connection failed with an IOException.
     * Not for a bad HTTP status or content type (also TYPE_OPEN in Media3: 403 etc. keep their own handling),
     * not for read errors and not for Cronet errors (ErrorFixerController has its own Cronet branch).
     */
    private static boolean isConnectError(PlaybackException error) {
        boolean connectError = false;

        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("Exception in CronetUrlRequest")) {
                return false;
            }

            if (cause instanceof HttpDataSourceException
                    && !(cause instanceof HttpDataSource.InvalidResponseCodeException)
                    && !(cause instanceof HttpDataSource.InvalidContentTypeException)) {
                HttpDataSourceException httpError = (HttpDataSourceException) cause;
                if (httpError.type == HttpDataSourceException.TYPE_OPEN &&
                        (httpError.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                         httpError.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)) {
                    connectError = true;
                }
            }
        }

        return connectError;
    }

    @SuppressWarnings("deprecation") // M3: still called by ExoPlayer (one callback for both values, like 2.10)
    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onPlayerStateChanged: " + TrackSelectorUtil.stateToString(playbackState));
        }

        boolean isPlayPressed = Player.STATE_READY == playbackState && playWhenReady;
        boolean isPausePressed = Player.STATE_READY == playbackState && !playWhenReady;
        boolean isPlaybackEnded = Player.STATE_ENDED == playbackState && playWhenReady;
        boolean isBuffering = Player.STATE_BUFFERING == playbackState && playWhenReady;

        // Fix chapters (seek and play) after playback ends
        if (isPlaybackEnded && mIsEnded) {
            return;
        }

        if (isPlayPressed) {
            mEventListener.onPlay();
        } else if (isPausePressed) {
            mEventListener.onPause();
        } else if (isPlaybackEnded) {
            mEventListener.onPlayEnd();
            mIsEnded = true;
        } else if (isBuffering) {
            mEventListener.onBuffering();
        }

        if (getPositionMs() < getDurationMs()) {
            mIsEnded = false;
        }
    }

    @Override
    public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        Log.d(TAG, "onPositionDiscontinuity");

        // M3: onSeekProcessed is gone; the seek discontinuity is the closest event (reported when the seek starts)
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            mEventListener.onSeekEnd();
            return;
        }

        // Fix video loop on 480p with legacy codes enabled
        // M3: DISCONTINUITY_REASON_PERIOD_TRANSITION became DISCONTINUITY_REASON_AUTO_TRANSITION
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            mPlayer.stop();
            mEventListener.onPlayEnd();
        }
    }

    public float getSpeed() {
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().speed;
        } else {
            return -1;
        }
    }

    public void setSpeed(float speed) {
        if (mPlayer != null && speed > 0) {
            if (PlayerTweaksData.instance(mContext).isAudioTimeStretchingEnabled()) {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, mPlayer.getPlaybackParameters().pitch));
            } else {
                mPlayer.setPlaybackParameters(new PlaybackParameters(speed, speed));
            }

            mTrackFormatter.setSpeed(speed);
            setQualityInfo(mTrackFormatter.getQualityLabel());
            mEventListener.onSpeedChanged(speed);
        }
    }

    public float getPitch() {
        if (mPlayer != null) {
            return mPlayer.getPlaybackParameters().pitch;
        } else {
            return -1;
        }
    }

    public void setPitch(float pitch) {
        if (mPlayer != null && pitch > 0) {
            mPlayer.setPlaybackParameters(new PlaybackParameters(mPlayer.getPlaybackParameters().speed, pitch));
        }
    }

    public void setVolume(float volume) {
        if (mPlayer != null && volume >= 0) {
            mPlayer.setVolume(Math.min(volume, 1f));

            //applyVolumeBoost(volume);
        }
    }
    
    public float getVolume() {
        if (mPlayer != null) {
            return mPlayer.getVolume();
        } else {
            return 1;
        }
    }

    /**
     * Fixes video artifacts when switching to the next video.<br/>
     * Also could help with memory leaks(??)<br/>
     * Without this also you'll have problems with track quality switching(??).
     */
    public void resetPlayerState() {
        ShortsQueue.setActive(null);
        cancelItemEnd();

        if (containsMedia()) {
            mPlayer.stop(); // M3: stop(true) = stop() + clearMediaItems()
            mPlayer.clearMediaItems();
        }
    }
    
    public void setOnVideoLoaded(Runnable onVideoLoaded) {
        mOnVideoLoaded = onVideoLoaded;
    }

    private void setQualityInfo(String qualityInfoStr) {
        if (mPlayerView != null && qualityInfoStr != null) {
            mPlayerView.setQualityInfo(qualityInfoStr);
        }
    }

    private void applyVolumeBoost(float volume) {
        if (mPlayer == null) {
            return;
        }

        if (mVolumeBooster != null) {
            mPlayer.removeListener(mVolumeBooster);
            mVolumeBooster = null;
        }

        // 5.1 audio cannot be boosted (format isn't supported error)
        // also, other 2.0 tracks in 5.1 group is already too loud. so cancel them too.
        if (volume > 1f && !contains51Audio() && Build.VERSION.SDK_INT >= 19) {
            mVolumeBooster = new VolumeBooster(true, volume, null);
            mPlayer.addListener(mVolumeBooster);
        }
    }
    
    private boolean contains51Audio() {
        if (mTrackSelectorManager == null || mTrackSelectorManager.getAudioTracks() == null) {
            return false;
        }

        for (MediaTrack track : mTrackSelectorManager.getAudioTracks()) {
            if (TrackSelectorUtil.is51Audio(track.format)) {
                return true;
            }
        }

        return false;
    }

    private void releasePlayer() {
        if (mPlayer == null) {
            return;
        }

        try {
            mPlayer.removeListener(this);
            mPlayer.stop(); // Cause input lags due to high cpu load?
            mPlayer.clearMediaItems();
            mPlayer.clearVideoSurface();
            mPlayer.release();
        } catch (ArrayIndexOutOfBoundsException e) { // thrown on stop()
            e.printStackTrace();
        } finally {
            mPlayer = null;
        }
    }
}
