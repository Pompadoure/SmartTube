package com.liskovsoft.smartyoutubetv2.mobile.ui.podcast;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Podcasts by RSS: the subscriptions (just the feed URLs), the RSS fetching/parsing and the listening positions.
 * Everything stays on the phone.
 */
public final class PodcastRepository {
    private static final String PREFS = "jotube_podcasts";
    private static final String KEY_FEEDS = "feeds";
    private static final Map<String, Feed> sCache = new HashMap<>();

    private PodcastRepository() {
    }

    public static class Episode {
        public String guid;
        public String title;
        public String audioUrl;
        public String image;
        public String description;
        public String feedTitle;
        public String feedUrl;
        public long dateMs;
        public long durationMs;
    }

    public static class Feed {
        public String url;
        public String title;
        public String author;
        public String image;
        public final List<Episode> episodes = new ArrayList<>();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The subscribed feeds as last seen (title/image may be missing until the first fetch). */
    public static List<Feed> subscriptions(Context context) {
        List<Feed> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY_FEEDS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Feed cached = sCache.get(item.getString("url"));
                if (cached != null) {
                    result.add(cached);
                    continue;
                }
                Feed feed = new Feed();
                feed.url = item.getString("url");
                feed.title = item.optString("title", feed.url);
                feed.image = item.optString("image", null);
                result.add(feed);
            }
        } catch (Exception e) {
            // corrupt prefs: start over
        }
        return result;
    }

    public static boolean isSubscribed(Context context, String url) {
        for (Feed feed : subscriptions(context)) {
            if (feed.url.equals(url)) {
                return true;
            }
        }
        return false;
    }

    public static void subscribe(Context context, Feed feed) {
        sCache.put(feed.url, feed);
        List<Feed> feeds = subscriptions(context);
        boolean found = false;
        for (Feed existing : feeds) {
            if (existing.url.equals(feed.url)) {
                found = true;
            }
        }
        if (!found) {
            feeds.add(feed);
        }
        save(context, feeds);
    }

    public static void unsubscribe(Context context, String url) {
        sCache.remove(url);
        List<Feed> feeds = subscriptions(context);
        for (int i = feeds.size() - 1; i >= 0; i--) {
            if (feeds.get(i).url.equals(url)) {
                feeds.remove(i);
            }
        }
        save(context, feeds);
    }

    private static void save(Context context, List<Feed> feeds) {
        try {
            JSONArray array = new JSONArray();
            for (Feed feed : feeds) {
                JSONObject item = new JSONObject();
                item.put("url", feed.url);
                item.put("title", feed.title);
                item.put("image", feed.image);
                array.put(item);
            }
            prefs(context).edit().putString(KEY_FEEDS, array.toString()).apply();
        } catch (Exception e) {
            // nothing sensible to do
        }
    }

    ///////////////////// positions

    private static String positionKey(Episode episode) {
        return "pos_" + (episode.guid != null ? episode.guid : episode.audioUrl).hashCode();
    }

    private static String finishedKey(Episode episode) {
        return "done_" + (episode.guid != null ? episode.guid : episode.audioUrl).hashCode();
    }

    private static String durationKey(Episode episode) {
        return "dur_" + (episode.guid != null ? episode.guid : episode.audioUrl).hashCode();
    }

    public static long getPosition(Context context, Episode episode) {
        return prefs(context).getLong(positionKey(episode), 0);
    }

    /** Listened to the end (the last 30 s count as the end). */
    public static boolean isFinished(Context context, Episode episode) {
        return prefs(context).getBoolean(finishedKey(episode), false);
    }

    /** The length from the feed, else the one the player measured (some feeds have none). 0 if unknown. */
    public static long getDurationMs(Context context, Episode episode) {
        return episode.durationMs > 0 ? episode.durationMs : prefs(context).getLong(durationKey(episode), 0);
    }

    /** How far it was listened to, 0..1 (1 when finished, 0 when not started or the length is unknown). */
    public static float getProgress(Context context, Episode episode) {
        if (isFinished(context, episode)) {
            return 1f;
        }
        long position = getPosition(context, episode);
        long duration = getDurationMs(context, episode);
        return position > 0 && duration > 0 ? Math.min(1f, position / (float) duration) : 0f;
    }

    public static void savePosition(Context context, Episode episode, long positionMs, long durationMs) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (durationMs > 0) {
            editor.putLong(durationKey(episode), durationMs);
        }
        if (durationMs > 0 && positionMs >= durationMs - 30_000) {
            editor.remove(positionKey(episode)); // finished
            editor.putBoolean(finishedKey(episode), true);
        } else if (positionMs > 5_000) {
            editor.putLong(positionKey(episode), positionMs);
        }
        editor.apply();
    }

    /** The user starts a finished episode again: it counts as not finished until it ends again. */
    public static void clearFinished(Context context, Episode episode) {
        prefs(context).edit().remove(finishedKey(episode)).apply();
    }

    ///////////////////// RSS

    /** Blocking: call from a background thread. */
    public static Feed fetch(String feedUrl) throws Exception {
        String current = feedUrl;
        HttpURLConnection connection = null;
        for (int redirects = 0; redirects < 6; redirects++) {
            connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "JoTube/1.0 (podcast reader)");
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) {
                    throw new IOException("Redirect without location");
                }
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (code != 200) {
                connection.disconnect();
                throw new IOException("HTTP " + code);
            }
            break;
        }

        try (InputStream stream = connection.getInputStream()) {
            Feed feed = parse(stream);
            feed.url = feedUrl;
            for (Episode episode : feed.episodes) {
                episode.feedTitle = feed.title;
                episode.feedUrl = feedUrl;
                if (episode.image == null) {
                    episode.image = feed.image;
                }
            }
            synchronized (sCache) {
                sCache.put(feedUrl, feed);
            }
            return feed;
        } finally {
            connection.disconnect();
        }
    }

    static Feed parse(InputStream stream) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, null);

        Feed feed = new Feed();
        Episode item = null;
        boolean inImage = false;
        int event = parser.getEventType();

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("item".equals(name)) {
                    item = new Episode();
                } else if ("image".equals(name)) {
                    inImage = true;
                    String href = parser.getAttributeValue(null, "href"); // itunes:image has no namespace handling here
                    if (href != null) {
                        setImage(feed, item, href);
                    }
                } else if ("itunes:image".equals(name)) {
                    String href = parser.getAttributeValue(null, "href");
                    if (href != null) {
                        setImage(feed, item, href);
                    }
                } else if ("enclosure".equals(name) && item != null) {
                    String type = parser.getAttributeValue(null, "type");
                    if (item.audioUrl == null && (type == null || type.startsWith("audio") || type.startsWith("video"))) {
                        item.audioUrl = parser.getAttributeValue(null, "url");
                    }
                } else if ("title".equals(name)) {
                    String text = parser.nextText();
                    if (item != null) {
                        item.title = text;
                    } else if (feed.title == null && !inImage) {
                        feed.title = text;
                    }
                } else if ("url".equals(name) && inImage && item == null) {
                    String text = parser.nextText();
                    if (feed.image == null) {
                        feed.image = text;
                    }
                } else if ("guid".equals(name) && item != null) {
                    item.guid = parser.nextText();
                } else if ("pubDate".equals(name) && item != null) {
                    item.dateMs = parseDate(parser.nextText());
                } else if ("itunes:duration".equals(name) && item != null) {
                    item.durationMs = parseDuration(parser.nextText());
                } else if (("description".equals(name) || "itunes:summary".equals(name)) && item != null) {
                    String text = parser.nextText();
                    if (item.description == null) {
                        item.description = text;
                    }
                } else if (("itunes:author".equals(name) || "author".equals(name)) && item == null && feed.author == null) {
                    feed.author = parser.nextText();
                }
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("image".equals(name)) {
                    inImage = false;
                } else if ("item".equals(name) && item != null) {
                    if (item.audioUrl != null) {
                        if (item.guid == null) {
                            item.guid = item.audioUrl;
                        }
                        if (item.title == null) {
                            item.title = item.audioUrl;
                        }
                        if (feed.episodes.size() < 300) {
                            feed.episodes.add(item);
                        }
                    }
                    item = null;
                }
            }
            event = parser.next();
        }

        if (feed.title == null) {
            throw new IOException("Not a podcast feed");
        }

        Collections.sort(feed.episodes, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        return feed;
    }

    private static void setImage(Feed feed, Episode item, String href) {
        if (item != null) {
            item.image = href;
        } else if (feed.image == null) {
            feed.image = href;
        }
    }

    private static final String[] DATE_FORMATS = {
            "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss z", "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z"};

    private static long parseDate(String text) {
        if (text == null) {
            return 0;
        }
        for (String format : DATE_FORMATS) {
            try {
                Date date = new SimpleDateFormat(format, Locale.US).parse(text.trim());
                if (date != null) {
                    return date.getTime();
                }
            } catch (ParseException e) {
                // next format
            }
        }
        return 0;
    }

    /** "1:02:03", "35:10" or plain seconds. */
    private static long parseDuration(String text) {
        if (text == null) {
            return 0;
        }
        try {
            String[] parts = text.trim().split(":");
            long seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + (long) Double.parseDouble(part);
            }
            return seconds * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
