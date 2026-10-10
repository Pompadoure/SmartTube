package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.os.Build.VERSION;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ShortsTransitionState;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.ExoUtils;

public class DebugInfoMediaCodecVideoRenderer extends MediaCodecVideoRenderer {
    private static final String TAG = DebugInfoMediaCodecVideoRenderer.class.getSimpleName();
    private boolean mIsSetOutputSurfaceWorkaroundEnabled;

    public DebugInfoMediaCodecVideoRenderer(Context context, MediaCodecAdapter.Factory codecAdapterFactory, MediaCodecSelector mediaCodecSelector,
                                            long allowedJoiningTimeMs, boolean enableDecoderFallback, @Nullable Handler eventHandler,
                                            @Nullable VideoRendererEventListener eventListener, int maxDroppedFramesToNotify) {
        super(context, codecAdapterFactory, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener,
                maxDroppedFramesToNotify);
    }

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

            if (!isSizeSupported(codecInfo, width, height, format.frameRate)) {
                // E.g. a 1080p class decoder: at least the full 1080p size in this orientation
                boolean portrait = format.height >= format.width;
                width = Math.max(values.width, portrait ? 1080 : 1920);
                height = Math.max(values.height, portrait ? 1920 : 1080);
            }

            if ((width > values.width || height > values.height) && isSizeSupported(codecInfo, width, height, format.frameRate)) {
                int inputSize = Math.max(values.inputSize,
                        getMaxInputSize(codecInfo, format.buildUpon()
                                .setWidth(width).setHeight(height).setMaxInputSize(Format.NO_VALUE).build()));
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

    // M3: canKeepCodec(MediaCodec, ...) + KEEP_CODEC_RESULT_* became canReuseCodec(...) + DecoderReuseEvaluation.REUSE_RESULT_*
    @Override
    protected DecoderReuseEvaluation canReuseCodec(MediaCodecInfo codecInfo, Format oldFormat, Format newFormat,
                                                   boolean isAdaptiveFormatChange) {
        DecoderReuseEvaluation evaluation = super.canReuseCodec(codecInfo, oldFormat, newFormat, isAdaptiveFormatChange);

        // JoTube: Shorts of the same kind often differ only in the color info (one has BT.709 set, the other
        // nothing). For SDR video that doesn't need a new decoder.
        if (evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO && ShortsTransitionState.isShortsMode() && mMaxValues != null &&
                codecInfo.adaptive &&
                oldFormat.sampleMimeType != null && oldFormat.sampleMimeType.equals(newFormat.sampleMimeType) &&
                oldFormat.rotationDegrees == newFormat.rotationDegrees &&
                newFormat.width <= mMaxValues.width && newFormat.height <= mMaxValues.height &&
                (newFormat.maxInputSize == Format.NO_VALUE || newFormat.maxInputSize <= mMaxValues.inputSize) &&
                isSdr(oldFormat.colorInfo) && isSdr(newFormat.colorInfo)) {
            int result = oldFormat.initializationDataEquals(newFormat) ?
                    DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION : DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION;
            evaluation = new DecoderReuseEvaluation(codecInfo.name, oldFormat, newFormat, result, /* discardReasons= */ 0);
        }

        if (evaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO) {
            Log.d(TAG, "New decoder needed: %sx%s -> %sx%s, adaptive %s, color %s -> %s", oldFormat.width, oldFormat.height,
                    newFormat.width, newFormat.height, codecInfo.adaptive, oldFormat.colorInfo, newFormat.colorInfo);
        }

        return evaluation;
    }

    private static boolean isSdr(@Nullable androidx.media3.common.ColorInfo colorInfo) {
        return colorInfo == null || (colorInfo.colorTransfer != C.COLOR_TRANSFER_ST2084 &&
                colorInfo.colorTransfer != C.COLOR_TRANSFER_HLG);
    }

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
