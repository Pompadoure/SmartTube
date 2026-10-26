/*
 * Copyright (c) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import androidx.core.content.res.ResourcesCompat;
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

public class DetailsDescriptionPresenter extends AbstractDetailsDescriptionPresenter {

    @Override
    protected void onBindDescription(ViewHolder viewHolder, Object item) {
        Video video = (Video) item;

        if (video != null) {
            viewHolder.getTitle().setText(video.getTitle());
            viewHolder.getSubtitle().setText(video.getAuthor());
            viewHolder.getBody().setText(video.getSecondTitle());

            // JoTube M3: modern look - strong title, the channel and the details softer
            Context context = viewHolder.view.getContext();
            Typeface medium = ResourcesCompat.getFont(context, R.font.roboto_medium);
            Typeface regular = ResourcesCompat.getFont(context, R.font.roboto_regular);
            viewHolder.getTitle().setTypeface(medium);
            viewHolder.getTitle().setTextColor(Color.WHITE);
            viewHolder.getSubtitle().setTypeface(regular);
            viewHolder.getSubtitle().setTextColor(Color.parseColor("#E6FFFFFF"));
            viewHolder.getBody().setTypeface(regular);
            viewHolder.getBody().setTextColor(Color.parseColor("#B3FFFFFF"));
        }
    }
}
