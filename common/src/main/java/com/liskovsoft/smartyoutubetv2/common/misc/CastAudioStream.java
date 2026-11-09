package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.MediaSource;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * JoTube: audio for Cast. YouTube's own stream URLs can't be handed to a Cast receiver (they only work inside the player
 * that got them), so the app plays the track with its own SABR engine in a hidden second player (muted on the phone),
 * catches the decoded audio and serves it to the receiver as a WAV stream on the local network.
 *
 * Byte offset and time are tied together (constant PCM rate), so the receiver can seek with range requests.
 */
@UnstableApi
public final class CastAudioStream {
    private static final String TAG = "JoTubeCast";
    private static final int HEADER_SIZE = 44;
    private static final int TAIL_GUARD = 1 << 20; // a request this close to the end is a probe, not a seek
    private static final Object LOCK = new Object();
    private static CastAudioStream sCurrent;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<Chunk> mQueue = new ArrayBlockingQueue<>(600); // ~ 10 s of stereo audio
    private final CaptureProcessor mProcessor = new CaptureProcessor();
    private final long mDurationMs;
    private final long mInitialStartMs;

    private ExoPlayer mPlayer;
    private ServerSocket mServer;
    private volatile boolean mReleased;
    private volatile boolean mFormatReady;
    private volatile boolean mEnded;
    private volatile int mRate;
    private volatile int mChannels;
    private volatile int mEpoch;      // changes whenever the player jumps (seek), older audio is stale
    private volatile int mActiveRequest;
    private boolean mServedOnce;
    private long mEpochStartMs;       // track time of the first byte read in the current epoch
    private long mReadBytes;          // PCM bytes handed to the receiver in the current epoch

    private static final class Chunk {
        final byte[] data;
        final int epoch;

        Chunk(byte[] data, int epoch) {
            this.data = data;
            this.epoch = epoch;
        }
    }

    private CastAudioStream(long durationMs, long startMs) {
        mDurationMs = durationMs;
        mInitialStartMs = startMs;
        mEpochStartMs = startMs;
    }

    /** Plays the track in the hidden player, starts serving, and returns the URL for the receiver (or null). Main thread. */
    public static String start(Context context, MediaItemFormatInfo info, long startMs, long durationMs) {
        synchronized (LOCK) {
            stop();
            try {
                String host = findLocalAddress();
                if (host == null) {
                    Log.e(TAG, "No Wi-Fi address to serve from");
                    return null;
                }

                CastAudioStream stream = new CastAudioStream(durationMs, Math.max(startMs, 0));
                MediaSource source = info.containsDashFormats()
                        ? new ExoMediaSourceFactory(context).fromDashFormatInfo(info)
                        : new ExoMediaSourceFactory(context).fromSabrFormatInfo(info);
                stream.openPlayer(context, source);
                stream.openServer();
                sCurrent = stream;
                String url = "http://" + host + ":" + stream.mServer.getLocalPort() + "/audio.wav";
                Log.d(TAG, "Serving " + url + " from " + stream.mInitialStartMs + " ms");
                return url;
            } catch (Throwable e) {
                Log.e(TAG, "Could not start the audio stream: " + e, e);
                stop();
                return null;
            }
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            if (sCurrent != null) {
                sCurrent.release();
                sCurrent = null;
            }
        }
    }

    private void openPlayer(Context context, MediaSource source) {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context) {
            @Override
            protected AudioSink buildAudioSink(Context c, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParameters) {
                return new DefaultAudioSink.Builder(c)
                        .setEnableFloatOutput(false)
                        .setAudioProcessors(new AudioProcessor[]{mProcessor})
                        .build();
            }
        };
        mPlayer = new ExoPlayer.Builder(context, renderers).build();
        mPlayer.setTrackSelectionParameters(mPlayer.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build());
        mPlayer.setVolume(0f); // the sound goes to the receiver, not the phone
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                mEpoch++;
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                mEnded = state == Player.STATE_ENDED;
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Audio stream player error: " + error);
            }
        });
        mPlayer.setMediaSource(source, mInitialStartMs);
        mPlayer.prepare();
        mPlayer.setPlayWhenReady(true);
    }

    private void openServer() throws IOException {
        mServer = new ServerSocket(0);
        Thread acceptor = new Thread(() -> {
            while (!mReleased && !mServer.isClosed()) {
                try {
                    final Socket socket = mServer.accept();
                    Thread worker = new Thread(() -> handle(socket), "CastAudioConn");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "CastAudioServer");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void release() {
        mReleased = true;
        mQueue.clear();
        try {
            if (mServer != null) {
                mServer.close();
            }
        } catch (IOException ignored) {
        }
        mMain.post(() -> {
            if (mPlayer != null) {
                mPlayer.release();
                mPlayer = null;
            }
        });
    }

    // --- HTTP ---------------------------------------------------------------------------------------------------

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            long rangeStart = -1;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.US).startsWith("range:")) {
                    rangeStart = parseRangeStart(line.substring(6).trim());
                }
            }
            String method = requestLine.split(" ")[0];
            OutputStream out = s.getOutputStream();

            if ("OPTIONS".equals(method)) {
                writeHead(out, "204 No Content", null, -1);
                return;
            }

            int id = ++mActiveRequest; // a newer request takes over from older ones
            if (!waitForFormat()) {
                writeHead(out, "503 Service Unavailable", null, 0);
                return;
            }

            final int bytesPerSec = mRate * mChannels * 2;
            final int frame = mChannels * 2;
            long dataLen = mDurationMs * bytesPerSec / 1000;
            dataLen -= dataLen % frame;
            final long total = HEADER_SIZE + dataLen;
            final long start = Math.max(rangeStart, 0);
            if (start >= total) {
                writeHead(out, "416 Range Not Satisfiable", "bytes */" + total, 0);
                return;
            }

            boolean partial = rangeStart >= 0;
            String contentRange = partial ? "bytes " + start + "-" + (total - 1) + "/" + total : null;
            writeHead(out, partial ? "206 Partial Content" : "200 OK", contentRange, total - start);
            if ("HEAD".equals(method)) {
                return;
            }

            long remaining = total - start;
            if (start < HEADER_SIZE) {
                byte[] header = wavHeader(dataLen, mRate, mChannels);
                out.write(header, (int) start, HEADER_SIZE - (int) start);
                remaining -= HEADER_SIZE - start;
            }

            if (start >= total - TAIL_GUARD && start >= HEADER_SIZE) {
                writeSilence(out, remaining); // a probe at the end of the file: no seeking for that
                return;
            }

            long dataStartMs = start < HEADER_SIZE ? (mServedOnce ? 0 : mInitialStartMs)
                    : (start - HEADER_SIZE) * 1000 / bytesPerSec;
            if (!positionAt(dataStartMs, bytesPerSec)) {
                return;
            }
            mServedOnce = true;

            while (remaining > 0 && id == mActiveRequest && !mReleased) {
                Chunk chunk = mQueue.poll(500, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    if (mEnded && mQueue.isEmpty()) {
                        writeSilence(out, remaining); // the length is rounded to seconds: pad the last bit
                        return;
                    }
                    continue;
                }
                if (chunk.epoch != mEpoch) {
                    continue; // from before a seek
                }
                int n = (int) Math.min(chunk.data.length, remaining);
                out.write(chunk.data, 0, n);
                remaining -= n;
                mReadBytes += n;
            }
            out.flush();
        } catch (IOException | InterruptedException e) {
            // the receiver closes the connection when it seeks or stops: normal
        } catch (Throwable e) {
            Log.e(TAG, "Audio stream error: " + e, e);
        }
    }

    /** Makes the next byte read be the audio at this track time, seeking the hidden player when needed. */
    private boolean positionAt(long timeMs, int bytesPerSec) throws InterruptedException {
        long nextMs = mEpochStartMs + mReadBytes * 1000 / bytesPerSec;
        if (Math.abs(timeMs - nextMs) <= 700) {
            return true;
        }

        final int before = mEpoch;
        mMain.post(() -> {
            if (mPlayer != null) {
                mPlayer.seekTo(timeMs);
            }
        });
        for (int i = 0; i < 100 && mEpoch == before && !mReleased; i++) {
            Thread.sleep(50);
        }
        mQueue.clear();
        mEpochStartMs = timeMs;
        mReadBytes = 0;
        Log.d(TAG, "Receiver seeked to " + timeMs + " ms");
        return !mReleased;
    }

    private boolean waitForFormat() throws InterruptedException {
        for (int i = 0; i < 400 && !mFormatReady && !mReleased; i++) {
            Thread.sleep(50);
        }
        return mFormatReady && !mReleased;
    }

    private static long parseRangeStart(String value) {
        try {
            String bytes = value.replace("bytes=", "").trim();
            return Long.parseLong(bytes.substring(0, bytes.indexOf('-')).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void writeHead(OutputStream out, String status, String contentRange, long length) throws IOException {
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Headers: Range\r\n");
        head.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n");
        head.append("Content-Type: audio/wav\r\n");
        head.append("Accept-Ranges: bytes\r\n");
        if (contentRange != null) {
            head.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        if (length >= 0) {
            head.append("Content-Length: ").append(length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void writeSilence(OutputStream out, long bytes) throws IOException {
        byte[] zeros = new byte[8192];
        while (bytes > 0) {
            int n = (int) Math.min(zeros.length, bytes);
            out.write(zeros, 0, n);
            bytes -= n;
        }
        out.flush();
    }

    private static byte[] wavHeader(long dataLen, int rate, int channels) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + dataLen));
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) dataLen);
        return b.array();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buffer.write(c);
            }
            if (buffer.size() > 8192) {
                throw new IOException("line too long");
            }
        }
        return c == -1 && buffer.size() == 0 ? null : buffer.toString("ISO-8859-1");
    }

    private static String findLocalAddress() throws IOException {
        String fallback = null;
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    if (nif.getName().startsWith("wlan") || nif.getName().startsWith("eth")) {
                        return address.getHostAddress();
                    }
                    if (fallback == null && !nif.getName().startsWith("tun")) {
                        fallback = address.getHostAddress();
                    }
                }
            }
        }
        return fallback;
    }

    // --- the audio catcher ----------------------------------------------------------------------------------------

    /** Passes the audio on unchanged and keeps a copy for the receiver. Runs on the hidden player's playback thread. */
    private final class CaptureProcessor extends BaseAudioProcessor {
        @Override
        protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
                throw new UnhandledAudioFormatException(inputAudioFormat);
            }
            mRate = inputAudioFormat.sampleRate;
            mChannels = inputAudioFormat.channelCount;
            mFormatReady = true;
            return inputAudioFormat;
        }

        @Override
        public void queueInput(ByteBuffer inputBuffer) {
            int n = inputBuffer.remaining();
            if (n == 0) {
                return;
            }
            byte[] copy = new byte[n];
            inputBuffer.get(copy);
            ByteBuffer out = replaceOutputBuffer(n);
            out.put(copy);
            out.flip();

            Chunk chunk = new Chunk(copy, mEpoch);
            try {
                // blocks while the receiver is paused or behind: the hidden player simply waits
                while (!mReleased && !mQueue.offer(chunk, 250, TimeUnit.MILLISECONDS)) {
                    if (chunk.epoch != mEpoch) {
                        return; // stale after a seek
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
