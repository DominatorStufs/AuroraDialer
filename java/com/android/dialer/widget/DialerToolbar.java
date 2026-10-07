/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
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

package com.android.dialer.widget;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;

import com.aurora.dialer.R;
import com.android.dialer.theme.base.ThemeComponent;

import android.content.res.Resources;
import android.util.AttributeSet;
import android.util.TypedValue;
import androidx.core.content.ContextCompat;
import androidx.appcompat.widget.Toolbar;
/** Toolbar widget for Dialer. */
public class DialerToolbar extends Toolbar {

  private final TextView title;
  private final BidiTextView subtitle;

  public DialerToolbar(Context context, @Nullable AttributeSet attributeSet) {
    super(context, attributeSet);
    inflate(context, R.layout.dialer_toolbar, this);
    title = (TextView) findViewById(R.id.title);
    subtitle = (BidiTextView) findViewById(R.id.subtitle);

        TypedValue typedValue = new TypedValue();
        Resources.Theme theme = context.getTheme();
        theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true);
        int color = ContextCompat.getColor(context, typedValue.resourceId);

        setBackgroundColor(color);

    setElevation(getResources().getDimensionPixelSize(R.dimen.toolbar_elevation));

    setNavigationIcon(R.drawable.quantum_ic_close_vd_theme_24);
    setNavigationContentDescription(R.string.toolbar_close);
    setNavigationOnClickListener(v -> ((Activity) context).finish());
    setPaddingRelative(
        getPaddingStart(),
        getPaddingTop(),
        getResources().getDimensionPixelSize(R.dimen.toolbar_end_padding),
        getPaddingBottom());
  }

  @Override
  public void setTitle(@StringRes int id) {
    setTitle(getResources().getString(id));
  }

  @Override
  public void setTitle(CharSequence charSequence) {
    title.setText(charSequence);
  }

  @Override
  public void setSubtitle(@StringRes int id) {
    setSubtitle(getResources().getString(id));
  }

  @Override
  public void setSubtitle(CharSequence charSequence) {
    if (charSequence != null) {
      subtitle.setText(charSequence);
      subtitle.setVisibility(VISIBLE);
    }
  }
}
