package com.android.dialer.app.calllog;

import android.os.Bundle;
import android.provider.CallLog.Calls;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.contacts.common.list.ViewPagerTabStrip;
import com.aurora.dialer.R;
import com.android.dialer.database.CallLogQueryHandler;
import com.google.android.material.tabs.TabLayout;
import android.text.SpannableString;

public class NewCallLogFragment extends Fragment {

private TabLayout tabLayout;
    private static final int TAB_INDEX_ALL = 0;
    private static final int TAB_INDEX_MISSED = 1;
    private static final int TAB_INDEX_COUNT = 2;

    private ViewPager viewPager;
    private ViewPagerTabStrip viewPagerTabStrip;
    private ViewPagerAdapter viewPagerAdapter;
    private String[] tabTitles;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_call_log_fragment, container, false);

        tabTitles = new String[TAB_INDEX_COUNT];
        tabTitles[0] = "All";
        tabTitles[1] = "Missed";

        viewPager = view.findViewById(R.id.new_call_log_pager);
        tabLayout = requireActivity().findViewById(R.id.tabs);

        viewPagerAdapter = new ViewPagerAdapter(getChildFragmentManager());
        viewPager.setAdapter(viewPagerAdapter);
        viewPager.setOffscreenPageLimit(1);

        tabLayout.setupWithViewPager(viewPager);

        return view;
    }

    public class ViewPagerAdapter extends FragmentPagerAdapter {

        public ViewPagerAdapter(FragmentManager fm) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case TAB_INDEX_ALL:
                    return new CallLogFragment(CallLogQueryHandler.CALL_TYPE_ALL, false);
                case TAB_INDEX_MISSED:
                    return new CallLogFragment(Calls.MISSED_TYPE, false);
                default:
                    throw new IllegalStateException("No fragment at position " + position);
            }
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return new SpannableString(tabTitles[position]);
        }

        @Override
        public int getCount() {
            return TAB_INDEX_COUNT;
        }
    }
}
