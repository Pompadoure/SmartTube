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

    /** Starts the queue as sound only, from {@code index}. */
    public static void playQueue(Activity activity, List<Video> queue, int index) {
        if (queue == null || index < 0 || index >= queue.size()) {
            return;
        }

        Video start = queue.get(index);
        Playlist playlist = Playlist.instance();
        playlist.clear();
        playlist.addAll(new ArrayList<>(queue));
        playlist.setCurrent(start);

        PlaybackActivity.setMusicContext(true);
        PlaybackActivity.setAudioModeForNextPlayback(true);
        PlaybackPresenter.instance(activity).openVideo(start);
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
