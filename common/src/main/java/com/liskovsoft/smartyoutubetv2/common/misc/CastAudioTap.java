package com.liskovsoft.smartyoutubetv2.common.misc;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;

/**
 * JoTube: catches the decoded audio of the phone's own player so it can be sent to a Cast receiver. The audio passes
 * on unchanged, or as silence while casting (the sound should only come out of the receiver). Only PCM16 is handled,
 * anything else is passed through untouched. Does nothing unless enabled (mobile app only).
 */
@UnstableApi
public final class CastAudioTap extends BaseAudioProcessor {
    public interface Sink {
        void onAudio(byte[] pcm);
        void onFlush();
        void onEnd();
    }

    private static final CastAudioTap INSTANCE = new CastAudioTap();
    private static volatile boolean sEnabled;

    private volatile Sink mSink;
    private volatile boolean mMute;
    private volatile int mRate;
    private volatile int mChannels;
    private volatile boolean mFresh;

    public static CastAudioTap instance() {
        return INSTANCE;
    }

    public static void enable() {
        sEnabled = true;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    /** While a sink is set the audio is copied to it; with mute the phone itself gets silence. */
    public void setSink(Sink sink, boolean mute) {
        mMute = mute && sink != null;
        mSink = sink;
    }

    /** The next track will report its format; until then the old one may be wrong. */
    public void invalidateFormat() {
        mFresh = false;
    }

    public int getRate() {
        return mRate;
    }

    public int getChannels() {
        return mChannels;
    }

    public boolean isFormatFresh() {
        return mFresh;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioFormat.NOT_SET; // not touched, and not castable
        }
        mRate = inputAudioFormat.sampleRate;
        mChannels = inputAudioFormat.channelCount;
        mFresh = true;
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int n = inputBuffer.remaining();
        if (n == 0) {
            return;
        }
        Sink sink = mSink;
        ByteBuffer out = replaceOutputBuffer(n);
        if (sink == null) {
            out.put(inputBuffer);
        } else {
            byte[] copy = new byte[n];
            inputBuffer.get(copy);
            sink.onAudio(copy);
            if (!mMute) {
                out.put(copy);
            } else {
                out.put(new byte[n]);
            }
        }
        out.flip();
    }

    @Override
    protected void onFlush(StreamMetadata streamMetadata) {
        Sink sink = mSink;
        if (sink != null) {
            sink.onFlush();
        }
    }

    @Override
    protected void onQueueEndOfStream() {
        Sink sink = mSink;
        if (sink != null) {
            sink.onEnd();
        }
    }
}
