/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.dialer.dialpadview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

/**
 * This is a custom text view intended for rendering text on the dialpad. TextView has built-in
 * top/bottom padding to help account for ascenders/descenders.
 *
 * <p>Since vertical space is at a premium on the dialpad, particularly if the font size is scaled
 * to a larger default, for the dialpad we use this class to more precisely render characters
 * according to the precise amount of space they need.
 */
public class DialpadTextView extends AppCompatTextView {

  private final Rect textBounds = new Rect();
  private String textStr;

  public DialpadTextView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  /** Draw the text to fit within the height/width which have been specified during measurement. */
  @Override
  public void draw(Canvas canvas) {
    Paint paint = getPaint();

    // Without this, the draw does not respect the style's specified text color.
    paint.setColor(getCurrentTextColor());

    canvas.drawText(textStr, 0, -textBounds.top, paint);
  }

  /**
   */
  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    textStr = getText().toString();
    Paint paint = getPaint();
    paint.getTextBounds(textStr, 0, textStr.length(), textBounds);

    int width = resolveSize((int) Math.ceil(paint.measureText(textStr)), widthMeasureSpec);
    int height = resolveSize(textBounds.height(), heightMeasureSpec);
    setMeasuredDimension(width, height);
  }
}
