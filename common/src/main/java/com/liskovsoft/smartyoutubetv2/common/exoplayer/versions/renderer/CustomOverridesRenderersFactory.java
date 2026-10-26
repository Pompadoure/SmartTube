package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.os.Handler;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.BlacklistMediaCodecSelector;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.util.ArrayList;

/**
 * Main intent: override audio delay
 */
public class CustomOverridesRenderersFactory extends CustomRenderersFactoryBase {
    private static final String TAG = CustomOverridesRenderersFactory.class.getSimpleName();
    private static final String[] FRAME_DROP_FIX_LIST = {
            "T95ZPLUS (q201_3GB)",
            "UGOOS (UGOOS)",
            "55UC30G (ctl_iptv_mrvl)" // Kivi 55uc30g
    };
    private final PlayerData mPlayerData;
    private final PlayerTweaksData mPlayerTweaksData;

    public CustomOverridesRenderersFactory(Context activity) {
        super(activity);

        mPlayerData = PlayerData.instance(activity);
        mPlayerTweaksData = PlayerTweaksData.instance(activity);

        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON);
        // setEnableDecoderFallback(true);

        if (mPlayerTweaksData.isSWDecoderForced()) {
            setMediaCodecSelector(new BlacklistMediaCodecSelector());
        }

        // M3: AmazonQuirks (disableSnappingToVsync, skipProfileLevelCheck) has no Media3 counterpart: dropped.
    }

    @Override
    protected void buildAudioRenderers(Context context, @ExtensionRendererMode int extensionRendererMode, MediaCodecSelector mediaCodecSelector,
                                       boolean enableDecoderFallback, AudioSink audioSink, Handler eventHandler,
                                       AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler,
                eventListener, out);

        if ((mPlayerData.getAudioDelayMs() == 0 || !mPlayerData.isAudioDelayEnabled()) && !mPlayerTweaksData.isAudioSyncFixEnabled()) {
            // Improve performance a bit by eliminating calculations presented in custom renderer.

            return;
        }

        // M3: the audio sink is built by DefaultRenderersFactory (buildAudioSink) and passed in, no own DefaultAudioSink needed.
        DelayMediaCodecAudioRenderer audioRenderer =
                new DelayMediaCodecAudioRenderer(context, getCodecAdapterFactory(), mediaCodecSelector, enableDecoderFallback,
                        eventHandler, eventListener, audioSink);

        audioRenderer.setAudioDelayMs(mPlayerData.isAudioDelayEnabled() ? mPlayerData.getAudioDelayMs() : 0);
        audioRenderer.enableAudioSyncFix(mPlayerTweaksData.isAudioSyncFixEnabled());

        replaceAudioRenderer(out, audioRenderer);
    }

    @Override
    protected void buildVideoRenderers(Context context, @ExtensionRendererMode int extensionRendererMode, MediaCodecSelector mediaCodecSelector,
                                       boolean enableDecoderFallback, Handler eventHandler, VideoRendererEventListener eventListener,
                                       long allowedVideoJoiningTimeMs, ArrayList<Renderer> out) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler,
                eventListener, allowedVideoJoiningTimeMs, out);

        if (!mPlayerTweaksData.isAmazonFrameDropFixEnabled() && !mPlayerTweaksData.isSonyFrameDropFixEnabled() && !mPlayerTweaksData.isAmlogicFixEnabled()) {
            // Improve performance a bit by eliminating some if conditions presented in tweaks.
            // But we need to obtain codec real name somehow. So use interceptor below.

            DebugInfoMediaCodecVideoRenderer videoRenderer =
                    new DebugInfoMediaCodecVideoRenderer(context, getCodecAdapterFactory(), mediaCodecSelector, allowedVideoJoiningTimeMs,
                            enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);

            videoRenderer.enableSetOutputSurfaceWorkaround(true); // Force enable?

            replaceVideoRenderer(out, videoRenderer);

            return;
        }

        TweaksMediaCodecVideoRenderer videoRenderer =
                new TweaksMediaCodecVideoRenderer(context, getCodecAdapterFactory(), mediaCodecSelector, allowedVideoJoiningTimeMs,
                        enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);

        videoRenderer.enableFrameDropFix(mPlayerTweaksData.isAmazonFrameDropFixEnabled());
        videoRenderer.enableFrameDropSonyFix(mPlayerTweaksData.isSonyFrameDropFixEnabled());
        videoRenderer.enableAmlogicFix(mPlayerTweaksData.isAmlogicFixEnabled());
        videoRenderer.enableSetOutputSurfaceWorkaround(true); // Force enable?

        replaceVideoRenderer(out, videoRenderer);
    }
}
