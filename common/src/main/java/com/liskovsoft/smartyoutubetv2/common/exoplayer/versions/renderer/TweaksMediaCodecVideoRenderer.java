package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import com.liskovsoft.sharedutils.mylogger.Log;

public class TweaksMediaCodecVideoRenderer extends DebugInfoMediaCodecVideoRenderer {
    private static final String TAG = TweaksMediaCodecVideoRenderer.class.getSimpleName();
    private boolean mIsFrameDropFixEnabled;
    private boolean mIsFrameDropSonyFixEnabled;
    private boolean mIsAmlogicFixEnabled;

    public TweaksMediaCodecVideoRenderer(Context context, MediaCodecAdapter.Factory codecAdapterFactory, MediaCodecSelector mediaCodecSelector,
                                         long allowedJoiningTimeMs, boolean enableDecoderFallback, @Nullable Handler eventHandler,
                                         @Nullable VideoRendererEventListener eventListener, int maxDroppedFramesToNotify) {
        super(context, codecAdapterFactory, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener,
                maxDroppedFramesToNotify);
    }

    @Override
    protected void renderOutputBufferV21(
            MediaCodecAdapter codec, int index, long presentationTimeUs, long releaseTimeNs) {
        // Fix frame drops on SurfaceView
        // https://github.com/google/ExoPlayer/issues/6348
        // https://developer.android.com/reference/android/media/MediaCodec#releaseOutputBuffer(int,%20long)
        super.renderOutputBufferV21(codec, index, presentationTimeUs, mIsFrameDropFixEnabled ? 0 : releaseTimeNs);
    }

    @Override
    protected CodecMaxValues getCodecMaxValues(
            MediaCodecInfo codecInfo, Format format, Format[] streamFormats) {
        CodecMaxValues maxValues =
                super.getCodecMaxValues(codecInfo, format, streamFormats);

        if (mIsAmlogicFixEnabled) {
            if (maxValues.width < 1920 || maxValues.height < 1089) {
                Log.d(TAG, "Applying Amlogic fix...");
                return new CodecMaxValues(
                        Math.max(maxValues.width, 1920),
                        Math.max(maxValues.height, 1089),
                        maxValues.inputSize);
            }
        }

        return maxValues;
    }

    /**
     * Frame drop fixes on Sony Bravia<br/>
     * https://github.com/google/ExoPlayer/issues/6348#issuecomment-718986083<br/>
     * M3: isBufferLate(earlyUs) became shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastBuffer)
     */
    @Override
    protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
        if (mIsFrameDropSonyFixEnabled) {
            return earlyUs < -1000000 && !isLastBuffer;
        }

        return super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastBuffer);
    }

    /**
     * Frame drop fixes on Sony Bravia<br/>
     * https://github.com/google/ExoPlayer/issues/6348#issuecomment-718986083<br/>
     * M3: isBufferVeryLate(earlyUs) became shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs, isLastBuffer)
     */
    @Override
    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
        if (mIsFrameDropSonyFixEnabled) {
            return earlyUs < -1500000 && !isLastBuffer;
        }

        return super.shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs, isLastBuffer);
    }

    public void enableFrameDropFix(boolean enabled) {
        mIsFrameDropFixEnabled = enabled;
    }

    public void enableFrameDropSonyFix(boolean enabled) {
        mIsFrameDropSonyFixEnabled = enabled;
    }

    public void enableAmlogicFix(boolean enabled) {
        mIsAmlogicFixEnabled = enabled;
    }
}
