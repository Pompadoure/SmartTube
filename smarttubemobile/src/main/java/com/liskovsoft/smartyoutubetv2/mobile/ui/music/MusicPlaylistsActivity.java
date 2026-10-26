package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.main.MobileActivity;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** "Dina spellistor": the list of playlists, and the songs of one playlist (extra {@link #EXTRA_ID}). */
public class MusicPlaylistsActivity extends MobileActivity {
    public static final String EXTRA_ID = "playlist_id";

    private String mPlaylistId;
    private TextView mTitle;
    private LinearLayout mActions;
    private RecyclerView mList;
    private final List<Object> mRows = new ArrayList<>(); // MusicLibrary.Playlist or MusicLibrary.Track
    private RecyclerView.Adapter<RecyclerView.ViewHolder> mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPlaylistId = getIntent().getStringExtra(EXTRA_ID);
        setContentView(createContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.yt_bg));
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));

        mTitle = new TextView(this);
        mTitle.setTextColor(getColor(R.color.yt_text));
        mTitle.setTextSize(18);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(mTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView add = new ImageView(this);
        add.setImageResource(R.drawable.ic_add);
        add.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        add.setOnClickListener(v -> {
            if (mPlaylistId == null) {
                MusicMenu.askName(this, "Ny spellista", "", name -> {
                    MusicLibrary.create(this, name);
                    reload();
                });
            } else {
                startActivity(new Intent(this, MusicSearchActivity.class)); // find songs to add
            }
        });
        bar.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        root.addView(bar);

        mActions = new LinearLayout(this);
        mActions.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        mActions.addView(actionButton("Spela", false));
        mActions.addView(actionButton("Blanda", true));
        mActions.setVisibility(mPlaylistId != null ? View.VISIBLE : View.GONE);
        root.addView(mActions);

        mList = new RecyclerView(this);
        mList.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new RowAdapter();
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(null);
        root.addView(mList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private TextView actionButton(String text, boolean shuffle) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(getColor(R.color.yt_bg));
        button.setBackgroundResource(R.drawable.bg_chip_selected);
        button.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        button.setOnClickListener(v -> playAll(shuffle));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(this, 8));
        button.setLayoutParams(params);
        return button;
    }

    private void reload() {
        mRows.clear();
        if (mPlaylistId == null) {
            mTitle.setText("Dina spellistor");
            mRows.addAll(MusicLibrary.playlists(this));
        } else {
            MusicLibrary.Playlist playlist = MusicLibrary.find(this, mPlaylistId);
            if (playlist == null) {
                finish();
                return;
            }
            mTitle.setText(playlist.name);
            mRows.addAll(playlist.tracks);
        }
        mAdapter.notifyDataSetChanged();
    }

    private void playAll(boolean shuffle) {
        List<Video> queue = new ArrayList<>();
        for (Object row : mRows) {
            queue.add(((MusicLibrary.Track) row).toVideo());
        }
        if (queue.isEmpty()) {
            return;
        }
        if (shuffle) {
            Collections.shuffle(queue);
        }
        MusicMenu.playQueue(this, queue, 0);
    }

    private void onRowClicked(int position) {
        Object row = mRows.get(position);
        if (row instanceof MusicLibrary.Playlist) {
            startActivity(new Intent(this, MusicPlaylistsActivity.class).putExtra(EXTRA_ID, ((MusicLibrary.Playlist) row).id));
        } else {
            List<Video> queue = new ArrayList<>();
            for (Object r : mRows) {
                queue.add(((MusicLibrary.Track) r).toVideo());
            }
            MusicMenu.playQueue(this, queue, position);
        }
    }

    private void onRowLongClicked(int position) {
        Object row = mRows.get(position);
        if (row instanceof MusicLibrary.Playlist) {
            MusicLibrary.Playlist playlist = (MusicLibrary.Playlist) row;
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(playlist.name)
                    .setItems(new String[]{"Byt namn", "Ta bort spellistan"}, (d, which) -> {
                        if (which == 0) {
                            MusicMenu.askName(this, "Byt namn", playlist.name, name -> {
                                MusicLibrary.rename(this, playlist.id, name);
                                reload();
                            });
                        } else {
                            MusicLibrary.delete(this, playlist.id);
                            reload();
                        }
                    })
                    .show();
        } else {
            MusicLibrary.Track track = (MusicLibrary.Track) row;
            MusicMenu.show(this, track.toVideo(), "Ta bort från spellistan", () -> {
                MusicLibrary.removeTrack(this, mPlaylistId, track.videoId);
                reload();
            });
        }
    }

    private class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(parent.getContext(), 16), Ui.dp(parent.getContext(), 6), Ui.dp(parent.getContext(), 16), Ui.dp(parent.getContext(), 6));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView cover = new ImageView(parent.getContext());
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(cover, new LinearLayout.LayoutParams(Ui.dp(parent.getContext(), 56), Ui.dp(parent.getContext(), 56)));

            LinearLayout texts = new LinearLayout(parent.getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(parent.getContext(), 12), 0, 0, 0);
            TextView title = new TextView(parent.getContext());
            title.setTextColor(getColor(R.color.yt_text));
            title.setTextSize(15);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            TextView subtitle = new TextView(parent.getContext());
            subtitle.setTextColor(getColor(R.color.yt_text_secondary));
            subtitle.setTextSize(13);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return new RecyclerView.ViewHolder(row) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Object item = mRows.get(position);
            LinearLayout row = (LinearLayout) holder.itemView;
            ImageView cover = (ImageView) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            Ui.makeRounded(cover, Ui.dp(MusicPlaylistsActivity.this, 6));

            String thumb;
            if (item instanceof MusicLibrary.Playlist) {
                MusicLibrary.Playlist p = (MusicLibrary.Playlist) item;
                ((TextView) texts.getChildAt(0)).setText(p.name);
                ((TextView) texts.getChildAt(1)).setText(p.tracks.size() + " låtar");
                thumb = p.tracks.isEmpty() ? null : p.tracks.get(0).thumb;
            } else {
                MusicLibrary.Track t = (MusicLibrary.Track) item;
                ((TextView) texts.getChildAt(0)).setText(t.title);
                ((TextView) texts.getChildAt(1)).setText(t.author);
                thumb = t.thumb;
            }
            if (thumb != null) {
                Glide.with(cover).load(thumb).into(cover);
            } else {
                cover.setImageResource(R.drawable.ic_music);
                cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }
            row.setOnClickListener(v -> onRowClicked(holder.getBindingAdapterPosition()));
            row.setOnLongClickListener(v -> {
                onRowLongClicked(holder.getBindingAdapterPosition());
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }
    }
}
