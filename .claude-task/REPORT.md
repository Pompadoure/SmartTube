# Card thumbnail preload (JoTube)

## Where images are loaded
`VideoCardPresenter.onBindViewHolder` (also used by `TinyCardPresenter`, `ShortsCardPresenter`) loads
`ClickbaitRemover.updateThumbnail(video, thumbQuality)` into the card's main ImageView (`widget_preview_card.xml`, `scaleType=centerCrop`) with:
`ViewUtil.glideOptions()` (diskCache NONE + **skipMemoryCache(true)**), `.override(mWidth, mHeight)`, `.diskCacheStrategy(ALL)` (API > 21), no explicit transformation.

Findings that matter for the cache key:
- `glideOptions()` sets `skipMemoryCache(true)`, so cards never used Glide's memory cache. A preload with the same options would only warm the disk cache.
  I added `.skipMemoryCache(false)` for the card request and the preload. Thumbnails are static, so the "restart animation" reason doesn't apply here.
  This is the only behavior change. Cards look the same.
- `into(ImageView)` implicitly adds `CenterCrop` because of the view's scaleType. A preload has no ImageView, so it would have a different key. Both requests now set `.centerCrop()` explicitly.

## Cache-key match
`ThumbnailPreloader.applyCardOptions(builder, w, h)` is the single place that applies the options (glideOptions, skipMemoryCache(false), override(w,h), centerCrop, diskCacheStrategy).
The bind and the preload both call it, so they share model URL, size, transformation and options. The URL comes from the same `ClickbaitRemover.updateThumbnail(video, quality)`.
w/h/quality are taken from the same presenter instance (`VideoCardPresenter.preload`). The `adapter.getPresenter(item)` call picks the shorts or normal presenter per row, so sizes match.
The request listener and `.error()` fallback aren't part of the key.

## Changes
- New `tv/util/ThumbnailPreloader`: shared options, LRU dedup (300 url+size entries), `Glide.with(appContext)...preload(w,h)`. Decoding runs on Glide executors and nothing blocks the UI thread. Failures are swallowed.
- `VideoCardPresenter`: bind uses `applyCardOptions`; new public `preload(Context, Video)`.
- `MultipleRowsFragment` (home etc.): on item selection, preloads the next 6 cards of the focused row and the first 6 of the next 2 rows. Also preloads the first 6 when a new row is created.
- `VideoGridFragment` (channel uploads, subscriptions, shorts grid): on selection, preloads the next 3 grid rows (`cols * 3` items) after the focused item.

## Risks
- Not compiled or run. Leanback/Glide 4.11 APIs used: `ObjectAdapter.getPresenter`, `RequestBuilder.preload(int,int)`, `centerCrop()`, `skipMemoryCache(false)`.
- Memory cache is now used by cards (preloaded bitmaps sit in Glide's LRU, sized by Glide defaults). That's about 18 images per focus move at card size.
- Rows continued by `existingAdapter.add(group)` aren't preloaded directly. They get preloaded on the next selection.
- Preloads of network images happen on focus moves (dedup limits repeats). Disk cache is limited to 10 MB (`GlideCachingModule`).
- Unused imports `VERSION`/`DiskCacheStrategy` may remain in `VideoCardPresenter` (harmless).
