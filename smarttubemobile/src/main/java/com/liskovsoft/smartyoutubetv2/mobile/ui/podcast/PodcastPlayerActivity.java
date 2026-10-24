package com.liskovsoft.smartyoutubetv2.mobile.ui.podcast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.music.QueueView;
import com.liskovsoft.smartyoutubetv2.mobile.ui.playback.MusicPlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The podcast player screen. JoTube: the same screen as the music player ({@link MusicPlayerView}): the cover with
 * the page in its color, pill buttons (share, speed, sleep timer), the progress, -10 s / previous / play / next /
 * +30 s and the queue at the bottom. So every change to the music player shows here too.
 */
public class PodcastPlayerActivity extends Activity {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ExoPlayer mPlayer;
    private MusicPlayerView mView;
    private PodcastRepository.Episode mShown;
    private Video mShownVideo;
    private boolean mDestroyed;
    private final Runnable mQueueListener = this::onQueueChanged;
    private boolean mSkipQueueRefresh;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!PodcastPlayer.isActive()) {
            finish();
            return;
        }
        mPlayer = PodcastPlayer.player(this);

        getWindow().setStatusBarColor(0x00000000);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(getColor(R.color.yt_bg));
        frame.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        mView = new MusicPlayerView(this, new Host());
        frame.addView(mView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        PodcastPlayer.setQueueListener(mQueueListener);
        update();
    }

    private static Video toVideo(PodcastRepository.Episode episode) {
        Video video = Video.from(episode.guid != null ? episode.guid : episode.audioUrl);
        video.title = episode.title;
        video.author = episode.feedTitle;
        video.cardImageUrl = episode.image;
        video.description = episode.description;
        return video;
    }

    private static List<Video> toVideos(List<PodcastRepository.Episode> episodes) {
        List<Video> result = new ArrayList<>();
        for (PodcastRepository.Episode episode : episodes) {
            result.add(toVideo(episode));
        }
        return result;
    }

    private final class Host implements MusicPlayerView.Host {
        @Override
        public Video video() {
            return mShownVideo;
        }

        @Override
        public boolean isPlaying() {
            return mPlayer.getPlayWhenReady() && mPlayer.getPlaybackState() != Player.STATE_ENDED;
        }

        @Override
        public long position() {
            return mPlayer.getCurrentPosition();
        }

        @Override
        public long duration() {
            long duration = mPlayer.getDuration();
            return duration == C.TIME_UNSET ? 0 : duration;
        }

        @Override
        public long buffered() {
            return mPlayer.getBufferedPosition();
        }

        @Override
        public void togglePlay() {
            mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady());
        }

        @Override
        public void previous() {
            mPlayer.seekToPreviousMediaItem();
        }

        @Override
        public void next() {
            mPlayer.seekToNextMediaItem();
        }

        @Override
        public void seekTo(long ms) {
            mPlayer.seekTo(ms);
        }

        @Override
        public void setSpeed(float speed) {
            PodcastPlayer.setSpeed(speed);
        }

        @Override
        public float speed() {
            return PodcastPlayer.getSpeed();
        }

        @Override
        public void minimize() {
            finish();
        }

        @Override
        public void showVideo() {
        }

        @Override
        public void share() {
            PodcastRepository.Episode episode = PodcastPlayer.current();
            if (episode == null) {
                return;
            }
            Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, episode.title)
                    .putExtra(Intent.EXTRA_TEXT, episode.title + (episode.feedTitle != null ? " – " + episode.feedTitle : "") + "\n" + episode.audioUrl);
            startActivity(Intent.createChooser(intent, "Dela avsnittet"));
        }

        @Override
        public QueueView.Listener queueListener() {
            return null;
        }

        @Override
        public List<Video> queued() {
            return toVideos(PodcastPlayer.queued());
        }

        @Override
        public List<Video> rest() {
            return toVideos(PodcastPlayer.rest());
        }

        @Override
        public boolean isPodcast() {
            return true;
        }

        @Override
        public boolean closable() {
            return true;
        }

        @Override
        public void close() {
            PodcastPlayer.stop();
            finish();
        }

        @Override
        public boolean more() {
            new AlertDialog.Builder(PodcastPlayerActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setItems(new String[]{"Om avsnittet", "Stoppa uppspelningen"}, (d, which) -> {
                        if (which == 0) {
                            showDescription();
                        } else {
                            close();
                        }
                    })
                    .show();
            return true;
        }

        @Override
        public View createQueue(Activity activity, Runnable closeSheet) {
            return PodcastQueueView.create(activity, PodcastPlayer.current(), PodcastPlayer.queued(), PodcastPlayer.rest(), new PodcastQueueView.Listener() {
                @Override
                public void onPlay(PodcastRepository.Episode episode) {
                    if (episode != PodcastPlayer.current()) {
                        PodcastPlayer.jumpTo(episode);
                    }
                    closeSheet.run();
                }

                @Override
                public void onLongPress(PodcastRepository.Episode episode) {
                    if (episode == PodcastPlayer.current()) {
                        return;
                    }
                    new AlertDialog.Builder(PodcastPlayerActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(episode.title)
                            .setItems(new String[]{"Spela nu", "Ta bort ur kön"}, (d, which) -> {
                                if (which == 0) {
                                    PodcastPlayer.jumpTo(episode);
                                    closeSheet.run();
                                } else {
                                    PodcastPlayer.remove(episode);
                                }
                            })
                            .show();
                }

                @Override
                public void onReordered(List<PodcastRepository.Episode> order) {
                    mSkipQueueRefresh = true;
                    PodcastPlayer.reorder(order);
                }
            });
        }
    }

    private void showDescription() {
        PodcastRepository.Episode episode = PodcastPlayer.current();
        if (episode == null) {
            return;
        }
        String text = episode.description != null && !episode.description.isEmpty() ? episode.description : "Ingen beskrivning";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(episode.title)
                .setMessage(android.text.Html.fromHtml(text))
                .setPositiveButton("Stäng", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mHandler.removeCallbacksAndMessages(null);
        PodcastPlayer.removeQueueListener(mQueueListener);
        if (mView != null) {
            mView.release();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mView != null && mView.isQueueShown()) {
            mView.showQueue(false);
            return;
        }
        super.onBackPressed();
    }

    private void onQueueChanged() {
        if (mDestroyed) {
            return;
        }
        // posted: it can come from a drag that is ending (the list is rebuilt)
        mHandler.post(() -> {
            if (mDestroyed) {
                return;
            }
            // after a drag the list already shows the new order (no rebuild: no jump, no flicker)
            if (!mSkipQueueRefresh) {
                mView.refreshQueue();
            }
            mSkipQueueRefresh = false;
        });
    }

    private void update() {
        if (mDestroyed || mPlayer == null) {
            return;
        }
        if (!PodcastPlayer.isActive()) {
            finish(); // stopped from the notification
            return;
        }

        PodcastRepository.Episode episode = PodcastPlayer.current();
        if (episode != null && episode != mShown) {
            mShown = episode;
            mShownVideo = toVideo(episode);
            mView.bind(mShownVideo);
        }
    }
}
