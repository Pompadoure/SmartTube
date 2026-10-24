package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.mobile.ui.playback.MediaPlaybackService;
import com.liskovsoft.smartyoutubetv2.mobile.ui.playback.PlaybackActivity;

import java.util.ArrayList;
import java.util.List;

/** Long press on a song: play, play next, queue, add to a playlist. And the helpers that start a queue. */
public final class MusicMenu {
    private MusicMenu() {
    }

    private static final java.util.concurrent.ExecutorService sExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static String sQueueTitle;
    private static int sRadioRequest;

    private static boolean sShuffle;
    private static List<Video> sUnshuffled; // the order before "Blanda", to go back to
    private static Integer sModeBeforeRepeat;

    public static boolean isShuffle() {
        return sShuffle;
    }

    /** Like YouTube Music: shuffles the songs after the playing one; off = their order from before. */
    public static void setShuffle(boolean on) {
        Playlist playlist = Playlist.instance();
        Video playing = playlist.getCurrent();
        List<Video> all = new ArrayList<>(playlist.getAll());
        int index = playing != null ? all.indexOf(playing) : -1;
        if (index < 0) {
            sShuffle = on;
            return;
        }
        List<Video> head = new ArrayList<>(all.subList(0, index + 1));
        List<Video> rest = new ArrayList<>(all.subList(index + 1, all.size()));
        if (on) {
            sUnshuffled = new ArrayList<>(rest);
            java.util.Collections.shuffle(rest);
        } else if (sUnshuffled != null) {
            List<Video> ordered = new ArrayList<>();
            for (Video video : sUnshuffled) {
                if (rest.contains(video)) {
                    ordered.add(video);
                }
            }
            for (Video video : rest) {
                if (!ordered.contains(video)) {
                    ordered.add(video); // added while shuffled
                }
            }
            rest = ordered;
            sUnshuffled = null;
        }
        sShuffle = on;
        head.addAll(rest);
        playlist.clear();
        playlist.addAll(head);
        playlist.setCurrent(playing);
        PlaybackActivity.refreshQueue();
    }

    public static boolean isRepeatOne(android.content.Context context) {
        return com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(context).getPlaybackMode()
                == com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_ONE;
    }

    /** Repeat the song (the player's playback mode "one"); off = the mode it had before. */
    public static void setRepeatOne(android.content.Context context, boolean on) {
        com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData data = com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(context);
        int one = com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_ONE;
        if (on) {
            if (data.getPlaybackMode() != one) {
                sModeBeforeRepeat = data.getPlaybackMode();
            }
            data.setPlaybackMode(one);
        } else {
            data.setPlaybackMode(sModeBeforeRepeat != null ? sModeBeforeRepeat
                    : com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants.PLAYBACK_MODE_ALL);
            sModeBeforeRepeat = null;
        }
    }

    /** The name of what plays ("GRUSVÄG – mix", an album), for the queue bar of the player. Null = a plain queue. */
    public static String queueTitle() {
        return sQueueTitle;
    }

    public static Video toVideo(MusicApi.Item item) {
        Video video = Video.from(item.videoId);
        video.title = item.title;
        video.author = item.author;
        video.secondTitle = item.subtitle;
        video.cardImageUrl = item.thumb;
        return video;
    }

    public static List<Video> toVideos(List<MusicApi.Item> items) {
        List<Video> result = new ArrayList<>();
        for (MusicApi.Item item : items) {
            if (item.videoId != null) {
                result.add(toVideo(item));
            }
        }
        return result;
    }

    /** Starts the queue as sound only, from {@code index}. */
    public static void playQueue(Activity activity, List<Video> queue, int index) {
        playQueue(activity, queue, index, null);
    }

    /** Like {@link #playQueue(Activity, List, int)}, with the name of what plays (album, mix). */
    public static void playQueue(Activity activity, List<Video> queue, int index, String title) {
        if (queue == null || index < 0 || index >= queue.size()) {
            return;
        }
        sQueueTitle = title;
        sRadioRequest++; // a radio still loading must not replace this queue
        sShuffle = false;
        sUnshuffled = null;

        Video start = queue.get(index);
        Playlist playlist = Playlist.instance();
        playlist.clear();
        playlist.addAll(new ArrayList<>(queue));
        playlist.setCurrent(start);

        PlaybackActivity.setMusicContext(true);
        PlaybackActivity.setAudioModeForNextPlayback(true);
        PlaybackPresenter.instance(activity).openVideo(start);
    }

    /**
     * Like YouTube Music: the song plays at once and its radio (similar songs) fills the queue behind it.
     * {@code alreadyPlaying}: the song plays already, only the songs after it change.
     */
    public static void startRadio(Activity activity, Video seed, boolean alreadyPlaying) {
        if (seed == null || seed.videoId == null) {
            return;
        }
        if (!alreadyPlaying) {
            List<Video> one = new ArrayList<>();
            one.add(seed);
            playQueue(activity, one, 0);
        }
        sQueueTitle = seed.getTitle() + " – mix";
        PlaybackActivity.refreshQueue();
        final int request = ++sRadioRequest;
        final String seedId = seed.videoId;
        final android.content.Context app = activity.getApplicationContext();
        sExecutor.execute(() -> {
            List<MusicApi.Item> radio;
            try {
                radio = MusicApi.radio(seedId);
            } catch (Exception e) {
                radio = null;
            }
            final List<MusicApi.Item> items = radio;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (request != sRadioRequest) {
                    return; // something else was started meanwhile
                }
                if (items == null || items.isEmpty()) {
                    Toast.makeText(app, "Kunde inte hämta radio", Toast.LENGTH_SHORT).show();
                    return;
                }
                Playlist playlist = Playlist.instance();
                Video playing = playlist.getCurrent();
                if (playing == null || !seedId.equals(playing.videoId)) {
                    return; // another song plays now
                }
                List<Video> all = new ArrayList<>(playlist.getAll());
                int index = all.indexOf(playing);
                if (index < 0) {
                    return;
                }
                List<Video> result = new ArrayList<>(all.subList(0, index + 1));
                for (MusicApi.Item item : items) {
                    if (item.videoId != null && !item.videoId.equals(seedId)) {
                        Video video = toVideo(item);
                        if (!result.contains(video)) {
                            result.add(video);
                        }
                    }
                }
                playlist.clear();
                playlist.addAll(result);
                playlist.setCurrent(playing);
                PlaybackActivity.refreshQueue();
            });
        });
    }

    /** Plays a mix or a playlist given as a playlist id ("RDCLAK...", "PL..."). */
    public static void playMix(Activity activity, String playlistId, String title) {
        if (playlistId == null) {
            return;
        }
        final int request = ++sRadioRequest;
        Toast.makeText(activity, "Hämtar " + (title != null ? title : "mixen") + "…", Toast.LENGTH_SHORT).show();
        sExecutor.execute(() -> {
            List<MusicApi.Item> items;
            try {
                items = MusicApi.watchPlaylist(null, playlistId);
            } catch (Exception e) {
                items = null;
            }
            final List<MusicApi.Item> result = items;
            activity.runOnUiThread(() -> {
                if (request != sRadioRequest || activity.isDestroyed()) {
                    return;
                }
                if (result == null || result.isEmpty()) {
                    Toast.makeText(activity, "Kunde inte hämta mixen", Toast.LENGTH_SHORT).show();
                    return;
                }
                playQueue(activity, toVideos(result), 0, title);
            });
        });
    }

    private static boolean isPlaying() {
        return Playlist.instance().getCurrent() != null && MediaPlaybackService.getPlayer() != null;
    }

    public static void show(Activity activity, Video video, String extraLabel, Runnable extraAction) {
        if (video == null || video.videoId == null) {
            return;
        }

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add("Spela");
        actions.add(() -> {
            List<Video> one = new ArrayList<>();
            one.add(video);
            playQueue(activity, one, 0);
        });

        labels.add("Spela nästa");
        actions.add(() -> {
            if (isPlaying()) {
                Playlist.instance().next(video);
                PlaybackActivity.refreshQueue();
                toast(activity, "Spelas näst");
            } else {
                List<Video> one = new ArrayList<>();
                one.add(video);
                playQueue(activity, one, 0);
            }
        });

        labels.add("Lägg i kön");
        actions.add(() -> {
            if (isPlaying()) {
                Playlist.instance().add(video);
                PlaybackActivity.refreshQueue();
                toast(activity, "Tillagd i kön");
            } else {
                List<Video> one = new ArrayList<>();
                one.add(video);
                playQueue(activity, one, 0);
            }
        });

        labels.add("Starta radio");
        actions.add(() -> {
            Video playing = Playlist.instance().getCurrent();
            startRadio(activity, video, isPlaying() && playing != null && video.videoId.equals(playing.videoId));
        });

        labels.add("Lägg till i spellista");
        actions.add(() -> chooseList(activity, video));

        if (video.channelId != null) {
            labels.add("Gå till artist");
            actions.add(() -> ChannelPresenter.instance(activity).openChannel(video));
        }

        if (extraLabel != null && extraAction != null) {
            labels.add(extraLabel);
            actions.add(extraAction);
        }

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(video.getTitle())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    public static void addToPlaylist(Activity activity, Video video) {
        if (video != null && video.videoId != null) {
            chooseList(activity, video);
        }
    }

    private static void chooseList(Activity activity, Video video) {
        List<MusicLibrary.Playlist> lists = MusicLibrary.playlists(activity);
        List<String> labels = new ArrayList<>();
        labels.add("Ny spellista...");
        for (MusicLibrary.Playlist p : lists) {
            labels.add(p.name + " (" + p.tracks.size() + ")");
        }

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Lägg till i spellista")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        askName(activity, "Ny spellista", "", name -> {
                            MusicLibrary.Playlist created = MusicLibrary.create(activity, name);
                            MusicLibrary.add(activity, created.id, video);
                            toast(activity, "Tillagd i " + created.name);
                        });
                    } else {
                        MusicLibrary.Playlist target = lists.get(which - 1);
                        boolean added = MusicLibrary.add(activity, target.id, video);
                        toast(activity, added ? "Tillagd i " + target.name : "Finns redan i " + target.name);
                    }
                })
                .show();
    }

    public interface NameCallback {
        void onName(String name);
    }

    public static void askName(Activity activity, String title, String initial, NameCallback callback) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(initial);
        input.setSelection(initial.length());
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Spara", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        callback.onName(name);
                    }
                })
                .setNegativeButton("Avbryt", null)
                .show();
    }

    private static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}
