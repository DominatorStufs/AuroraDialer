/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2023-2025 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.contacts.common.list;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.dialer.util.DialerUtils;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import android.graphics.Typeface;

import com.aurora.dialer.R;

public class ViewPagerTabStrip extends LinearLayout implements ViewPager.OnPageChangeListener {

    private final Paint mSelectedUnderlinePaint;
    private int mIndexForSelection;
    private float mSelectionOffset;
    private ViewPager mViewPager;

    public ViewPagerTabStrip(Context context) {
        this(context, null);
    }

    public ViewPagerTabStrip(Context context, AttributeSet attrs) {
        super(context, attrs);
        int underlineColor = context.getResources().getColor(R.color.dialer_pill_color);

        mSelectedUnderlinePaint = new Paint();
        mSelectedUnderlinePaint.setAntiAlias(true);
        mSelectedUnderlinePaint.setColor(underlineColor);

        setBackgroundColor(Color.TRANSPARENT);
        setWillNotDraw(false);
    }


    public void setViewPager(ViewPager viewPager) {
        mViewPager = viewPager;
        if (viewPager != null) {
            viewPager.addOnPageChangeListener(this);
        }
        populateTabs();
    }

private void populateTabs() {
    final PagerAdapter adapter = mViewPager.getAdapter();
    removeAllViews(); // Clear any old tabs
    final int count = adapter.getCount();

    for (int i = 0; i < count; i++) {
        final CharSequence title = adapter.getPageTitle(i);

        final TextView textView = new TextView(getContext()) {
            @Override
            public void setSelected(boolean selected) {
                super.setSelected(selected);
                int colorId = selected
                        ? DialerUtils.resolveColor(getContext(), android.R.attr.textColorPrimaryInverse)
                        : DialerUtils.resolveColor(getContext(), android.R.attr.textColorPrimary);
                setTextColor(colorId);
            }
        };

        textView.setText(title);
        textView.setAllCaps(false);
        textView.setTypeface(Typeface.DEFAULT);
        textView.setTransformationMethod(null);
        textView.setGravity(Gravity.CENTER);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                0, LayoutParams.MATCH_PARENT, 1);
        textView.setLayoutParams(layoutParams);

        final int position = i;
        textView.setOnClickListener(v -> mViewPager.setCurrentItem(position));

        addView(textView);
    }

    onPageSelected(mViewPager.getCurrentItem());
}


    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
        mIndexForSelection = position;
        mSelectionOffset = positionOffset;
        invalidate();
    }

@Override
public void onPageSelected(int position) {
    for (int i = 0; i < getChildCount(); i++) {
        getChildAt(i).setSelected(i == position);
    }
}


    @Override
    public void onPageScrollStateChanged(int state) {
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int childCount = getChildCount();
        if (childCount > 0) {
            View selectedTitle = getChildAt(mIndexForSelection);
            if (selectedTitle == null) return;

            int selectedLeft = selectedTitle.getLeft();
            int selectedRight = selectedTitle.getRight();

            if (mSelectionOffset > 0.0f && mIndexForSelection < (getChildCount() - 1)) {
                View nextTitle = getChildAt(mIndexForSelection + 1);
                selectedLeft = (int) (mSelectionOffset * nextTitle.getLeft() +
                        (1.0f - mSelectionOffset) * selectedLeft);
                selectedRight = (int) (mSelectionOffset * nextTitle.getRight() +
                        (1.0f - mSelectionOffset) * selectedRight);
            }

            int height = getHeight();
            int padding = (int) (4 * getResources().getDisplayMetrics().density);
            RectF rect = new RectF(selectedLeft + padding, padding, selectedRight - padding, height - padding);
            float cornerRadius = height / 2f;
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, mSelectedUnderlinePaint);
        }
    }
}
