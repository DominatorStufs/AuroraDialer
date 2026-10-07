package com.android.dialer.app.settings;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;

import com.aurora.dialer.R;

public class CardPreference extends Preference {
    public CardPreference(Context context) {
        super(context);
        init();
    }

    public CardPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayoutResource(R.layout.preference_card_item);
    }
}