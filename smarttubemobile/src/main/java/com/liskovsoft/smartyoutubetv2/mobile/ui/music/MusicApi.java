package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal YouTube Music client, written from the public protocol (the same requests the YouTube Music web app
 * sends). Anonymous: no account, no cookies, nothing of the user's is sent. The code is our own; the endpoint, the
 * client names, the browse ids and the search filter values are protocol constants the apps themselves send.
 * The request/response shapes were checked against ytmusicapi (MIT) as a map; no code was copied from GPL clients.<br/>
 * Everything here is blocking: call it from a background thread. Only metadata: the streams still come from
 * the SmartTube core (one format fetch at a time, never cancelled half-way).
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

    public static final int SONG = 0, ARTIST = 1, ALBUM = 2, PLAYLIST = 3, MIX = 4;

    public static final class Item {
        public int type = SONG;
        public String title;
        public String subtitle;
        public String author; // first artist for songs
        public String thumb;
        public String videoId;
        public String browseId;
        public String playlistId; // a mix / radio to start (MIX)
        public boolean wide; // a 16:9 picture (videos), otherwise square
    }

    /** A row of the home page: list rows ("Snabbval") or cards. */
    public static final class Shelf {
        public String title;
        public String strapline; // the small line above the title
        public boolean listRows; // song rows in columns of four, otherwise cards
        public final List<Item> items = new ArrayList<>();
    }

    public static final class Chip {
        public String title;
        public String browseId;
        public String params;
    }

    public static final class Home {
        public final List<Chip> chips = new ArrayList<>();
        public final List<Shelf> shelves = new ArrayList<>();
        public String continuation;
    }

    public static final class SearchResult {
        public Item top; // the big card ("Toppresultat"), may be null
        public final List<Item> items = new ArrayList<>();
    }

    /** An album or a playlist with its tracks. */
    public static final class Page {
        public String title;
        public String subtitle;
        public String secondSubtitle;
        public String thumb;
        public final List<Item> tracks = new ArrayList<>();
    }

    public static final class LyricLine {
        public long startMs;
        public String text;
    }

    public static final class Lyrics {
        public final List<LyricLine> lines = new ArrayList<>(); // timed (may be empty)
        public String plain; // when not timed
        public String source;

        public boolean isTimed() {
            return !lines.isEmpty();
        }
    }

    private static final String ENDPOINT = "https://music.youtube.com/youtubei/v1/";
    private static final String CLIENT_VERSION = "1.20250915.03.00";
    private static final String MOBILE_CLIENT_VERSION = "7.21.50"; // ANDROID_MUSIC: the only one that returns timed lyrics
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private MusicApi() {
    }

    ///////////////////// search

    public static List<Item> search(String query, Filter filter) throws Exception {
        JSONObject body = baseBody();
        body.put("query", query);
        if (filter != null) {
            body.put("params", filter.params);
        }
        return parse(post("search", body, null));
    }

    /** Without a filter, like the YouTube Music app: the top result card, then everything mixed. */
    public static SearchResult searchAll(String query) throws Exception {
        JSONObject body = baseBody();
        body.put("query", query);
        JSONObject root = post("search", body, null);

        SearchResult result = new SearchResult();
        List<JSONObject> cards = new ArrayList<>();
        collect(root, "musicCardShelfRenderer", cards, 0);
        if (!cards.isEmpty()) {
            result.top = cardToItem(cards.get(0));
        }

        Set<String> seen = new HashSet<>();
        if (result.top != null) {
            seen.add(key(result.top));
        }
        for (Item item : parse(root)) {
            if (seen.add(key(item))) {
                result.items.add(item);
            }
        }
        return result;
    }

    private static String key(Item item) {
        return item.type + ":" + (item.videoId != null ? item.videoId : item.browseId);
    }

    /** What the user may be typing ("clair obs" -> "clair obscur expedition 33"). */
    public static List<String> suggestions(String input) throws Exception {
        JSONObject body = baseBody();
        body.put("input", input);
        JSONObject root = post("music/get_search_suggestions", body, null);
        List<JSONObject> renderers = new ArrayList<>();
        collect(root, "searchSuggestionRenderer", renderers, 0);
        List<String> result = new ArrayList<>();
        for (JSONObject r : renderers) {
            String text = joinRuns(r.optJSONObject("suggestion"));
            if (text != null && !text.isEmpty() && !result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

    ///////////////////// browse: albums, playlists, home

    /** The tracks of an album / playlist ("MPREb_..." or "VL..."). */
    public static List<Item> browse(String browseId) throws Exception {
        return page(browseId).tracks;
    }

    public static Page page(String browseId) throws Exception {
        JSONObject body = baseBody();
        body.put("browseId", browseId);
        JSONObject root = post("browse", body, null);

        Page page = new Page();
        List<JSONObject> headers = new ArrayList<>();
        collect(root, "musicResponsiveHeaderRenderer", headers, 0);
        if (headers.isEmpty()) {
            collect(root, "musicDetailHeaderRenderer", headers, 0);
        }
        if (!headers.isEmpty()) {
            JSONObject header = headers.get(0);
            page.title = joinRuns(header.optJSONObject("title"));
            String strapline = joinRuns(header.optJSONObject("straplineTextOne"));
            String subtitle = joinRuns(header.optJSONObject("subtitle"));
            page.subtitle = strapline != null && !strapline.isEmpty()
                    ? strapline + (subtitle != null && !subtitle.isEmpty() ? " • " + subtitle : "") : subtitle;
            page.secondSubtitle = joinRuns(header.optJSONObject("secondSubtitle"));
            page.thumb = thumb(header.optJSONObject("thumbnail"));
        }

        for (Item item : parse(root)) {
            if (item.type == SONG && item.videoId != null) {
                page.tracks.add(item);
            }
        }
        // Album tracks have no pictures of their own: the album cover
        for (Item track : page.tracks) {
            if (track.thumb == null) {
                track.thumb = page.thumb;
            }
        }
        return page;
    }

    /** The YouTube Music start page; {@code chip} = one of its mood chips (null = all). */
    public static Home home(Chip chip) throws Exception {
        JSONObject body = baseBody();
        body.put("browseId", chip != null && chip.browseId != null ? chip.browseId : "FEmusic_home");
        if (chip != null && chip.params != null) {
            body.put("params", chip.params);
        }
        JSONObject root = post("browse", body, null);

        Home home = new Home();
        JSONObject sectionList = first(root, "sectionListRenderer");
        if (sectionList == null) {
            return home;
        }

        List<JSONObject> chips = new ArrayList<>();
        collect(sectionList.optJSONObject("header"), "chipCloudChipRenderer", chips, 0);
        for (JSONObject c : chips) {
            Chip item = new Chip();
            item.title = joinRuns(c.optJSONObject("text"));
            JSONObject browse = path(c, "navigationEndpoint", "browseEndpoint");
            if (browse != null) {
                item.browseId = browse.optString("browseId", null);
                item.params = browse.optString("params", null);
            }
            if (item.title != null && item.params != null) {
                home.chips.add(item);
            }
        }

        parseShelves(sectionList.optJSONArray("contents"), home.shelves);
        home.continuation = continuation(sectionList);
        return home;
    }

    /** More rows of the home page. */
    public static Home homeMore(String continuation) throws Exception {
        JSONObject body = baseBody();
        String encoded = URLEncoder.encode(continuation, "UTF-8");
        JSONObject root = post("browse", body, "&ctoken=" + encoded + "&continuation=" + encoded + "&type=next");
        Home home = new Home();
        JSONObject list = path(root, "continuationContents", "sectionListContinuation");
        if (list != null) {
            parseShelves(list.optJSONArray("contents"), home.shelves);
            home.continuation = continuation(list);
        }
        return home;
    }

    private static String continuation(JSONObject sectionList) {
        JSONArray continuations = sectionList.optJSONArray("continuations");
        JSONObject first = continuations != null ? continuations.optJSONObject(0) : null;
        JSONObject data = first != null ? first.optJSONObject("nextContinuationData") : null;
        return data != null ? data.optString("continuation", null) : null;
    }

    private static void parseShelves(JSONArray contents, List<Shelf> out) {
        for (int i = 0; contents != null && i < contents.length(); i++) {
            JSONObject section = contents.optJSONObject(i);
            if (section == null) {
                continue;
            }
            JSONObject carousel = section.optJSONObject("musicCarouselShelfRenderer");
            if (carousel == null) {
                carousel = section.optJSONObject("musicImmersiveCarouselShelfRenderer");
            }
            if (carousel == null) {
                continue;
            }

            Shelf shelf = new Shelf();
            JSONObject header = path(carousel, "header", "musicCarouselShelfBasicHeaderRenderer");
            if (header == null) {
                header = path(carousel, "header", "musicImmersiveCarouselShelfBasicHeaderRenderer");
            }
            if (header != null) {
                shelf.title = joinRuns(header.optJSONObject("title"));
                shelf.strapline = joinRuns(header.optJSONObject("strapline"));
            }

            JSONArray items = carousel.optJSONArray("contents");
            int listRows = 0;
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject entry = items.optJSONObject(j);
                if (entry == null) {
                    continue;
                }
                Item item = null;
                if (entry.has("musicTwoRowItemRenderer")) {
                    item = twoRowToItem(entry.optJSONObject("musicTwoRowItemRenderer"));
                } else if (entry.has("musicResponsiveListItemRenderer")) {
                    item = toItem(entry.optJSONObject("musicResponsiveListItemRenderer"));
                    if (item != null) {
                        listRows++;
                    }
                }
                if (item != null) {
                    shelf.items.add(item);
                }
            }
            shelf.listRows = listRows > 0 && listRows * 2 >= shelf.items.size();
            if (shelf.title == null) {
                shelf.title = shelf.strapline;
                shelf.strapline = null;
            }
            if (!shelf.items.isEmpty() && shelf.title != null) {
                out.add(shelf);
            }
        }
    }

    ///////////////////// watch: radio, mixes, lyrics

    /** The songs of a mix ("RDCLAK...", "RDAMPL...") or a song's radio ("RDAMVM" + videoId). */
    public static List<Item> watchPlaylist(String videoId, String playlistId) throws Exception {
        JSONObject body = baseBody();
        body.put("enablePersistentPlaylistPanel", true);
        body.put("isAudioOnly", true);
        body.put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL");
        if (videoId != null) {
            body.put("videoId", videoId);
        }
        if (playlistId != null) {
            body.put("playlistId", playlistId);
        }
        if (playlistId != null && playlistId.startsWith("RDAMVM")) {
            body.put("params", "wAEB"); // radio
        }
        JSONObject root = post("next", body, null);

        List<JSONObject> renderers = new ArrayList<>();
        collect(root, "playlistPanelVideoRenderer", renderers, 0);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JSONObject r : renderers) {
            if (r.has("unplayableText")) {
                continue;
            }
            Item item = new Item();
            item.videoId = r.optString("videoId", null);
            item.title = joinRuns(r.optJSONObject("title"));
            JSONObject byline = r.optJSONObject("longBylineText");
            if (byline == null) {
                byline = r.optJSONObject("shortBylineText");
            }
            item.subtitle = joinRuns(byline);
            item.author = firstRunText(byline);
            item.thumb = thumbList(r.optJSONObject("thumbnail"));
            if (item.videoId != null && item.title != null && seen.add(item.videoId)) {
                items.add(item);
            }
        }
        return items;
    }

    /** A song's radio: the song first, then similar ones (like "Starta radio" in YouTube Music). */
    public static List<Item> radio(String videoId) throws Exception {
        return watchPlaylist(videoId, "RDAMVM" + videoId);
    }

    /**
     * The lyrics of a song: YouTube Music's own (timed when it has them), else LRCLIB (an open lyrics database).
     * Null when nobody has them.
     */
    public static Lyrics lyrics(String videoId, String title, String artist, long durationMs) {
        try {
            Lyrics own = youTubeLyrics(videoId);
            if (own != null) {
                return own;
            }
        } catch (Exception e) {
            android.util.Log.w("MusicApi", "YouTube lyrics: " + e.getMessage());
        }
        try {
            return LrcLib.find(title, artist, durationMs);
        } catch (Exception e) {
            android.util.Log.w("MusicApi", "LRCLIB: " + e.getMessage());
            return null;
        }
    }

    private static Lyrics youTubeLyrics(String videoId) throws Exception {
        JSONObject body = baseBody();
        body.put("videoId", videoId);
        body.put("isAudioOnly", true);
        JSONObject root = post("next", body, null);

        String lyricsId = null;
        List<JSONObject> tabs = new ArrayList<>();
        collect(root, "tabRenderer", tabs, 0);
        for (JSONObject tab : tabs) {
            JSONObject browse = path(tab, "endpoint", "browseEndpoint");
            if (browse != null && pageType(browse).contains("LYRICS")) {
                lyricsId = browse.optString("browseId", null);
            }
        }
        if (lyricsId == null) {
            return null;
        }

        // Timed lines: only the Android app gets them
        try {
            JSONObject mobile = baseBody();
            mobile.getJSONObject("context").getJSONObject("client")
                    .put("clientName", "ANDROID_MUSIC").put("clientVersion", MOBILE_CLIENT_VERSION);
            mobile.put("browseId", lyricsId);
            JSONObject timed = post("browse", mobile, null);
            JSONObject data = first(timed, "timedLyricsModel");
            JSONObject lyricsData = data != null ? data.optJSONObject("lyricsData") : null;
            JSONArray raw = lyricsData != null ? lyricsData.optJSONArray("timedLyricsData") : null;
            if (raw != null && raw.length() > 0) {
                Lyrics lyrics = new Lyrics();
                lyrics.source = lyricsData.optString("sourceMessage", null);
                StringBuilder plain = new StringBuilder();
                for (int i = 0; i < raw.length(); i++) {
                    JSONObject line = raw.optJSONObject(i);
                    if (line == null) {
                        continue;
                    }
                    String text = line.optString("lyricLine", "");
                    plain.append(text).append('\n');
                    JSONObject cue = line.optJSONObject("cueRange");
                    if (cue != null) {
                        LyricLine l = new LyricLine();
                        l.startMs = Long.parseLong(cue.optString("startTimeMilliseconds", "0"));
                        l.text = text;
                        lyrics.lines.add(l);
                    }
                }
                if (lyrics.lines.size() < raw.length()) {
                    lyrics.lines.clear(); // not all timed: show as text
                    lyrics.plain = plain.toString().trim();
                }
                return lyrics;
            }
        } catch (Exception e) {
            android.util.Log.w("MusicApi", "timed lyrics: " + e.getMessage());
        }

        JSONObject web = baseBody();
        web.put("browseId", lyricsId);
        JSONObject root2 = post("browse", web, null);
        JSONObject shelf = first(root2, "musicDescriptionShelfRenderer");
        if (shelf == null) {
            return null;
        }
        Lyrics lyrics = new Lyrics();
        lyrics.plain = joinRuns(shelf.optJSONObject("description"));
        lyrics.source = joinRuns(shelf.optJSONObject("footer"));
        return lyrics.plain != null && !lyrics.plain.isEmpty() ? lyrics : null;
    }

    ///////////////////// http

    private static JSONObject baseBody() throws JSONException {
        Locale locale = Locale.getDefault();
        JSONObject client = new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", locale.getLanguage().isEmpty() ? "sv" : locale.getLanguage())
                .put("gl", locale.getCountry().isEmpty() ? "SE" : locale.getCountry());
        return new JSONObject().put("context", new JSONObject().put("client", client));
    }

    private static JSONObject post(String method, JSONObject body, String extraQuery) throws Exception {
        String url = ENDPOINT + method + "?prettyPrint=false" + (extraQuery != null ? extraQuery : "");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
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
                android.util.Log.e("MusicApi", "HTTP " + c.getResponseCode() + " for " + method);
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(readAll(in));
            }
        } finally {
            c.disconnect();
        }
    }

    static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toString("UTF-8");
    }

    ///////////////////// parsing (defensive: the layout changes now and then)

    private static List<Item> parse(JSONObject root) {
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

    /** The first object under {@code key}, depth first. */
    private static JSONObject first(Object node, String key) {
        List<JSONObject> found = new ArrayList<>();
        collect(node, key, found, 0);
        return found.isEmpty() ? null : found.get(0);
    }

    private static JSONObject path(JSONObject node, String... keys) {
        JSONObject current = node;
        for (String key : keys) {
            if (current == null) {
                return null;
            }
            current = current.optJSONObject(key);
        }
        return current;
    }

    private static void collect(Object node, String key, List<JSONObject> out, int depth) {
        if (node == null || depth > 40) {
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
            Iterator<String> keys = obj.keys();
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
        if (r == null) {
            return null;
        }
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
        for (int col = 1; col < columns.length(); col++) {
            JSONArray subRuns = runs(columns.optJSONObject(col));
            if (subRuns == null || subRuns.length() == 0) {
                continue;
            }
            if (subtitle.length() > 0) {
                subtitle.append(" • ");
            }
            for (int i = 0; i < subRuns.length(); i++) {
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
            if (!applyBrowse(item, browse)) {
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

    /** @return false for pages we can't show (podcasts, profiles...) */
    private static boolean applyBrowse(Item item, JSONObject browse) {
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
            return false;
        }
        return true;
    }

    /** A card of the home page (album, playlist, mix, artist, song or video). */
    private static Item twoRowToItem(JSONObject r) {
        if (r == null) {
            return null;
        }
        Item item = new Item();
        item.title = joinRuns(r.optJSONObject("title"));
        item.subtitle = joinRuns(r.optJSONObject("subtitle"));
        item.author = artistOf(r.optJSONObject("subtitle"));
        item.thumb = thumb(path(r, "thumbnailRenderer"));
        item.wide = r.optString("aspectRatio", "").contains("16_9");
        if (item.title == null) {
            return null;
        }

        JSONObject nav = r.optJSONObject("navigationEndpoint");
        JSONObject watch = nav != null ? nav.optJSONObject("watchEndpoint") : null;
        JSONObject watchPlaylist = nav != null ? nav.optJSONObject("watchPlaylistEndpoint") : null;
        JSONObject browse = nav != null ? nav.optJSONObject("browseEndpoint") : null;
        if (browse != null) {
            if (!applyBrowse(item, browse)) {
                return null;
            }
            return item.browseId != null ? item : null;
        }
        if (watchPlaylist != null) {
            item.type = MIX;
            item.playlistId = watchPlaylist.optString("playlistId", null);
            return item.playlistId != null ? item : null;
        }
        if (watch != null) {
            item.videoId = watch.optString("videoId", null);
            String playlistId = watch.optString("playlistId", null);
            if (item.videoId == null && playlistId != null) {
                item.type = MIX;
                item.playlistId = playlistId;
                return item;
            }
            return item.videoId != null ? item : null;
        }
        return null;
    }

    /** The big "top result" card of a search. */
    private static Item cardToItem(JSONObject card) {
        Item item = new Item();
        JSONObject title = card.optJSONObject("title");
        item.title = joinRuns(title);
        item.subtitle = joinRuns(card.optJSONObject("subtitle"));
        item.author = artistOf(card.optJSONObject("subtitle"));
        item.thumb = thumb(card.optJSONObject("thumbnail"));
        if (item.title == null) {
            return null;
        }

        JSONObject nav = null;
        JSONArray titleRuns = title != null ? title.optJSONArray("runs") : null;
        if (titleRuns != null && titleRuns.length() > 0) {
            nav = titleRuns.optJSONObject(0).optJSONObject("navigationEndpoint");
        }
        if (nav == null) {
            nav = card.optJSONObject("onTap");
        }
        JSONObject browse = nav != null ? nav.optJSONObject("browseEndpoint") : null;
        JSONObject watch = nav != null ? nav.optJSONObject("watchEndpoint") : null;
        if (browse != null) {
            if (!applyBrowse(item, browse)) {
                return null;
            }
            if (item.type == ARTIST && item.author == null) {
                item.author = item.title;
            }
            return item.browseId != null ? item : null;
        }
        if (watch != null) {
            item.videoId = watch.optString("videoId", null);
            return item.videoId != null ? item : null;
        }
        return null;
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

    /** All runs of a text, joined ("Album • Lorien Testard"). */
    static String joinRuns(JSONObject text) {
        if (text == null) {
            return null;
        }
        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) {
            String simple = text.optString("simpleText", null);
            return simple != null ? simple : null;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                result.append(run.optString("text", ""));
            }
        }
        return result.toString();
    }

    private static String firstRunText(JSONObject text) {
        JSONArray runs = text != null ? text.optJSONArray("runs") : null;
        JSONObject run = runs != null ? runs.optJSONObject(0) : null;
        return run != null ? run.optString("text", null) : null;
    }

    /** The first run that links to an artist. */
    private static String artistOf(JSONObject text) {
        JSONArray runs = text != null ? text.optJSONArray("runs") : null;
        for (int i = 0; runs != null && i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            JSONObject browse = run != null ? path(run, "navigationEndpoint", "browseEndpoint") : null;
            if (browse != null && pageType(browse).contains("ARTIST")) {
                return run.optString("text", null);
            }
        }
        return null;
    }

    private static String thumb(JSONObject thumbnail) {
        if (thumbnail == null) {
            return null;
        }
        JSONObject renderer = thumbnail.optJSONObject("musicThumbnailRenderer");
        if (renderer == null) {
            renderer = thumbnail.optJSONObject("croppedSquareThumbnailRenderer");
        }
        return thumbList(renderer != null ? renderer.optJSONObject("thumbnail") : thumbnail);
    }

    private static String thumbList(JSONObject thumbnail) {
        JSONArray thumbs = thumbnail != null ? thumbnail.optJSONArray("thumbnails") : null;
        if (thumbs == null || thumbs.length() == 0) {
            return null;
        }
        String url = thumbs.optJSONObject(thumbs.length() - 1).optString("url", null);
        if (url == null) {
            return null;
        }
        if (url.startsWith("//")) {
            url = "https:" + url;
        }
        // googleusercontent pictures can be asked bigger (the list ones are 60 px)
        return url.contains("googleusercontent.com") ? url.replaceAll("=w\\d+-h\\d+.*$", "=w544-h544") : url;
    }
}
