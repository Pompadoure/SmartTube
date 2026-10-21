package com.liskovsoft.smartyoutubetv2.mobile.ui.podcast;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JoTube: the podcast queue, like the music one ({@link com.liskovsoft.smartyoutubetv2.mobile.ui.music.QueueView}): the
 * playing episode first, then the queued episodes (can be dragged), then the rest of the list the episode came from.
 */
public final class PodcastQueueView {
    public interface Listener {
        void onPlay(PodcastRepository.Episode episode);

        void onLongPress(PodcastRepository.Episode episode);

        /** The user dragged episodes: the new order of the queued episodes (not the playing one, not the rest). */
        void onReordered(List<PodcastRepository.Episode> queued);
    }

    private PodcastQueueView() {
    }

    /**
     * @param current the playing episode
     * @param queued  the episodes after it in the queue (can be dragged)
     * @param rest    episodes that follow from the list the episode came from (cannot be dragged)
     */
    public static View create(Activity activity, PodcastRepository.Episode current, List<PodcastRepository.Episode> queued, List<PodcastRepository.Episode> rest, Listener listener) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(activity);
        header.setText("Spelas just nu");
        header.setTextColor(activity.getColor(R.color.yt_text));
        header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 20), Ui.dp(activity, 16), Ui.dp(activity, 8));
        root.addView(header);

        final List<PodcastRepository.Episode> rows = new ArrayList<>();
        if (current != null) {
            rows.add(current);
        }
        final int firstQueued = rows.size();
        rows.addAll(queued);
        final int queuedEnd = rows.size(); // exclusive
        rows.addAll(rest);

        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setItemAnimator(null);

        final ItemTouchHelper[] helper = new ItemTouchHelper[1];
        // the order the player has (the list isn't rebuilt after a drag, so compare with the last reported order)
        final List<List<PodcastRepository.Episode>> reported = new ArrayList<>();
        reported.add(new ArrayList<>(queued));

        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(createRow(activity)) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                PodcastRepository.Episode episode = rows.get(position);
                LinearLayout row = (LinearLayout) holder.itemView;
                ImageView cover = (ImageView) row.getChildAt(0);
                LinearLayout texts = (LinearLayout) row.getChildAt(1);
                ImageView handle = (ImageView) row.getChildAt(2);

                ((TextView) texts.getChildAt(0)).setText(episode.title);
                String author = episode.feedTitle;
                ((TextView) texts.getChildAt(1)).setText(author != null ? author : "");

                String thumb = episode.image;
                if (thumb != null) {
                    Glide.with(cover).load(thumb).into(cover);
                } else {
                    Glide.with(cover).clear(cover);
                    cover.setImageDrawable(null);
                }

                boolean draggable = position >= firstQueued && position < queuedEnd;
                handle.setVisibility(draggable ? View.VISIBLE : View.INVISIBLE);
                handle.setOnTouchListener((v, event) -> {
                    if (draggable && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        helper[0].startDrag(holder);
                        return true; // the handle takes the touch: no click on the row
                    }
                    return draggable;
                });

                row.setOnClickListener(v -> listener.onPlay(episode));
                row.setOnLongClickListener(v -> {
                    listener.onLongPress(episode);
                    return true;
                });
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        });

        helper[0] = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                int position = viewHolder.getBindingAdapterPosition();
                boolean draggable = position >= firstQueued && position < queuedEnd;
                return makeMovementFlags(draggable ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from < firstQueued || from >= queuedEnd || to < firstQueued || to >= queuedEnd) {
                    return false;
                }
                rows.add(to, rows.remove(from));
                recyclerView.getAdapter().notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                List<PodcastRepository.Episode> now = new ArrayList<>(rows.subList(firstQueued, queuedEnd));
                if (!now.equals(reported.get(0))) { // only when something moved
                    reported.set(0, now);
                    listener.onReordered(now);
                }
            }
        });
        helper[0].attachToRecyclerView(list);

        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private static View createRow(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8), Ui.dp(activity, 4), Ui.dp(activity, 8));
        row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView cover = new ImageView(activity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.makeRounded(cover, Ui.dp(activity, 4));
        row.addView(cover, new LinearLayout.LayoutParams(Ui.dp(activity, 56), Ui.dp(activity, 56)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 8), 0);
        TextView title = new TextView(activity);
        title.setTextColor(activity.getColor(R.color.yt_text));
        title.setTextSize(16);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView subtitle = new TextView(activity);
        subtitle.setTextColor(activity.getColor(R.color.yt_text_secondary));
        subtitle.setTextSize(14);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(subtitle);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView handle = new ImageView(activity);
        handle.setImageResource(R.drawable.ic_drag);
        handle.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 12), Ui.dp(activity, 12));
        row.addView(handle, new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        return row;
    }
}
