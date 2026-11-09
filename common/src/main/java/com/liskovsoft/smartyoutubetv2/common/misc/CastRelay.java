package com.liskovsoft.smartyoutubetv2.common.misc;

import android.util.Log;
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

/**
 * JoTube: a tiny HTTP relay for Cast. YouTube's stream URLs only work for the client that got them (IP, token and
 * headers), so a Cast receiver gets 403 when it fetches one itself. The phone fetches the stream with the app's own
 * HTTP client and hands it on to the receiver on the local network. Serves one stream at a time, range requests included.
 */
public final class CastRelay {
    private static final String TAG = "JoTubeCast";
    private static final Object LOCK = new Object();

    private static ServerSocket sServer;
    private static volatile String sUpstream;
    private static volatile String sContentType;
    private static int sGeneration;

    private CastRelay() {}

    /** Starts the relay (if needed) for this stream and returns the URL the receiver should load, or null. */
    public static String serve(String upstreamUrl, String contentType) {
        synchronized (LOCK) {
            try {
                String host = findLocalAddress();
                if (host == null) {
                    Log.e(TAG, "Relay: no Wi-Fi address");
                    return null;
                }
                if (sServer == null || sServer.isClosed()) {
                    sServer = new ServerSocket(0);
                    final ServerSocket server = sServer;
                    Thread acceptor = new Thread(() -> acceptLoop(server), "CastRelay");
                    acceptor.setDaemon(true);
                    acceptor.start();
                }
                sUpstream = upstreamUrl;
                sContentType = contentType;
                sGeneration++;
                String url = "http://" + host + ":" + sServer.getLocalPort() + "/stream?g=" + sGeneration;
                Log.d(TAG, "Relay: " + url);
                return url;
            } catch (IOException e) {
                Log.e(TAG, "Relay start failed: " + e);
                return null;
            }
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            sUpstream = null;
            if (sServer != null) {
                try {
                    sServer.close();
                } catch (IOException ignored) {
                }
                sServer = null;
            }
        }
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

    private static void acceptLoop(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                final Socket socket = server.accept();
                Thread worker = new Thread(() -> handle(socket), "CastRelayConn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private static void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) {
                return;
            }
            String range = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.US).startsWith("range:")) {
                    range = line.substring(6).trim();
                }
            }
            String method = requestLine.split(" ")[0];
            OutputStream out = s.getOutputStream();

            if ("OPTIONS".equals(method)) {
                writeHead(out, "204 No Content", null, null, null, -1);
                return;
            }

            String upstream = sUpstream;
            if (upstream == null) {
                writeHead(out, "404 Not Found", null, null, null, 0);
                return;
            }

            OkHttpClient client = OkHttpManager.instance().getClient();
            // YouTube ties a URL to the client that got it: try the app's own identity first, then the usual ones
            String[][] identities = {
                    {DefaultHeaders.APP_USER_AGENT, null},
                    {"com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip", null},
                    {"com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)", null},
                    {"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36", "https://www.youtube.com"},
            };
            Response response = null;
            for (int i = 0; i < identities.length; i++) {
                Request.Builder builder = new Request.Builder().url(upstream).header("User-Agent", identities[i][0]);
                if (identities[i][1] != null) {
                    builder.header("Origin", identities[i][1]).header("Referer", identities[i][1] + "/");
                }
                if (range != null) {
                    builder.header("Range", range);
                }
                if ("HEAD".equals(method)) {
                    builder.head();
                }
                if (response != null) {
                    response.close();
                }
                response = client.newCall(builder.build()).execute();
                Log.d(TAG, "Relay: " + method + " range=" + range + " identity#" + i + " -> upstream HTTP " + response.code());
                if (response.isSuccessful()) {
                    break;
                }
            }
            if (!response.isSuccessful()) {
                logWhy(client, upstream);
            }
            try (Response ignored = response) {
                ResponseBody body = response.body();
                String status = response.code() == 206 ? "206 Partial Content" : response.code() == 200 ? "200 OK"
                        : response.code() + " Error";
                long length = body != null ? body.contentLength() : -1;
                writeHead(out, status, sContentType, response.header("Content-Range"), "bytes", length);
                if (body != null && !"HEAD".equals(method) && response.isSuccessful()) {
                    InputStream stream = body.byteStream();
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                out.flush();
            }
        } catch (IOException e) {
            // the receiver closes the connection when it seeks or stops: normal
        } catch (Throwable e) {
            Log.e(TAG, "Relay error: " + e);
        }
    }

    private static void logWhy(OkHttpClient client, String upstream) {
        try {
            android.net.Uri uri = android.net.Uri.parse(upstream);
            String viaApp = "?";
            try (Response r = client.newCall(new Request.Builder().url("https://api64.ipify.org").build()).execute()) {
                viaApp = r.body() != null ? r.body().string() : "-";
            } catch (Throwable e) {
                viaApp = "err " + e;
            }
            String viaPlain = "?";
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://api64.ipify.org").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                viaPlain = new java.util.Scanner(c.getInputStream()).useDelimiter("\\A").next();
                c.disconnect();
            } catch (Throwable e) {
                viaPlain = "err " + e;
            }
            Log.d(TAG, "Relay 403: url ip=" + uri.getQueryParameter("ip") + " c=" + uri.getQueryParameter("c")
                    + " pot=" + (uri.getQueryParameter("pot") != null) + " | the app's HTTP client leaves from " + viaApp
                    + " | plain phone network leaves from " + viaPlain);
        } catch (Throwable e) {
            Log.d(TAG, "Relay 403 diagnostics failed: " + e);
        }
    }

    private static void writeHead(OutputStream out, String status, String type, String contentRange, String ranges, long length)
            throws IOException {
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Headers: Range\r\n");
        head.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n");
        if (type != null) {
            head.append("Content-Type: ").append(type).append("\r\n");
        }
        if (ranges != null) {
            head.append("Accept-Ranges: ").append(ranges).append("\r\n");
        }
        if (contentRange != null) {
            head.append("Content-Range: ").append(contentRange).append("\r\n");
        }
        if (length >= 0) {
            head.append("Content-Length: ").append(length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
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
}
