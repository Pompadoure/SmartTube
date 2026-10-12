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

import android.graphics.Color;
import android.graphics.Typeface;
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

public class DetailsDescriptionPresenter extends AbstractDetailsDescriptionPresenter {

    @Override
    protected void onBindDescription(ViewHolder viewHolder, Object item) {
        Video video = (Video) item;

        if (video != null) {
            viewHolder.getTitle().setText(video.getTitle());
            viewHolder.getSubtitle().setText(video.getAuthor());
            viewHolder.getBody().setText(video.getSecondTitle());

            // JoTube M3: modern look - strong title, the channel and the details softer
            viewHolder.getTitle().setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            viewHolder.getTitle().setTextColor(Color.WHITE);
            viewHolder.getSubtitle().setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            viewHolder.getSubtitle().setTextColor(Color.parseColor("#E6FFFFFF"));
            viewHolder.getBody().setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            viewHolder.getBody().setTextColor(Color.parseColor("#B3FFFFFF"));
        }
    }
}
