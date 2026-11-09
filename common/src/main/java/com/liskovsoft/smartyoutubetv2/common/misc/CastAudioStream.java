package com.liskovsoft.smartyoutubetv2.common.misc;

import android.util.Log;
import androidx.media3.common.util.UnstableApi;

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
 * that got them), so the phone's own player stays the master: the decoded audio is caught (CastAudioTap) and served to
 * the receiver as a WAV stream on the local network. The stream is live: it carries whatever the phone plays right now.
 * Length and byte offsets follow the track time (constant PCM rate), so the receiver sees a normal file.
 */
@UnstableApi
public final class CastAudioStream implements CastAudioTap.Sink {
    private static final String TAG = "JoTubeCast";
    private static final int HEADER_SIZE = 44;
    private static final int TAIL_GUARD = 1 << 20; // a request this close to the end is a probe, not the music
    private static CastAudioStream sCurrent;

    private final ArrayBlockingQueue<byte[]> mQueue = new ArrayBlockingQueue<>(400); // newest audio wins
    private final CastAudioTap mTap = CastAudioTap.instance();
    private ServerSocket mServer;
    private String mHost;
    private volatile boolean mReleased;
    private volatile boolean mEnded;
    private volatile int mActiveRequest;
    private volatile int mGeneration;
    private volatile long mDurationMs;

    /**
     * Starts (or re-aims) the server for a track and returns the URL for the receiver (or null). Call again for a new
     * track or after a seek: the URL changes, so the receiver opens a new connection. Main thread.
     */
    public static synchronized String prepare(long durationMs, boolean newTrack) {
        try {
            if (sCurrent == null) {
                String host = findLocalAddress();
                if (host == null) {
                    Log.e(TAG, "No Wi-Fi address to serve from");
                    return null;
                }
                CastAudioStream stream = new CastAudioStream();
                stream.mHost = host;
                stream.openServer();
                sCurrent = stream;
            }
            CastAudioStream stream = sCurrent;
            stream.mDurationMs = durationMs;
            stream.mEnded = false;
            stream.mQueue.clear();
            if (newTrack) {
                stream.mTap.invalidateFormat();
            }
            stream.mTap.setSink(stream, true); // the phone itself is silent from now on
            int generation = ++stream.mGeneration;
            return "http://" + stream.mHost + ":" + stream.mServer.getLocalPort() + "/audio.wav?n=" + generation;
        } catch (Throwable e) {
            Log.e(TAG, "Could not start the audio stream: " + e, e);
            stop();
            return null;
        }
    }

    /** Ends casting: the phone is audible again. */
    public static synchronized void stop() {
        CastAudioTap.instance().setSink(null, false);
        if (sCurrent != null) {
            sCurrent.release();
            sCurrent = null;
        }
    }

    @Override
    public void onAudio(byte[] pcm) {
        while (!mQueue.offer(pcm)) {
            mQueue.poll(); // nobody is listening fast enough: drop the oldest
        }
    }

    @Override
    public void onFlush() {
        mQueue.clear(); // a seek or a new track: older audio is stale
        mEnded = false;
    }

    @Override
    public void onEnd() {
        mEnded = true;
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

            if (!waitForFormat()) {
                writeHead(out, "503 Service Unavailable", null, 0);
                return;
            }

            final int rate = mTap.getRate();
            final int channels = mTap.getChannels();
            final int bytesPerSec = rate * channels * 2;
            final int frame = channels * 2;
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
                byte[] header = wavHeader(dataLen, rate, channels);
                out.write(header, (int) start, HEADER_SIZE - (int) start);
                remaining -= HEADER_SIZE - start;
            }

            if (start >= total - TAIL_GUARD && start >= HEADER_SIZE) {
                writeSilence(out, remaining); // a probe at the end of the file: no seeking for that
                return;
            }

            final int id = ++mActiveRequest; // the newest connection takes over from older ones
            mQueue.clear(); // only audio from now on
            while (remaining > 0 && id == mActiveRequest && !mReleased) {
                byte[] chunk = mQueue.poll(500, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    if (mEnded) {
                        writeSilence(out, remaining); // the length is rounded to seconds: pad the last bit
                        return;
                    }
                    continue;
                }
                int n = (int) Math.min(chunk.length, remaining);
                out.write(chunk, 0, n);
                remaining -= n;
            }
            out.flush();
        } catch (IOException | InterruptedException e) {
            // the receiver closes the connection when it seeks or stops: normal
        } catch (Throwable e) {
            Log.e(TAG, "Audio stream error: " + e, e);
        }
    }

    private boolean waitForFormat() throws InterruptedException {
        for (int i = 0; i < 60 && !mTap.isFormatFresh() && !mReleased; i++) {
            Thread.sleep(50); // a new track reports its format a moment after it was opened
        }
        for (int i = 0; i < 400 && mTap.getRate() == 0 && !mReleased; i++) {
            Thread.sleep(50);
        }
        return mTap.getRate() > 0 && !mReleased;
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
}
