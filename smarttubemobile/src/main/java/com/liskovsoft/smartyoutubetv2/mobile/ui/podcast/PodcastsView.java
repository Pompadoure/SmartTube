package com.liskovsoft.smartyoutubetv2.mobile.ui.podcast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.mobile.R;
import com.liskovsoft.smartyoutubetv2.mobile.ui.widgets.Ui;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Podcasts: the subscribed shows (RSS feeds you add yourself, or import as OPML) and their latest episodes.<br/>
 * JoTube: a view (not an activity), so the music part's bottom bar and mini player stay in place when switching
 * between Musik and Poddar (BrowseActivity shows it over the music feed). PodcastsActivity hosts it too.
 */
public final class PodcastsView extends LinearLayout {
    public static final int REQUEST_OPML = 41;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(3);
    private final List<Object> mItems = new ArrayList<>();
    private final List<PodcastRepository.Episode> mEpisodes = new ArrayList<>();
    private RecyclerView mList;
    private RecyclerView.Adapter<RecyclerView.ViewHolder> mAdapter;
    private String mSelectedFeed;
    private boolean mLoading;
    private int mRefreshGeneration; // only the latest refresh round updates the list (Poddar may be shown again meanwhile)
    private boolean mDestroyed;

    private static final int TYPE_RAIL = 0, TYPE_HEADER = 1, TYPE_EPISODE = 2, TYPE_EMPTY = 3;

    private static final class Rail {
        final List<PodcastRepository.Feed> feeds;

        Rail(List<PodcastRepository.Feed> feeds) {
            this.feeds = feeds;
        }
    }

    private static final class Empty {
        final String text;

        Empty(String text) {
            this.text = text;
        }
    }

    private final Activity mActivity;
    private final Runnable mOnBack;

    /**
     * @param onBack the back arrow of the top bar
     */
    public PodcastsView(Activity activity, Runnable onBack) {
        super(activity);
        mActivity = activity;
        mOnBack = onBack;
        setOrientation(VERTICAL);
        setBackgroundColor(activity.getColor(R.color.yt_bg));
        setClickable(true); // shown over the music feed: touches must not reach the feed below

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(activity, 4), 0, Ui.dp(activity, 4), 0);
        ImageView back = icon(R.drawable.ic_back);
        back.setOnClickListener(v -> mOnBack.run());
        bar.addView(back);
        TextView title = new TextView(activity);
        title.setText("Poddar");
        title.setTextColor(activity.getColor(R.color.yt_text));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView add = icon(R.drawable.ic_add);
        add.setOnClickListener(v -> showAddDialog());
        bar.addView(add);
        ImageView more = icon(R.drawable.ic_more);
        more.setOnClickListener(v -> showMenu());
        bar.addView(more);
        addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 56)));

        mList = new RecyclerView(activity);
        mList.setLayoutManager(new LinearLayoutManager(activity));
        mAdapter = new Adapter();
        mList.setAdapter(mAdapter);
        mList.setItemAnimator(null);
        addView(mList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private ImageView icon(int res) {
        ImageView view = new ImageView(mActivity);
        view.setImageResource(res);
        view.setPadding(Ui.dp(mActivity, 12), Ui.dp(mActivity, 12), Ui.dp(mActivity, 12), Ui.dp(mActivity, 12));
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(mActivity, 48), Ui.dp(mActivity, 48)));
        return view;
    }

    /** Shown (again): the list from the stored feeds right away, then fresh episodes from the network. */
    public void onShown() {
        // Like the old screen, which started fresh each time: all shows, at the top
        mSelectedFeed = null;
        mList.scrollToPosition(0);
        rebuild();
        refresh();
    }

    /** The host activity resumed (e.g. back from the podcast player): positions may have changed. */
    public void onResume() {
        mAdapter.notifyDataSetChanged();
    }

    /** The host activity is destroyed. */
    public void release() {
        mDestroyed = true;
        mExecutor.shutdownNow();
    }

    ///////////////////// data

    private void rebuild() {
        List<PodcastRepository.Feed> feeds = PodcastRepository.subscriptions(mActivity);
        mEpisodes.clear();
        for (PodcastRepository.Feed feed : feeds) {
            if (mSelectedFeed == null || mSelectedFeed.equals(feed.url)) {
                mEpisodes.addAll(feed.episodes);
            }
        }
        Collections.sort(mEpisodes, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        if (mEpisodes.size() > 150) {
            mEpisodes.subList(150, mEpisodes.size()).clear();
        }

        mItems.clear();
        if (feeds.isEmpty()) {
            mItems.add(new Empty("Inga poddar än.\nTryck på + och klistra in en RSS-länk, eller importera en OPML-fil via menyn."));
        } else {
            mItems.add("Dina poddar");
            mItems.add(new Rail(feeds));
            mItems.add(mSelectedFeed == null ? "Senaste avsnitt" : "Avsnitt");
            if (mEpisodes.isEmpty()) {
                mItems.add(new Empty(mLoading ? "Hämtar avsnitt…" : "Inga avsnitt hittades."));
            } else {
                mItems.addAll(mEpisodes);
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private void refresh() {
        final List<PodcastRepository.Feed> feeds = PodcastRepository.subscriptions(mActivity);
        mLoading = !feeds.isEmpty();
        final int generation = ++mRefreshGeneration;
        final int[] pending = {feeds.size()};
        for (PodcastRepository.Feed feed : feeds) {
            final String url = feed.url;
            mExecutor.execute(() -> {
                try {
                    PodcastRepository.Feed fresh = PodcastRepository.fetch(url);
                    PodcastRepository.subscribe(mActivity.getApplicationContext(), fresh);
                } catch (Exception e) {
                    // keep what we had; the next refresh tries again
                }
                mActivity.runOnUiThread(() -> {
                    if (mDestroyed || generation != mRefreshGeneration) {
                        return; // destroyed, or a newer round runs (the fetched feed is stored anyway)
                    }
                    pending[0]--;
                    if (pending[0] <= 0) {
                        mLoading = false;
                    }
                    rebuild();
                });
            });
        }
    }

    ///////////////////// add / import

    private void showAddDialog() {
        final EditText input = new EditText(mActivity);
        input.setHint("https://…/feed.xml");
        input.setSingleLine(true);
        input.setTextColor(mActivity.getColor(R.color.yt_text));
        input.setHintTextColor(mActivity.getColor(R.color.yt_text_secondary));
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        FrameLayoutPadding wrap = new FrameLayoutPadding(mActivity, input);

        new AlertDialog.Builder(mActivity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Lägg till podd")
                .setMessage("RSS-länk till poddens flöde")
                .setView(wrap)
                .setNegativeButton("Avbryt", null)
                .setPositiveButton("Lägg till", (d, w) -> addFeed(input.getText().toString().trim()))
                .show();
    }

    private static final class FrameLayoutPadding extends android.widget.FrameLayout {
        FrameLayoutPadding(Context context, View child) {
            super(context);
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0);
            addView(child, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void addFeed(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        if (!url.startsWith("http")) {
            url = "https://" + url;
        }
        final String feedUrl = url;
        Toast.makeText(mActivity, "Hämtar flödet…", Toast.LENGTH_SHORT).show();
        mExecutor.execute(() -> {
            try {
                PodcastRepository.Feed feed = PodcastRepository.fetch(feedUrl);
                PodcastRepository.subscribe(mActivity.getApplicationContext(), feed);
                mActivity.runOnUiThread(() -> {
                    if (!mDestroyed) {
                        Toast.makeText(mActivity, "Lade till " + feed.title, Toast.LENGTH_SHORT).show();
                        rebuild();
                    }
                });
            } catch (Exception e) {
                mActivity.runOnUiThread(() -> {
                    if (!mDestroyed) {
                        Toast.makeText(mActivity, "Det där verkar inte vara ett poddflöde (" + e.getMessage() + ")", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showMenu() {
        new AlertDialog.Builder(mActivity, android.R.style.Theme_Material_Dialog_Alert)
                .setItems(new String[]{"Importera OPML-fil…", "Uppdatera"}, (d, which) -> {
                    if (which == 0) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                        mActivity.startActivityForResult(intent, REQUEST_OPML);
                    } else {
                        refresh();
                    }
                })
                .show();
    }

    /** From the host activity's onActivityResult (the OPML file picker). */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_OPML && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            mExecutor.execute(() -> {
                int added = 0;
                try (InputStream stream = mActivity.getContentResolver().openInputStream(uri)) {
                    org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
                    parser.setInput(stream, null);
                    for (int event = parser.getEventType(); event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; event = parser.next()) {
                        if (event == org.xmlpull.v1.XmlPullParser.START_TAG && "outline".equals(parser.getName())) {
                            String xmlUrl = parser.getAttributeValue(null, "xmlUrl");
                            if (xmlUrl != null) {
                                PodcastRepository.Feed feed = new PodcastRepository.Feed();
                                feed.url = xmlUrl;
                                String name = parser.getAttributeValue(null, "title");
                                feed.title = name != null ? name : parser.getAttributeValue(null, "text");
                                if (feed.title == null) {
                                    feed.title = xmlUrl;
                                }
                                PodcastRepository.subscribe(mActivity.getApplicationContext(), feed);
                                added++;
                            }
                        }
                    }
                } catch (Exception e) {
                    final String message = e.getMessage();
                    mActivity.runOnUiThread(() -> Toast.makeText(mActivity, "Kunde inte läsa filen: " + message, Toast.LENGTH_LONG).show());
                    return;
                }
                final int count = added;
                mActivity.runOnUiThread(() -> {
                    if (!mDestroyed) {
                        Toast.makeText(mActivity, count + " poddar importerade", Toast.LENGTH_SHORT).show();
                        rebuild();
                        refresh();
                    }
                });
            });
        }
    }

    ///////////////////// list

    private void playEpisode(PodcastRepository.Episode episode) {
        int index = mEpisodes.indexOf(episode);
        if (index < 0) {
            return;
        }
        PodcastPlayer.play(mActivity, mEpisodes, index);
        mActivity.startActivity(new Intent(mActivity, PodcastPlayerActivity.class));
    }

    private static String formatDuration(long ms) {
        long minutes = ms / 60_000;
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            Object item = mItems.get(position);
            if (item instanceof Rail) {
                return TYPE_RAIL;
            }
            if (item instanceof String) {
                return TYPE_HEADER;
            }
            if (item instanceof Empty) {
                return TYPE_EMPTY;
            }
            return TYPE_EPISODE;
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            if (viewType == TYPE_RAIL) {
                RecyclerView rail = new RecyclerView(c);
                rail.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                rail.setLayoutManager(new LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false));
                rail.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 4));
                rail.setClipToPadding(false);
                return new RecyclerView.ViewHolder(rail) {
                };
            }
            if (viewType == TYPE_HEADER) {
                TextView header = new TextView(c);
                header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                header.setTextColor(c.getColor(R.color.yt_text));
                header.setTextSize(18);
                header.setTypeface(Typeface.DEFAULT_BOLD);
                header.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 8));
                return new RecyclerView.ViewHolder(header) {
                };
            }
            if (viewType == TYPE_EMPTY) {
                TextView empty = new TextView(c);
                empty.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                empty.setTextColor(c.getColor(R.color.yt_text_secondary));
                empty.setTextSize(15);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(Ui.dp(c, 32), Ui.dp(c, 48), Ui.dp(c, 32), Ui.dp(c, 48));
                return new RecyclerView.ViewHolder(empty) {
                };
            }

            // episode row
            LinearLayout row = new LinearLayout(c);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
            ImageView cover = new ImageView(c);
            cover.setId(R.id.mobile_thumb);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundColor(c.getColor(R.color.yt_surface));
            Ui.makeRounded(cover, Ui.dp(c, 8));
            row.addView(cover, new LinearLayout.LayoutParams(Ui.dp(c, 68), Ui.dp(c, 68)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
            TextView title = new TextView(c);
            title.setId(R.id.mobile_title);
            title.setTextColor(c.getColor(R.color.yt_text));
            title.setTextSize(15);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            TextView meta = new TextView(c);
            meta.setId(R.id.mobile_meta);
            meta.setTextColor(c.getColor(R.color.yt_text_secondary));
            meta.setTextSize(12);
            meta.setMaxLines(2);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            meta.setPadding(0, Ui.dp(c, 2), 0, 0);
            texts.addView(meta);
            // How far it was listened to: a grey track with a red fill, under the texts
            LinearLayout track = new LinearLayout(c);
            track.setId(R.id.mobile_progress);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setWeightSum(1f);
            track.setBackgroundColor(0xFF4A4A4A);
            Ui.makeRounded(track, Ui.dp(c, 2));
            View fill = new View(c);
            fill.setBackgroundColor(c.getColor(R.color.yt_red));
            track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f));
            LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 4));
            trackParams.topMargin = Ui.dp(c, 6);
            texts.addView(track, trackParams);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageView play = new ImageView(c);
            play.setImageResource(R.drawable.ic_play);
            play.setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
            row.addView(play, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
            return new RecyclerView.ViewHolder(row) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Object item = mItems.get(position);
            if (item instanceof String) {
                ((TextView) holder.itemView).setText((String) item);
            } else if (item instanceof Empty) {
                ((TextView) holder.itemView).setText(((Empty) item).text);
            } else if (item instanceof Rail) {
                bindRail((RecyclerView) holder.itemView, ((Rail) item).feeds);
            } else {
                bindEpisode(holder.itemView, (PodcastRepository.Episode) item);
            }
        }

        private void bindEpisode(View row, final PodcastRepository.Episode episode) {
            Context c = row.getContext();
            boolean finished = PodcastRepository.isFinished(c, episode);
            TextView titleView = row.findViewById(R.id.mobile_title);
            titleView.setText(episode.title);
            titleView.setTextColor(c.getColor(finished ? R.color.yt_text_secondary : R.color.yt_text));
            LinearLayout track = row.findViewById(R.id.mobile_progress);
            float fraction = PodcastRepository.getProgress(c, episode);
            View fill = track.getChildAt(0);
            LinearLayout.LayoutParams fillParams = (LinearLayout.LayoutParams) fill.getLayoutParams();
            fillParams.weight = fraction;
            fill.setLayoutParams(fillParams);
            track.setVisibility(fraction > 0 ? View.VISIBLE : View.GONE);
            StringBuilder meta = new StringBuilder();
            if (episode.feedTitle != null) {
                meta.append(episode.feedTitle);
            }
            if (episode.dateMs > 0) {
                meta.append(" • ").append(DateUtils.getRelativeTimeSpanString(episode.dateMs, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS));
            }
            if (episode.durationMs > 0) {
                meta.append(" • ").append(formatDuration(episode.durationMs));
            }
            long position = PodcastRepository.getPosition(c, episode);
            long duration = PodcastRepository.getDurationMs(c, episode);
            if (finished) {
                meta.append("\n").append("✓ Lyssnad");
            } else if (position > 0 && duration > position) {
                meta.append("\n").append("Fortsätt: ").append(formatDuration(duration - position)).append(" kvar");
            }
            ((TextView) row.findViewById(R.id.mobile_meta)).setText(meta);
            ImageView cover = row.findViewById(R.id.mobile_thumb);
            if (episode.image != null) {
                Glide.with(cover).load(episode.image).into(cover);
            } else {
                Glide.with(cover).clear(cover);
                cover.setImageDrawable(null);
            }
            row.setOnClickListener(v -> playEpisode(episode));
        }

        private void bindRail(RecyclerView rail, final List<PodcastRepository.Feed> feeds) {
            rail.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                    Context c = parent.getContext();
                    LinearLayout cell = new LinearLayout(c);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(Ui.dp(c, 96), ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.setMarginEnd(Ui.dp(c, 10));
                    cell.setLayoutParams(params);
                    ImageView cover = new ImageView(c);
                    cover.setId(R.id.mobile_thumb);
                    cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    cover.setBackgroundColor(c.getColor(R.color.yt_surface));
                    Ui.makeRounded(cover, Ui.dp(c, 10));
                    cell.addView(cover, new LinearLayout.LayoutParams(Ui.dp(c, 96), Ui.dp(c, 96)));
                    TextView name = new TextView(c);
                    name.setId(R.id.mobile_title);
                    name.setTextColor(c.getColor(R.color.yt_text));
                    name.setTextSize(12);
                    name.setMaxLines(2);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    name.setPadding(0, Ui.dp(c, 6), 0, 0);
                    cell.addView(name);
                    return new RecyclerView.ViewHolder(cell) {
                    };
                }

                @Override
                public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
                    final PodcastRepository.Feed feed = feeds.get(pos);
                    ((TextView) h.itemView.findViewById(R.id.mobile_title)).setText(feed.title);
                    ImageView cover = h.itemView.findViewById(R.id.mobile_thumb);
                    if (feed.image != null) {
                        Glide.with(cover).load(feed.image).into(cover);
                    } else {
                        Glide.with(cover).clear(cover);
                        cover.setImageDrawable(null);
                    }
                    boolean dim = mSelectedFeed != null && !mSelectedFeed.equals(feed.url);
                    h.itemView.setAlpha(dim ? 0.45f : 1f);
                    h.itemView.setOnClickListener(v -> {
                        mSelectedFeed = feed.url.equals(mSelectedFeed) ? null : feed.url;
                        rebuild();
                    });
                    h.itemView.setOnLongClickListener(v -> {
                        new AlertDialog.Builder(mActivity, android.R.style.Theme_Material_Dialog_Alert)
                                .setTitle(feed.title)
                                .setMessage("Ta bort podden?")
                                .setNegativeButton("Avbryt", null)
                                .setPositiveButton("Ta bort", (d, w) -> {
                                    PodcastRepository.unsubscribe(mActivity, feed.url);
                                    if (feed.url.equals(mSelectedFeed)) {
                                        mSelectedFeed = null;
                                    }
                                    rebuild();
                                })
                                .show();
                        return true;
                    });
                }

                @Override
                public int getItemCount() {
                    return feeds.size();
                }
            });
        }
    }
}
