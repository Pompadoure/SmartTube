package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The user's own music playlists, kept on the phone (SharedPreferences, JSON). */
public final class MusicLibrary {
    private static final String PREFS = "jotube_music";
    private static final String KEY = "playlists";

    public static final class Track {
        public String videoId, title, author, thumb;

        public Video toVideo() {
            Video video = Video.from(videoId);
            video.title = title;
            video.author = author;
            video.cardImageUrl = thumb;
            return video;
        }
    }

    public static final class Playlist {
        public String id, name;
        public final List<Track> tracks = new ArrayList<>();
    }

    private MusicLibrary() {
    }

    public static synchronized List<Playlist> playlists(Context context) {
        List<Playlist> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Playlist p = new Playlist();
                p.id = o.getString("id");
                p.name = o.optString("name", "");
                JSONArray tracks = o.optJSONArray("tracks");
                for (int j = 0; tracks != null && j < tracks.length(); j++) {
                    JSONObject t = tracks.getJSONObject(j);
                    Track track = new Track();
                    track.videoId = t.getString("v");
                    track.title = t.optString("t", null);
                    track.author = t.optString("a", null);
                    track.thumb = t.optString("i", null);
                    p.tracks.add(track);
                }
                result.add(p);
            }
        } catch (Exception e) {
            // damaged data: start over with what could be read
        }
        return result;
    }

    public static synchronized Playlist find(Context context, String id) {
        for (Playlist p : playlists(context)) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    public static synchronized Playlist create(Context context, String name) {
        List<Playlist> all = playlists(context);
        Playlist p = new Playlist();
        p.id = UUID.randomUUID().toString();
        p.name = name.trim();
        all.add(p);
        save(context, all);
        return p;
    }

    public static synchronized void rename(Context context, String id, String name) {
        List<Playlist> all = playlists(context);
        for (Playlist p : all) {
            if (p.id.equals(id)) {
                p.name = name.trim();
            }
        }
        save(context, all);
    }

    public static synchronized void delete(Context context, String id) {
        List<Playlist> all = playlists(context);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        save(context, all);
    }

    /** @return false if the song was already in the playlist */
    public static synchronized boolean add(Context context, String playlistId, Video video) {
        if (video == null || video.videoId == null) {
            return false;
        }
        List<Playlist> all = playlists(context);
        for (Playlist p : all) {
            if (p.id.equals(playlistId)) {
                for (Track t : p.tracks) {
                    if (t.videoId.equals(video.videoId)) {
                        return false;
                    }
                }
                Track track = new Track();
                track.videoId = video.videoId;
                track.title = video.getTitle() != null ? video.getTitle().toString() : null;
                track.author = video.getAuthor();
                track.thumb = video.getCardImageUrl();
                p.tracks.add(track);
                save(context, all);
                return true;
            }
        }
        return false;
    }

    public static synchronized void removeTrack(Context context, String playlistId, String videoId) {
        List<Playlist> all = playlists(context);
        for (Playlist p : all) {
            if (p.id.equals(playlistId)) {
                for (int i = 0; i < p.tracks.size(); i++) {
                    if (p.tracks.get(i).videoId.equals(videoId)) {
                        p.tracks.remove(i);
                        break;
                    }
                }
            }
        }
        save(context, all);
    }

    private static void save(Context context, List<Playlist> all) {
        try {
            JSONArray array = new JSONArray();
            for (Playlist p : all) {
                JSONArray tracks = new JSONArray();
                for (Track t : p.tracks) {
                    tracks.put(new JSONObject().put("v", t.videoId).put("t", t.title).put("a", t.author).put("i", t.thumb));
                }
                array.put(new JSONObject().put("id", p.id).put("name", p.name).put("tracks", tracks));
            }
            prefs(context).edit().putString(KEY, array.toString()).apply();
        } catch (Exception e) {
            // keep the old data
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
