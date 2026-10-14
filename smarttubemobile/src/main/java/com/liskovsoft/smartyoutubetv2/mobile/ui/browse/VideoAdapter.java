package com.liskovsoft.smartyoutubetv2.mobile.ui.browse;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.RatioFrameLayout;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * YouTube-like feed: a full width 16:9 card (duration badge, channel avatar, two line title, channel and views),
 * Shorts as 9:16 cards in two columns. Rows of the TV home feed become section headers.
 */
public class VideoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onClick(Video video);

        void onLongClick(Video video);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_VIDEO = 1;
    private static final int TYPE_SHORT = 2;
    private static final int TYPE_SETTING = 3;
    private static final int TYPE_VIEW = 4;
    private static final int TYPE_SHORTS_ROW = 5;
    private boolean mShortsAsWide;
    private final List<Object> mItems = new ArrayList<>();
    private final Listener mListener;

    public VideoAdapter(Listener listener) {
        mListener = listener;
        setHasStableIds(false);
    }

    public void clear() {
        int size = mItems.size();
        mItems.clear();
        notifyItemRangeRemoved(0, size);
    }

    /** Watch page: Shorts among the suggestions are shown like ordinary 16:9 cards. */
    public void setShortsAsWide(boolean wide) {
        mShortsAsWide = wide;
    }

    /** A ready made view (the watch page info block) as the first row. */
    public void addView(View view) {
        mItems.add(view);
        notifyItemInserted(mItems.size() - 1);
    }

    /** Home feed: Shorts as one small horizontally scrolling row. */
    public void addShortsRow(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return;
        }

        mItems.add(new ArrayList<>(videos));
        notifyItemInserted(mItems.size() - 1);
    }

    public void addHeader(String title) {
        if (TextUtils.isEmpty(title)) {
            return;
        }

        mItems.add(title);
        notifyItemInserted(mItems.size() - 1);
    }

    public void addSettings(List<SettingsItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        int start = mItems.size();
        mItems.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public void addVideos(List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            return;
        }

        int start = mItems.size();

        for (Video video : videos) {
            if (!contains(video)) {
                mItems.add(video);
            }
        }

        notifyItemRangeInserted(start, mItems.size() - start);
    }

    private boolean contains(Video video) {
        // Same video twice in a feed is a server hiccup; only a cheap check against the tail
        for (int i = Math.max(0, mItems.size() - 40); i < mItems.size(); i++) {
            if (mItems.get(i) == video) {
                return true;
            }
        }

        return false;
    }

    public void removeVideo(Video video) {
        int index = mItems.indexOf(video);

        if (index >= 0) {
            mItems.remove(index);
            notifyItemRemoved(index);
        }
    }

    public int size() {
        return mItems.size();
    }

    public Video lastVideo() {
        for (int i = mItems.size() - 1; i >= 0; i--) {
            if (mItems.get(i) instanceof Video) {
                return (Video) mItems.get(i);
            }

            if (mItems.get(i) instanceof List && !((List<?>) mItems.get(i)).isEmpty()) {
                List<?> row = (List<?>) mItems.get(i);
                return (Video) row.get(row.size() - 1);
            }
        }

        return null;
    }

    public boolean isHeader(int position) {
        return position >= 0 && position < mItems.size() && !(mItems.get(position) instanceof Video);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @Override
    public int getItemViewType(int position) {
        Object item = mItems.get(position);

        if (item instanceof String) {
            return TYPE_HEADER;
        }

        if (item instanceof View) {
            return TYPE_VIEW;
        }

        if (item instanceof List) {
            return TYPE_SHORTS_ROW;
        }

        if (item instanceof SettingsItem) {
            return TYPE_SETTING;
        }

        return ((Video) item).isShorts && !mShortsAsWide ? TYPE_SHORT : TYPE_VIDEO;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        Context context = parent.getContext();

        if (viewType == TYPE_HEADER) {
            TextView header = new TextView(context);
            header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            header.setTextColor(context.getColor(R.color.yt_text));
            header.setTextSize(18);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setPadding(Ui.dp(context, 16), Ui.dp(context, 20), Ui.dp(context, 16), Ui.dp(context, 8));
            return new RecyclerView.ViewHolder(header) {
            };
        }

        if (viewType == TYPE_SHORTS_ROW) {
            RecyclerView row = new RecyclerView(context);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(context, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
            row.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), Ui.dp(context, 8));
            row.setClipToPadding(false);
            row.setNestedScrollingEnabled(false);
            return new RecyclerView.ViewHolder(row) {
            };
        }

        if (viewType == TYPE_VIEW) {
            FrameLayout holder = new FrameLayout(context);
            holder.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(holder) {
            };
        }

        if (viewType == TYPE_SETTING) {
            TextView row = new TextView(context);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setTextColor(context.getColor(R.color.yt_text));
            row.setTextSize(16);
            row.setPadding(Ui.dp(context, 16), Ui.dp(context, 16), Ui.dp(context, 16), Ui.dp(context, 16));
            return new RecyclerView.ViewHolder(row) {
            };
        }

        return new CardHolder(createCard(context, viewType == TYPE_SHORT));
    }

    private View createCard(Context context, boolean isShort) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setPadding(0, 0, 0, Ui.dp(context, isShort ? 12 : 16));

        RatioFrameLayout thumbBox = new RatioFrameLayout(context);
        thumbBox.setHeightPerWidth(isShort ? 16f / 9f : 9f / 16f);
        thumbBox.setBackgroundColor(context.getColor(R.color.yt_surface));
        thumbBox.setTag(R.id.mobile_thumb_box, Boolean.TRUE);

        ImageView thumb = new ImageView(context);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setId(R.id.mobile_thumb);
        thumbBox.addView(thumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView badge = new TextView(context);
        badge.setId(R.id.mobile_badge);
        badge.setBackgroundResource(R.drawable.bg_badge);
        badge.setTextColor(context.getColor(R.color.yt_text));
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(Ui.dp(context, 4), Ui.dp(context, 1), Ui.dp(context, 4), Ui.dp(context, 1));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        badgeParams.setMargins(0, 0, Ui.dp(context, 8), Ui.dp(context, 8));
        thumbBox.addView(badge, badgeParams);
        card.addView(thumbBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(context, isShort ? 4 : 12), Ui.dp(context, 12), Ui.dp(context, isShort ? 4 : 12), 0);

        if (!isShort) {
            ImageView avatar = new ImageView(context);
            avatar.setId(R.id.mobile_avatar);
            avatar.setBackgroundResource(R.drawable.bg_avatar);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Ui.makeCircle(avatar);
            LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36));
            avatarParams.setMarginEnd(Ui.dp(context, 12));
            row.addView(avatar, avatarParams);
        }

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setId(R.id.mobile_title);
        title.setTextColor(context.getColor(R.color.yt_text));
        title.setTextSize(isShort ? 14 : 15);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);

        TextView meta = new TextView(context);
        meta.setId(R.id.mobile_meta);
        meta.setTextColor(context.getColor(R.color.yt_text_secondary));
        meta.setTextSize(12);
        meta.setMaxLines(1);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, Ui.dp(context, 2), 0, 0);
        texts.addView(meta);

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return card;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        Object item = mItems.get(position);

        if (item instanceof String) {
            ((TextView) holder.itemView).setText((String) item);
            return;
        }

        if (item instanceof List) {
            @SuppressWarnings("unchecked")
            final List<Video> shorts = (List<Video>) item;
            ((RecyclerView) holder.itemView).setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                    View card = createCard(parent.getContext(), true);
                    RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(Ui.dp(parent.getContext(), 128), ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.setMarginEnd(Ui.dp(parent.getContext(), 8));
                    card.setLayoutParams(params);
                    return new CardHolder(card);
                }

                @Override
                public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                    bindCard(h.itemView, shorts.get(pos));
                }

                @Override
                public int getItemCount() {
                    return shorts.size();
                }
            });
            return;
        }

        if (item instanceof View) {
            View view = (View) item;
            FrameLayout frame = (FrameLayout) holder.itemView;
            if (view.getParent() != frame) {
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                frame.removeAllViews();
                frame.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return;
        }

        if (item instanceof SettingsItem) {
            SettingsItem setting = (SettingsItem) item;
            ((TextView) holder.itemView).setText(setting.title);
            holder.itemView.setOnClickListener(v -> {
                if (setting.onClick != null) {
                    setting.onClick.run();
                }
            });
            return;
        }

        bindCard(holder.itemView, (Video) item);
    }

    private void bindCard(View root, Video video) {
        ImageView thumb = root.findViewById(R.id.mobile_thumb);
        ImageView avatar = root.findViewById(R.id.mobile_avatar);
        TextView badge = root.findViewById(R.id.mobile_badge);
        TextView title = root.findViewById(R.id.mobile_title);
        TextView meta = root.findViewById(R.id.mobile_meta);

        title.setText(video.getTitle());

        CharSequence second = video.getSecondTitle();
        String author = video.getAuthor();
        // the second line often starts with the author already
        if (author != null && second != null && second.toString().startsWith(author)) {
            second = second.toString().substring(author.length()).replaceFirst("^\\s*[•·-]\\s*", "");
        }
        if (author != null && !TextUtils.isEmpty(second)) {
            meta.setText(author + " • " + second);
        } else {
            meta.setText(author != null ? author : second);
        }

        String badgeText = video.isLive ? "LIVE" : video.badge;
        badge.setVisibility(TextUtils.isEmpty(badgeText) ? View.GONE : View.VISIBLE);
        badge.setText(badgeText);
        badge.setBackgroundResource(video.isLive ? R.drawable.bg_badge_live : R.drawable.bg_badge);

        Glide.with(thumb).load(video.getCardImageUrl()).into(thumb);

        if (avatar != null) {
            if (video.authorImageUrl != null) {
                Glide.with(avatar).load(video.authorImageUrl).into(avatar);
            } else {
                Glide.with(avatar).clear(avatar);
                avatar.setImageDrawable(null);
            }
        }

        root.setOnClickListener(v -> mListener.onClick(video));
        root.setOnLongClickListener(v -> {
            mListener.onLongClick(video);
            return true;
        });
    }

    @Override
    public void onViewRecycled(RecyclerView.ViewHolder holder) {
        View thumb = holder.itemView.findViewById(R.id.mobile_thumb);

        if (thumb != null) {
            Glide.with(thumb).clear(thumb);
        }
    }

    private static class CardHolder extends RecyclerView.ViewHolder {
        CardHolder(View itemView) {
            super(itemView);
        }
    }
}
