package com.android.dialer.app.calllog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.android.dialer.aurora.AuroraPreferences;
import com.android.dialer.aurora.SocialActionHelper;
import com.android.dialer.widget.SwipeAndDragHelper;

public class CallLogSwipeHelper extends SwipeAndDragHelper {

    private static final float CARD_CORNER_RADIUS_DP = 32f;
    private static final float CARD_INNER_CORNER_RADIUS_DP = 1f;

    private static final float SWIPE_THRESHOLD = 0.35f;
    private static final float SWIPE_ESCAPE_VELOCITY_MULTIPLIER = 1.5f;
    private static final float MIN_SWIPE_ALPHA = 0.4f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF background = new RectF();
    private final Path backgroundPath = new Path();
    private final float[] radii = new float[8];

    public CallLogSwipeHelper(Context context, ActionCompletionContract contract) {
        super(contract);
    }

    @Override
    public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
        if (viewHolder instanceof CallLogListItemViewHolder) {
            Context context = recyclerView.getContext();
            if (!AuroraPreferences.isSwipeActionsEnabled(context)) {
                return 0;
            }
            int rightAction = AuroraPreferences.getSwipeRightAction(context);
            int leftAction = AuroraPreferences.getSwipeLeftAction(context);
            int swipeFlags = 0;
            if (rightAction != AuroraPreferences.SWIPE_ACTION_NONE) {
                swipeFlags |= ItemTouchHelper.RIGHT;
            }
            if (leftAction != AuroraPreferences.SWIPE_ACTION_NONE) {
                swipeFlags |= ItemTouchHelper.LEFT;
            }
            return makeMovementFlags(0, swipeFlags);
        }
        return 0;
    }

    @Override
    public float getSwipeThreshold(RecyclerView.ViewHolder viewHolder) {
        return SWIPE_THRESHOLD;
    }

    @Override
    public float getSwipeEscapeVelocity(float defaultValue) {
        return defaultValue * SWIPE_ESCAPE_VELOCITY_MULTIPLIER;
    }

    @Override
    public void onChildDraw(Canvas c, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
                            float dX, float dY, int actionState, boolean isCurrentlyActive) {

        if (!(viewHolder instanceof CallLogListItemViewHolder)) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            return;
        }

        CallLogListItemViewHolder holder = (CallLogListItemViewHolder) viewHolder;
        View itemView = holder.itemView;
        View cardView = holder.callLogEntryView;
        Context context = cardView.getContext();

        float cardLeft = itemView.getLeft() + cardView.getLeft();
        float cardTop = itemView.getTop() + cardView.getTop();
        float cardRight = cardLeft + cardView.getWidth();
        float cardBottom = cardTop + cardView.getHeight();

        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0) {
            boolean isSwipingRight = dX > 0;
            int actionId = isSwipingRight
                    ? AuroraPreferences.getSwipeRightAction(context)
                    : AuroraPreferences.getSwipeLeftAction(context);

            float backgroundLeft = isSwipingRight ? cardLeft : cardLeft + dX;
            float backgroundRight = isSwipingRight ? cardRight + dX : cardRight;

            // Clip so that drawing cannot bleed onto neighbouring rows.
            c.save();
            c.clipRect(backgroundLeft, cardTop, backgroundRight, cardBottom);

            paint.setColor(SocialActionHelper.getActionColor(context, actionId));

            background.set(backgroundLeft, cardTop, backgroundRight, cardBottom);
            setCornerRadii(context, holder);
            backgroundPath.rewind();
            backgroundPath.addRoundRect(background, radii, Path.Direction.CW);
            c.drawPath(backgroundPath, paint);

            drawActionIcon(c, context, cardView, itemView, cardTop, dX, isSwipingRight, actionId);

            c.restore();
        }

        float progress = Math.min(1f, Math.abs(dX) / (float) Math.max(1, cardView.getWidth()));
        cardView.setAlpha(1f - (1f - MIN_SWIPE_ALPHA) * progress);
        cardView.setTranslationX(dX);
    }

    private void setCornerRadii(Context context, CallLogListItemViewHolder holder) {
        float outer = dpToPx(context, CARD_CORNER_RADIUS_DP);
        float inner = dpToPx(context, CARD_INNER_CORNER_RADIUS_DP);

        float top = holder.isFirstInDateGroup ? outer : inner;
        float bottom = holder.isLastInDateGroup ? outer : inner;

        radii[0] = radii[1] = top;
        radii[2] = radii[3] = top;
        radii[4] = radii[5] = bottom;
        radii[6] = radii[7] = bottom;
    }

    private void drawActionIcon(Canvas c, Context context, View cardView, View itemView,
                                float cardTop, float dX, boolean isSwipingRight, int actionId) {
        int iconRes = SocialActionHelper.getActionIconRes(actionId);
        if (iconRes == 0) {
            return;
        }
        Drawable icon = ContextCompat.getDrawable(context, iconRes);
        if (icon == null) {
            return;
        }

        icon = icon.mutate();
        icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);

        int iconSize = icon.getIntrinsicHeight();
        int iconMargin = (cardView.getHeight() - iconSize) / 2;
        int iconAreaWidth = iconSize + (2 * iconMargin);

        float reveal = Math.min(1f, Math.abs(dX) / (float) Math.max(1, iconAreaWidth));
        icon.setAlpha((int) (reveal * 255));

        int top = (int) (cardTop + iconMargin);
        int bottom = top + iconSize;
        if (isSwipingRight) {
            int left = itemView.getLeft() + iconMargin;
            icon.setBounds(left, top, left + iconSize, bottom);
        } else {
            int right = itemView.getRight() - iconMargin;
            icon.setBounds(right - iconSize, top, right, bottom);
        }
        icon.draw(c);
    }

    private static float dpToPx(Context context, float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    @Override
    public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
        if (viewHolder instanceof CallLogListItemViewHolder) {
            View cardView = ((CallLogListItemViewHolder) viewHolder).callLogEntryView;
            cardView.setTranslationX(0f);
            cardView.setAlpha(1.0f);
        }
        super.clearView(recyclerView, viewHolder);
    }
}
