package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.os.Build;
import androidx.media3.common.C;
import androidx.media3.exoplayer.DefaultLoadControl;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.AudioAttributes;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.controller.ShortsPreloadControl;
import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

public class ExoPlayerInitializer {
    private final int mMaxBufferBytes;
    private final PlayerData mPlayerData;
    private final PlayerTweaksData mPlayerTweaksData;
    private VolumeBooster mVolumeBooster;
    private float mVolumeBoost;
    private ExoPlayer mPlayer;
    private DefaultPreloadManager mPreloadManager;
    private final ShortsPreloadControl mPreloadControl = new ShortsPreloadControl();
    private static AudioAttributes sAudioAttributes;
    // JoTube role swap: the load control of every player built here (the spare one loads only a few seconds)
    private final java.util.Map<ExoPlayer, FastStartLoadControl> mLoadControls = new java.util.HashMap<>();
    private ExoPlayer mSparePlayer;
    private VolumeBooster mSpareVolumeBooster;

    public ExoPlayerInitializer(Context context) {
        mPlayerData = PlayerData.instance(context);
        mPlayerTweaksData = PlayerTweaksData.instance(context);

        long deviceRam = DeviceHelpers.getDeviceRam(context);

        // If ram is too big, bigger then max int value DeviceRam will return a negative number...
        // use 196MB as that can only happens if device has more than 17GB of RAM, so 196 is enough and safe
        // https://github.com/yuliskov/SmartYouTubeTV/issues/532
        mMaxBufferBytes = deviceRam <= 0 ? 196_000_000 : (int)(deviceRam / 18);
    }

    public ExoPlayer createPlayer(Context context, DefaultRenderersFactory renderersFactory, DefaultTrackSelector trackSelector) {
        return createPlayer(context, renderersFactory, trackSelector, null);
    }

    /**
     * @param preloadSelectorFactory not null: the player is built together with a {@link DefaultPreloadManager} (Shorts
     *                               preloading). It creates the track selector of the preload manager: it must select
     *                               like {@code trackSelector}, else a preloaded Short is selected again when it plays.
     */
    public ExoPlayer createPlayer(Context context, DefaultRenderersFactory renderersFactory, DefaultTrackSelector trackSelector,
                                  TrackSelector.Factory preloadSelectorFactory) {
        DefaultLoadControl loadControl = createLoadControl();

        // HDR fix?
        //trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingAudioSessionId(C.generateAudioSessionIdV21(context)));

        // Old initializer
        // SmartTube J: faster first frame for Shorts
        // JoTube M3: ExoPlayer.Builder replaces ExoPlayerFactory.newSimpleInstance (no DRM manager, as before: null)
        ExoPlayer player;
        mPreloadManager = null;

        if (preloadSelectorFactory != null) {
            // JoTube M3: the player and the preload manager share the playback thread, renderers factory, load
            // control and bandwidth meter (DefaultPreloadManager.Builder.buildExoPlayer). The factory is called for the
            // player first, then for the preload manager.
            final boolean[] playerSelectorGiven = {false};
            FastStartLoadControl preloadLoadControl = new FastStartLoadControl(loadControl, mMaxBufferBytes);
            DefaultPreloadManager.Builder builder = new DefaultPreloadManager.Builder(context, new DefaultPreloadManager.SimpleRankingDataComparator() {
                        @Override
                        public int compare(Integer key1, Integer key2) {
                            return Integer.compare(mPreloadControl.getPriority(key1), mPreloadControl.getPriority(key2));
                        }
                    }, mPreloadControl)
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(preloadLoadControl)
                    .setBandwidthMeter(ExoMediaSourceFactory.getBandwidthMeter(context))
                    .setTrackSelectorFactory(ctx -> {
                        if (!playerSelectorGiven[0]) {
                            playerSelectorGiven[0] = true;
                            return trackSelector;
                        }
                        return preloadSelectorFactory.createTrackSelector(ctx);
                    });
            player = builder.buildExoPlayer(new ExoPlayer.Builder(context).setLivePlaybackSpeedControl(createFixedLiveSpeedControl()));
            mPreloadManager = builder.build();
            mLoadControls.put(player, preloadLoadControl);
        } else {
            FastStartLoadControl playerLoadControl = new FastStartLoadControl(loadControl, mMaxBufferBytes);
            player = new ExoPlayer.Builder(context, renderersFactory)
                    .setTrackSelector(trackSelector)
                    .setLoadControl(playerLoadControl)
                    .setBandwidthMeter(ExoMediaSourceFactory.getBandwidthMeter(context))
                    .setLivePlaybackSpeedControl(createFixedLiveSpeedControl())
                    .build();
            mLoadControls.put(player, playerLoadControl);
        }

        // JoTube M3: 2.10 never asked the display for a frame rate; the app's own AFR (UhdHelper) does it. Without this
        // Media3 would also call Surface.setFrameRate on API 30+ (double mode switch, even with AFR switched off).
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF);

        // New initializer
        //ExoPlayer player = ExoPlayerFactory.newSimpleInstance(
        //        context, renderersFactory, trackSelector, loadControl,
        //        null, new DummyBandwidthMeter(), new AnalyticsCollector.Factory(), Util.getLooper()
        //);

        //enableAudioFocus(player);

        // Lead to numbered errors
        //player.setRepeatMode(Player.REPEAT_MODE_ONE);

        // Fix still image while audio is playing (happens after format change or exit from sleep)
        //player.setPlayWhenReady(true);

        applyPlaybackFixes(player);

        setupAudioFocus(player);

        setupVolumeBoost(player);

        mPlayer = player;

        return player;
    }

    /**
     * JoTube role swap (Shorts): a second player built like the main one (renderers, load control, fixes, volume boost),
     * on a playback thread of its own (a shared thread would stall one player while the other starts a decoder). It
     * prepares the next Short; on a swipe it keeps playing it and becomes the main player (the other one the spare).
     * It doesn't take the audio focus until it becomes the main player ({@link #moveAudioFocus}).
     */
    public ExoPlayer createSparePlayer(Context context, DefaultRenderersFactory renderersFactory, DefaultTrackSelector trackSelector) {
        FastStartLoadControl loadControl = new FastStartLoadControl(createLoadControl(), mMaxBufferBytes);
        loadControl.setSpare(true);
        ExoPlayer player = new ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setBandwidthMeter(ExoMediaSourceFactory.getBandwidthMeter(context))
                .setLivePlaybackSpeedControl(createFixedLiveSpeedControl())
                .build();
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF);
        applyPlaybackFixes(player);
        if (mPlayerTweaksData.isAudioFocusEnabled()) {
            try {
                player.setAudioAttributes(getAudioAttributes(), false);
            } catch (SecurityException e) {
                e.printStackTrace();
            }
        }
        float volume = mPlayerTweaksData.isPlayerAutoVolumeEnabled() ? mPlayerData.getPlayerVolume() * 2.0f : mPlayerData.getPlayerVolume();
        if (volume > 1f && Build.VERSION.SDK_INT >= 19) {
            mSpareVolumeBooster = new VolumeBooster(true, volume, player);
            player.addListener(mSpareVolumeBooster);
        }
        mLoadControls.put(player, loadControl);
        mSparePlayer = player;
        return player;
    }

    /** JoTube role swap: the spare player loads only a few seconds of the next Short. */
    public void setSpare(ExoPlayer player, boolean spare) {
        FastStartLoadControl loadControl = player != null ? mLoadControls.get(player) : null;
        if (loadControl != null) {
            loadControl.setSpare(spare);
        }
    }

    /** JoTube role swap: the new main player takes the audio focus (if the app handles it), the old one lets it go. */
    public void moveAudioFocus(ExoPlayer from, ExoPlayer to) {
        if (!mPlayerTweaksData.isAudioFocusEnabled()) {
            return;
        }
        try {
            if (from != null) {
                from.setAudioAttributes(getAudioAttributes(), false);
            }
            if (to != null) {
                to.setAudioAttributes(getAudioAttributes(), true);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    /**
     * JoTube M3: Media3 adjusts the speed of live streams (0.97-1.03x) by itself; 2.10 never did.
     * min = max = 1 makes the control a no-op.
     */
    private static androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl createFixedLiveSpeedControl() {
        return new androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl.Builder()
                .setFallbackMinPlaybackSpeed(1f)
                .setFallbackMaxPlaybackSpeed(1f)
                .build();
    }

    /**
     * Null if the player was built without preloading (embed player).
     */
    public DefaultPreloadManager getPreloadManager() {
        return mPreloadManager;
    }

    public ShortsPreloadControl getPreloadControl() {
        return mPreloadControl;
    }

    private static AudioAttributes getAudioAttributes() {
        if (sAudioAttributes == null) {
            sAudioAttributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build();
        }

        return sAudioAttributes;
    }

    /**
     * Increase player's min/max buffer size to 60 secs
     * @return load control
     */
    private DefaultLoadControl createLoadControl() {
        DefaultLoadControl.Builder baseBuilder = new DefaultLoadControl.Builder();

        // Default values
        //DefaultLoadControl.DEFAULT_MIN_BUFFER_MS // 15_000
        //DefaultLoadControl.DEFAULT_MAX_BUFFER_MS // 50_000
        //DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS // 2_500
        //DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS // 5_000

        // Default values
        int minBufferMs = 30_000;
        int maxBufferMs = 30_000;
        int bufferForPlaybackMs = 2_500;
        int bufferForPlaybackAfterRebufferMs = 5_000;

        switch (mPlayerData.getVideoBufferType()) {
            case PlayerData.BUFFER_HIGHEST:
                minBufferMs = 50_000;
                maxBufferMs = 100_000;
                // Infinite buffer works awfully on live streams. Constant stuttering.
                //maxBufferMs = 36_000_000; // technical infinity, recommended here a very high number, the max will be based on setTargetBufferBytes() value
                baseBuilder
                        .setTargetBufferBytes(mMaxBufferBytes);
                baseBuilder.setBackBuffer(minBufferMs, true);
                break;
            case PlayerData.BUFFER_HIGH:
                minBufferMs = 50_000;
                maxBufferMs = 50_000;
                baseBuilder.setBackBuffer(minBufferMs, true);
                break;
            case PlayerData.BUFFER_MEDIUM:
                //minBufferMs = 30_000;
                //maxBufferMs = 30_000;
                break;
            case PlayerData.BUFFER_LOW:
                minBufferMs = 5_000; // LIVE fix
                maxBufferMs = 5_000; // LIVE fix
                //bufferForPlaybackMs = 1_000;
                //bufferForPlaybackAfterRebufferMs = 1_000;
                break;
        }

        baseBuilder
                .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs);

        // Decrease buffer size?
        //baseBuilder.setAllocator(new DefaultAllocator(true, 16 * 1024));

        return baseBuilder.build();
    }

    private void setupVolumeBoost(ExoPlayer player) {
        // 5.1 audio cannot be boosted (format isn't supported error)
        // also, other 2.0 tracks in 5.1 group is already too loud. so cancel them too.
        float volume = mPlayerTweaksData.isPlayerAutoVolumeEnabled() ? mPlayerData.getPlayerVolume() * 2.0f : mPlayerData.getPlayerVolume();
        if (volume > 1f && Build.VERSION.SDK_INT >= 19) {
            mVolumeBooster = new VolumeBooster(true, volume, player);
            player.addListener(mVolumeBooster); // M3: VolumeBooster is a Player.Listener (onAudioSessionIdChanged)
        }
        mVolumeBoost = Math.max(volume, 1f);
    }

    /**
     * Manage audio focus. E.g. use Spotify when audio is disabled.
     */
    private void setupAudioFocus(ExoPlayer player) {
        if (player != null && mPlayerTweaksData.isAudioFocusEnabled()) {
            try {
                player.setAudioAttributes(getAudioAttributes(), true);
            } catch (SecurityException e) { // uid 10390 not allowed to perform TAKE_AUDIO_FOCUS
                e.printStackTrace();
            }
        }
    }

    private void applyPlaybackFixes(ExoPlayer player) {
        // Try to fix decoder error on Nvidia Shield 2019.
        // Init resources as early as possible.
        //player.setForegroundMode(true);
        // NOTE: Avoid using seekParameters. ContentBlock hangs because of constant skipping to the segment start.
        // ContentBlock hangs on the last segment: https://www.youtube.com/watch?v=pYymRbfjKv8

        // Fix seeking on TextureView (some devices only)
        if (mPlayerTweaksData.isTextureViewEnabled()) {
            // Also, live stream (dash) seeking fix
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        }
    }

    public float getVolumeBoost() {
        return mVolumeBoost;
    }

    public void release() {
        if (mVolumeBooster != null) {
            mVolumeBooster.release();
        }

        if (mPlayer != null && mVolumeBooster != null) {
            mPlayer.removeListener(mVolumeBooster);
        }

        mVolumeBooster = null;
        mPlayer = null;
        if (mSpareVolumeBooster != null) {
            mSpareVolumeBooster.release();
            if (mSparePlayer != null) {
                mSparePlayer.removeListener(mSpareVolumeBooster);
            }
        }
        mSpareVolumeBooster = null;
        mSparePlayer = null;
        mLoadControls.clear();
        if (mPreloadManager != null) {
            mPreloadManager.release();
            mPreloadManager = null;
        }
    }

    // JoTube M3: the unused createDrmManager() and DummyBandwidthMeter (2.10 only) were removed.
}
