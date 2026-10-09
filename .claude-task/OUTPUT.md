I added thumbnail preloading to the home rows and the grid screens. I couldn't build or run anything, so none of it has been compiled or tried on a device.

**Where images load.** Card images are loaded in `VideoCardPresenter.onBindViewHolder`. `TinyCardPresenter` and `ShortsCardPresenter` inherit that code. The request has no explicit transformation. Its options are `ViewUtil.glideOptions()`, `.override(w, h)`, and `diskCacheStrategy(ALL)` on API above 21.

**Cache-key match.** The old request never used Glide's memory cache. `glideOptions()` sets `skipMemoryCache(true)`, so a matching preload could only have warmed the disk cache. Glide also adds a centerCrop transformation implicitly when loading into an ImageView, which a preload without a view would not have.
- **Shared options:** one helper, `ThumbnailPreloader.applyCardOptions(...)`, now builds the options for both the card bind and the preload. It sets `skipMemoryCache(false)`, `override(w, h)` and an explicit `centerCrop()`. The URL comes from the same `ClickbaitRemover.updateThumbnail(...)` call, and size and quality come from the same presenter instance.
- **Behavior change:** cards now use the memory cache (`skipMemoryCache(false)`), where before they skipped it. Cards look the same.

**What changed**
- **`ThumbnailPreloader`** (new, in `tv/util`) remembers the last 300 URL-and-size pairs to avoid repeats. It calls `preload(w, h)` through the application context, so decoding runs on Glide's executors and nothing blocks the UI.
- **`VideoCardPresenter`** now uses the shared options and has a new `preload(Context, Video)`.
- **`MultipleRowsFragment`** (home and similar rows): when you focus a card, it preloads the next 6 cards in that row and the first 6 of the next 2 rows. It also preloads the first 6 cards of a newly created row.
- **`VideoGridFragment`** (channels, subscriptions, shorts grid): when you focus a card, it preloads the next 3 grid rows below it.

**Risks.** Preloaded bitmaps now sit in Glide's memory cache, about 18 images per focus move at card size. Rows that grow by adding more items to an existing row aren't preloaded until the next focus move. The report is in `.claude-task/REPORT.md`.
