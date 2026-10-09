package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.media.MediaCodec;
import android.os.Build.VERSION;
import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.ExoUtils;

public class DebugInfoMediaCodecVideoRenderer extends MediaCodecVideoRenderer {
    private static final String TAG = DebugInfoMediaCodecVideoRenderer.class.getSimpleName();
    private int mFrameIndex;
    private boolean mIsSetOutputSurfaceWorkaroundEnabled;

    // Exo 2.9
    //public DebugInfoMediaCodecVideoRenderer(Context context, MediaCodecSelector mediaCodecSelector, long allowedJoiningTimeMs,
    //                                     @Nullable DrmSessionManager<FrameworkMediaCrypto> drmSessionManager, boolean playClearSamplesWithoutKeys,
    //                                     @Nullable Handler eventHandler, @Nullable VideoRendererEventListener eventListener,
    //                                     int maxDroppedFramesToNotify) {
    //    super(context, mediaCodecSelector, allowedJoiningTimeMs, drmSessionManager, playClearSamplesWithoutKeys, eventHandler, eventListener,
    //            maxDroppedFramesToNotify);
    //}

    // Exo 2.10, 2.11
    public DebugInfoMediaCodecVideoRenderer(Context context, MediaCodecSelector mediaCodecSelector, long allowedJoiningTimeMs,
                                            @Nullable DrmSessionManager<FrameworkMediaCrypto> drmSessionManager, boolean playClearSamplesWithoutKeys, boolean enableDecoderFallback, @Nullable Handler eventHandler, @Nullable VideoRendererEventListener eventListener, int maxDroppedFramesToNotify) {
        super(context, mediaCodecSelector, allowedJoiningTimeMs, drmSessionManager, playClearSamplesWithoutKeys, enableDecoderFallback, eventHandler, eventListener, maxDroppedFramesToNotify);
    }

    // Exo 2.12, 2.13
    //public DebugInfoMediaCodecVideoRenderer(Context context, MediaCodecSelector mediaCodecSelector, long allowedJoiningTimeMs,
    //                                     boolean enableDecoderFallback, @Nullable Handler eventHandler,
    //                                     @Nullable VideoRendererEventListener eventListener, int maxDroppedFramesToNotify) {
    //    super(context, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, maxDroppedFramesToNotify);
    //}

    @Override
    protected CodecMaxValues getCodecMaxValues(
            MediaCodecInfo codecInfo, Format format, Format[] streamFormats) {
        ExoUtils.updateVideoDecoderInfo(codecInfo);

        CodecMaxValues values = super.getCodecMaxValues(codecInfo, format, streamFormats);

        // JoTube: Shorts are a playlist of videos of different sizes. The decoder is set up for at least 1920x1920
        // (portrait 1080x1920 and landscape 1920x1080 both fit), so the next Short doesn't need a new decoder
        // (~350 ms before its first frame). Only a size the decoder really supports.
        if (ShortsTransitionState.isShortsMode() && codecInfo.adaptive) {
            int width = Math.max(values.width, SHORTS_MIN_MAX_SIZE);
            int height = Math.max(values.height, SHORTS_MIN_MAX_SIZE);

            if ((width > values.width || height > values.height) && isSizeSupported(codecInfo, width, height, format.frameRate)) {
                int inputSize = Math.max(values.inputSize,
                        getCodecMaxInputSize(codecInfo, format.sampleMimeType, width, height));
                Log.d(TAG, "Shorts: codec max size %sx%s (format %sx%s)", width, height, format.width, format.height);
                values = new CodecMaxValues(width, height, inputSize);
            }
        }

        mMaxValues = values;
        return values;
    }

    private static final int SHORTS_MIN_MAX_SIZE = 1920;
    private CodecMaxValues mMaxValues;

    private static boolean isSizeSupported(MediaCodecInfo codecInfo, int width, int height, float frameRate) {
        if (VERSION.SDK_INT < 21 || codecInfo.capabilities == null) {
            return false;
        }

        android.media.MediaCodecInfo.VideoCapabilities videoCapabilities = codecInfo.capabilities.getVideoCapabilities();

        // Directly (MediaCodecInfo.isVideoSizeAndRateSupportedV21 also accepts the size rotated)
        return videoCapabilities != null &&
                videoCapabilities.areSizeAndRateSupported(width, height, frameRate > 0 ? frameRate : 30);
    }

    @Override
    protected int canKeepCodec(MediaCodec codec, MediaCodecInfo codecInfo, Format oldFormat, Format newFormat) {
        int result = super.canKeepCodec(codec, codecInfo, oldFormat, newFormat);

        // JoTube: Shorts of the same kind often differ only in the color info (one has BT.709 set, the other
        // nothing). For SDR video that doesn't need a new decoder.
        if (result == KEEP_CODEC_RESULT_NO && ShortsTransitionState.isShortsMode() && mMaxValues != null &&
                codecInfo.adaptive &&
                oldFormat.sampleMimeType != null && oldFormat.sampleMimeType.equals(newFormat.sampleMimeType) &&
                oldFormat.rotationDegrees == newFormat.rotationDegrees &&
                newFormat.width <= mMaxValues.width && newFormat.height <= mMaxValues.height &&
                (newFormat.maxInputSize == Format.NO_VALUE || newFormat.maxInputSize <= mMaxValues.inputSize) &&
                isSdr(oldFormat.colorInfo) && isSdr(newFormat.colorInfo)) {
            result = oldFormat.initializationDataEquals(newFormat) ?
                    KEEP_CODEC_RESULT_YES_WITHOUT_RECONFIGURATION : KEEP_CODEC_RESULT_YES_WITH_RECONFIGURATION;
        }

        if (result == KEEP_CODEC_RESULT_NO) {
            Log.d(TAG, "New decoder needed: %sx%s -> %sx%s, adaptive %s, color %s -> %s", oldFormat.width, oldFormat.height,
                    newFormat.width, newFormat.height, codecInfo.adaptive, oldFormat.colorInfo, newFormat.colorInfo);
        }

        return result;
    }

    private static boolean isSdr(@Nullable com.google.android.exoplayer2.video.ColorInfo colorInfo) {
        return colorInfo == null || (colorInfo.colorTransfer != C.COLOR_TRANSFER_ST2084 &&
                colorInfo.colorTransfer != C.COLOR_TRANSFER_HLG);
    }

    // Measure real fps.
    // Note, that you can't accurate measure frame rate because actual frame rate is the average frame rate for the whole video track!
    // 29.97fps test: https://www.youtube.com/watch?v=LXb3EKWsInQ (Costa Rica)
    // More info: https://github.com/google/ExoPlayer/issues/4088
    //@Override
    //protected void renderOutputBuffer(MediaCodec codec, int index, long presentationTimeUs) {
    //    super.renderOutputBuffer(codec, index, presentationTimeUs);
    //}
    //
    //@Override
    //protected void renderOutputBufferV21(MediaCodec codec, int index, long presentationTimeUs, long releaseTimeNs) {
    //    super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs);
    //
    //    mFrameIndex++;
    //
    //    Log.d(TAG, "Real fps: %s", 1_000_000f / (presentationTimeUs / mFrameIndex));
    //}

    @Override
    protected boolean codecNeedsSetOutputSurfaceWorkaround(String name) {
        // Null surface error on Android 9 (VERSION.SDK_INT >= 28) and above (appears on background audio playback)
        // Need to be enabled on older version of ExoPlayer (e.g. 2.10.6).
        // It's because there's no tweaks for modern devices.
        return mIsSetOutputSurfaceWorkaroundEnabled || super.codecNeedsSetOutputSurfaceWorkaround(name);
    }

    /**
     * Null surface error on Android 9 (VERSION.SDK_INT >= 28) and above (appears on background audio playback)<br/>
     * Need to be enabled on older version of ExoPlayer (e.g. 2.10.6).<br/>
     * It's because there's no tweaks for modern devices.
     */
    public void enableSetOutputSurfaceWorkaround(boolean enable) {
        mIsSetOutputSurfaceWorkaroundEnabled = enable;
    }
}
