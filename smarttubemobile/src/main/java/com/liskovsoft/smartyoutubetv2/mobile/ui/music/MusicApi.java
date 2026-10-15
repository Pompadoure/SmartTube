package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal YouTube Music search/browse, written from the public protocol (the same requests the YouTube Music web app
 * sends). Anonymous: no account, no cookies, nothing of the user's is sent. Written from scratch (no code taken from
 * other projects).
 */
public final class MusicApi {
    public enum Filter {
        SONGS("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"),
        VIDEOS("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"),
        ALBUMS("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"),
        ARTISTS("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"),
        PLAYLISTS("EgeKAQQoAEABagoQAxAEEAoQCRAF");

        final String params;

        Filter(String params) {
            this.params = params;
        }
    }

    public static final int SONG = 0, ARTIST = 1, ALBUM = 2, PLAYLIST = 3;

    public static final class Item {
        public int type = SONG;
        public String title;
        public String subtitle;
        public String author; // first artist for songs
        public String thumb;
        public String videoId;
        public String browseId;
    }

    private static final String ENDPOINT = "https://music.youtube.com/youtubei/v1/";
    private static final String CLIENT_VERSION = "1.20250915.03.00";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private MusicApi() {
    }

    /** Blocking: call from a background thread. */
    public static List<Item> search(String query, Filter filter) throws Exception {
        JSONObject body = baseBody();
        body.put("query", query);
        if (filter != null) {
            body.put("params", filter.params);
        }
        return parse(post("search", body), filter == Filter.SONGS || filter == Filter.VIDEOS);
    }

    /** Blocking: the tracks of an album / playlist ("MPREb_..." or "VL..."). */
    public static List<Item> browse(String browseId) throws Exception {
        JSONObject body = baseBody();
        body.put("browseId", browseId);
        List<Item> items = parse(post("browse", body), true);
        List<Item> songs = new ArrayList<>();
        for (Item item : items) {
            if (item.type == SONG && item.videoId != null) {
                songs.add(item);
            }
        }
        return songs;
    }

    private static JSONObject baseBody() throws JSONException {
        JSONObject client = new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "en")
                .put("gl", java.util.Locale.getDefault().getCountry().isEmpty() ? "SE" : java.util.Locale.getDefault().getCountry());
        return new JSONObject().put("context", new JSONObject().put("client", client));
    }

    private static JSONObject post(String method, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT + method + "?prettyPrint=false").openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Origin", "https://music.youtube.com");
            c.setRequestProperty("Referer", "https://music.youtube.com/");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                return new JSONObject(buf.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    ///////////////////// parsing (defensive: the layout changes now and then)

    private static List<Item> parse(JSONObject root, boolean songsExpected) {
        List<JSONObject> renderers = new ArrayList<>();
        collect(root, "musicResponsiveListItemRenderer", renderers, 0);

        List<Item> items = new ArrayList<>();
        for (JSONObject r : renderers) {
            Item item = toItem(r);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private static void collect(Object node, String key, List<JSONObject> out, int depth) {
        if (depth > 40) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has(key)) {
                JSONObject found = obj.optJSONObject(key);
                if (found != null) {
                    out.add(found);
                }
                return; // no nested lists inside an item
            }
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                collect(obj.opt(keys.next()), key, out, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collect(array.opt(i), key, out, depth + 1);
            }
        }
    }

    private static Item toItem(JSONObject r) {
        JSONArray columns = r.optJSONArray("flexColumns");
        if (columns == null || columns.length() == 0) {
            return null;
        }

        JSONArray titleRuns = runs(columns.optJSONObject(0));
        if (titleRuns == null || titleRuns.length() == 0) {
            return null;
        }

        Item item = new Item();
        item.title = titleRuns.optJSONObject(0).optString("text", null);
        if (item.title == null) {
            return null;
        }

        StringBuilder subtitle = new StringBuilder();
        String firstArtist = null;
        if (columns.length() > 1) {
            JSONArray subRuns = runs(columns.optJSONObject(1));
            for (int i = 0; subRuns != null && i < subRuns.length(); i++) {
                JSONObject run = subRuns.optJSONObject(i);
                String text = run != null ? run.optString("text", "") : "";
                subtitle.append(text);
                if (firstArtist == null && run != null && run.optJSONObject("navigationEndpoint") != null
                        && text.length() > 0 && !" • ".equals(text)) {
                    JSONObject browse = run.optJSONObject("navigationEndpoint").optJSONObject("browseEndpoint");
                    if (browse != null && pageType(browse).contains("ARTIST")) {
                        firstArtist = text;
                    }
                }
            }
        }
        item.subtitle = subtitle.toString();
        item.author = firstArtist;

        item.thumb = thumb(r.optJSONObject("thumbnail"));

        // videoId: playlist item data, the title run, or the play button of the overlay
        JSONObject data = r.optJSONObject("playlistItemData");
        if (data != null) {
            item.videoId = data.optString("videoId", null);
        }
        if (item.videoId == null) {
            JSONObject watch = titleRuns.optJSONObject(0).optJSONObject("navigationEndpoint");
            watch = watch != null ? watch.optJSONObject("watchEndpoint") : null;
            if (watch != null) {
                item.videoId = watch.optString("videoId", null);
            }
        }

        JSONObject nav = r.optJSONObject("navigationEndpoint");
        JSONObject browse = nav != null ? nav.optJSONObject("browseEndpoint") : null;
        if (browse != null) {
            item.browseId = browse.optString("browseId", null);
            String type = pageType(browse);
            if (type.contains("ARTIST")) {
                item.type = ARTIST;
            } else if (type.contains("ALBUM") || type.contains("AUDIOBOOK")) {
                item.type = ALBUM;
            } else if (type.contains("PLAYLIST")) {
                item.type = PLAYLIST;
                if (item.browseId != null && !item.browseId.startsWith("VL")) {
                    item.browseId = "VL" + item.browseId;
                }
            } else if (item.videoId == null) {
                return null; // podcasts, profiles, ...
            }
        }

        if (item.type == SONG && item.videoId == null) {
            return null;
        }
        if (item.type != SONG && item.browseId == null) {
            return null;
        }
        return item;
    }

    private static String pageType(JSONObject browse) {
        JSONObject configs = browse.optJSONObject("browseEndpointContextSupportedConfigs");
        JSONObject music = configs != null ? configs.optJSONObject("browseEndpointContextMusicConfig") : null;
        return music != null ? music.optString("pageType", "") : "";
    }

    private static JSONArray runs(JSONObject column) {
        if (column == null) {
            return null;
        }
        JSONObject renderer = column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        JSONObject text = renderer != null ? renderer.optJSONObject("text") : null;
        return text != null ? text.optJSONArray("runs") : null;
    }

    private static String thumb(JSONObject thumbnail) {
        if (thumbnail == null) {
            return null;
        }
        JSONObject renderer = thumbnail.optJSONObject("musicThumbnailRenderer");
        JSONObject inner = renderer != null ? renderer.optJSONObject("thumbnail") : null;
        JSONArray thumbs = inner != null ? inner.optJSONArray("thumbnails") : null;
        if (thumbs == null || thumbs.length() == 0) {
            return null;
        }
        String url = thumbs.optJSONObject(thumbs.length() - 1).optString("url", null);
        return url != null ? url.replaceAll("=w\\d+-h\\d+.*$", "=w400-h400") : null;
    }
}
