package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRCLIB (lrclib.net): an open, free database of song lyrics, many of them synced line by line. No key needed.
 * The fallback when YouTube Music has no lyrics for a song. Blocking: call from a background thread.
 */
final class LrcLib {
    private static final String BASE = "https://lrclib.net/api/";
    private static final String USER_AGENT = "JoTube (personal use; https://github.com/Pompadoure/SmartTube)";
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)$");

    private LrcLib() {
    }

    static MusicApi.Lyrics find(String title, String artist, long durationMs) throws Exception {
        if (title == null || title.trim().isEmpty()) {
            return null;
        }
        String cleanTitle = clean(title);
        JSONObject match = null;

        if (artist != null && !artist.isEmpty()) {
            String url = BASE + "get?track_name=" + enc(cleanTitle) + "&artist_name=" + enc(artist)
                    + (durationMs > 0 ? "&duration=" + (durationMs / 1000) : "");
            try {
                String body = get(url);
                if (body != null) {
                    match = new JSONObject(body);
                }
            } catch (Exception e) {
                match = null; // e.g. 400 without a duration: the search below
            }
        }

        if (match == null) {
            String body = get(BASE + "search?q=" + enc(cleanTitle + (artist != null ? " " + artist : "")));
            JSONArray results = body != null ? new JSONArray(body) : null;
            for (int i = 0; results != null && i < results.length() && match == null; i++) {
                JSONObject candidate = results.optJSONObject(i);
                if (candidate == null) {
                    continue;
                }
                double seconds = candidate.optDouble("duration", 0);
                boolean durationOk = durationMs <= 0 || seconds <= 0 || Math.abs(seconds * 1000 - durationMs) < 8000;
                if (durationOk && (has(candidate, "syncedLyrics") || has(candidate, "plainLyrics"))) {
                    match = candidate;
                }
            }
        }

        return match != null ? toLyrics(match) : null;
    }

    private static boolean has(JSONObject object, String key) {
        return !object.isNull(key) && !object.optString(key, "").trim().isEmpty();
    }

    private static MusicApi.Lyrics toLyrics(JSONObject json) {
        if (json.optBoolean("instrumental", false)) {
            return null;
        }
        MusicApi.Lyrics lyrics = new MusicApi.Lyrics();
        lyrics.source = "LRCLIB";
        if (has(json, "syncedLyrics")) {
            for (String raw : json.optString("syncedLyrics").split("\n")) {
                Matcher m = LINE.matcher(raw.trim());
                if (!m.matches()) {
                    continue;
                }
                long minutes = Long.parseLong(m.group(1));
                long seconds = Long.parseLong(m.group(2));
                String fraction = m.group(3);
                long ms = 0;
                if (fraction != null) {
                    ms = Long.parseLong(fraction);
                    ms = fraction.length() == 1 ? ms * 100 : fraction.length() == 2 ? ms * 10 : ms;
                }
                MusicApi.LyricLine line = new MusicApi.LyricLine();
                line.startMs = minutes * 60_000 + seconds * 1000 + ms;
                line.text = m.group(4).trim();
                lyrics.lines.add(line);
            }
        }
        if (lyrics.lines.isEmpty()) {
            if (!has(json, "plainLyrics")) {
                return null;
            }
            lyrics.plain = json.optString("plainLyrics").trim();
        }
        return lyrics;
    }

    /** "Song (Official Video) [HD]" -> "Song" */
    private static String clean(String title) {
        String result = title.replaceAll("(?i)[(\\[][^)\\]]*(official|video|audio|lyrics?|visualizer|hd|4k|remaster(ed)?)[^)\\]]*[)\\]]", "");
        return result.trim().isEmpty() ? title.trim() : result.trim();
    }

    private static String enc(String text) throws Exception {
        return URLEncoder.encode(text, "UTF-8");
    }

    /** @return the body, or null for 404 */
    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            int code = c.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                throw new IllegalStateException("HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                return MusicApi.readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }
}
