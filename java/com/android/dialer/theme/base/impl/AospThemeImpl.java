/*
 * Copyright (C) 2018 The Android Open Source Project
 * Copyright (C) 2023-2025 The LineageOS Project
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

package com.android.dialer.theme.base.impl;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

import androidx.annotation.ColorInt;
import androidx.annotation.StyleRes;

import com.aurora.dialer.R;
import com.android.dialer.common.Assert;
import com.android.dialer.theme.base.Theme;

import javax.inject.Singleton;

/** Utility for fetching */
@SuppressWarnings("unused")
@Singleton
public class AospThemeImpl implements Theme {

  private final Context context;

  public AospThemeImpl(Context context) {
    this.context = context.getApplicationContext();
  }

  /**
   * Returns the {@link Theme} that the application is using. Activities should check this value if
   * their custom style needs to customize further based on the application theme.
   */
  @Override
  public @Type int getTheme() {
    int currentNightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    switch (currentNightMode) {
        case Configuration.UI_MODE_NIGHT_NO:
            return LIGHT;
        case Configuration.UI_MODE_NIGHT_YES:
            return DARK;
        default:
            return LIGHT;
    }
  }

  @Override
  public @StyleRes int getApplicationThemeRes() {
    switch (getTheme()) {
      case DARK:
        return R.style.Dialer_Dark_ThemeBase_NoActionBar;
      case LIGHT:
        return R.style.Dialer_ThemeBase_NoActionBar;
      case UNKNOWN:
      default:
        throw Assert.createIllegalStateFailException("Theme hasn't been set yet.");
    }
  }

  @Override
  public Context getThemedContext(Context context) {
    return new ContextThemeWrapper(context, getApplicationThemeRes());
  }

  @Override
  public LayoutInflater getThemedLayoutInflator(LayoutInflater inflater) {
    return inflater.cloneInContext(getThemedContext(inflater.getContext()));
  }

  @Override
  public @ColorInt int getColorIcon() {
    return resolveColor(R.attr.colorIcon);
  }

  @Override
  public @ColorInt int getColorIconSecondary() {
    return resolveColor(R.attr.colorIconSecondary);
  }

  @Override
  public @ColorInt int getColorPrimary() {
    return resolveColor(android.R.attr.colorPrimary);
  }

  @Override
  public int getColorPrimaryDark() {
    return resolveColor(android.R.attr.colorPrimaryDark);
  }

  @Override
  public @ColorInt int getColorAccent() {
    return resolveColor(android.R.attr.colorAccent);
  }

  @Override
  public @ColorInt int getTextColorSecondary() {
    return resolveColor(android.R.attr.textColorSecondary);
  }

  @Override
  public @ColorInt int getTextColorPrimary() {
    return resolveColor(android.R.attr.textColorPrimary);
  }

  @Override
  public @ColorInt int getColorTextOnUnthemedDarkBackground() {
    return resolveColor(R.attr.colorTextOnUnthemedDarkBackground);
  }

  @Override
  public @ColorInt int getColorIconOnUnthemedDarkBackground() {
    return resolveColor(R.attr.colorIconOnUnthemedDarkBackground);
  }

  @Override
  public @ColorInt int getColorCallNotificationBackground() {
    return resolveColor(R.attr.colorCallNotificationBackground);
  }

  private int resolveColor(int attr) {
    final TypedArray a = context.obtainStyledAttributes(new int[]{attr});
    try {
      return a.getColor(0, 0);
    } finally {
      a.recycle();
    }
  }
}