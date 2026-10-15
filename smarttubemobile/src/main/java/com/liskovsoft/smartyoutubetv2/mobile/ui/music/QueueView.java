package com.liskovsoft.smartyoutubetv2.mobile.ui.music;

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
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "Spelas just nu" like the YouTube Music queue: the playing song first, then the songs that follow, each with a cover,
 * a title, an artist and a handle to drag it to another place.
 */
public final class QueueView {
    public interface Listener {
        void onPlay(Video video);

        void onLongPress(Video video);

        /** The user dragged songs: the new order of the queued songs (not the playing one, not the rest). */
        void onReordered(List<Video> queued);
    }

    private QueueView() {
    }

    /**
     * @param current the playing song
     * @param queued  the songs after it in the queue (can be dragged)
     * @param rest    songs that follow from the list the song came from (cannot be dragged)
     */
    public static View create(Activity activity, Video current, List<Video> queued, List<Video> rest, Listener listener) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView header = new TextView(activity);
        header.setText("Spelas just nu");
        header.setTextColor(activity.getColor(R.color.yt_text));
        header.setTextSize(18);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 20), Ui.dp(activity, 16), Ui.dp(activity, 8));
        root.addView(header);

        final List<Video> rows = new ArrayList<>();
        if (current != null) {
            rows.add(current);
        }
        final int firstQueued = rows.size();
        rows.addAll(queued);
        final int queuedEnd = rows.size(); // exclusive
        rows.addAll(rest);

        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setNestedScrollingEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setItemAnimator(null);

        final ItemTouchHelper[] helper = new ItemTouchHelper[1];

        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(createRow(activity)) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                Video video = rows.get(position);
                LinearLayout row = (LinearLayout) holder.itemView;
                ImageView cover = (ImageView) row.getChildAt(0);
                LinearLayout texts = (LinearLayout) row.getChildAt(1);
                ImageView handle = (ImageView) row.getChildAt(2);

                ((TextView) texts.getChildAt(0)).setText(video.getTitle());
                String author = video.getAuthor();
                ((TextView) texts.getChildAt(1)).setText(author != null ? author : "");

                String thumb = video.getCardImageUrl();
                if (thumb != null) {
                    Glide.with(cover).load(thumb).into(cover);
                } else {
                    cover.setImageDrawable(null);
                }

                boolean draggable = position >= firstQueued && position < queuedEnd;
                handle.setVisibility(draggable ? View.VISIBLE : View.INVISIBLE);
                handle.setOnTouchListener((v, event) -> {
                    if (draggable && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        helper[0].startDrag(holder);
                    }
                    return false;
                });

                row.setOnClickListener(v -> listener.onPlay(video));
                row.setOnLongClickListener(v -> {
                    listener.onLongPress(video);
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
                Collections.swap(rows, from, to);
                recyclerView.getAdapter().notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                listener.onReordered(new ArrayList<>(rows.subList(firstQueued, queuedEnd)));
            }
        });
        helper[0].attachToRecyclerView(list);

        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
