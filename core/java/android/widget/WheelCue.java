/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package android.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.internal.R;

/**
 * A short cue about the wheel, such as how to turn a grabbed slider, shown beside the wheel
 * where the wheel is: level with its middle on the edge it is on at the screen's rotation, as
 * SystemUI's wheel guide shows its legend. Over the app, taking no touches. Main thread only.
 *
 * <p>A cue is a card of rows, each a gesture drawn small and a word or two for what it does
 * ({@link #card}), in the theme's accent and pointing at the wheel, so it reads at a glance.
 *
 * @hide
 */
public final class WheelCue {

    /** A turn of the wheel. */
    public static final int TURN = 0;
    /** A press of the side button. */
    public static final int PRESS = 1;
    /** A hold of the side button. */
    public static final int HOLD = 2;
    /** Two quick presses of the side button. */
    public static final int DOUBLE = 3;
    /** Three quick presses of the side button. */
    public static final int TRIPLE = 4;

    private static final long SHOW_MS = 2500;

    private static PopupWindow sShowing;

    private WheelCue() {
    }

    /**
     * A card of rows, {@code gestures[i]} drawn beside {@code texts[i]}, with a notch on its
     * {@code pointTo} side (a Gravity edge, or 0 for none) towards what it is about.
     */
    public static LinearLayout card(Context context, int[] gestures, CharSequence[] texts,
            int pointTo) {
        final float density = context.getResources().getDisplayMetrics().density;
        // In the theme's accent, as the user's colours have it
        final int ink = context.getColor(R.color.materialColorOnPrimary);
        final int notch = Math.round(7 * density);
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        final int pad = Math.round(10 * density);
        card.setPadding(pad + (pointTo == Gravity.LEFT ? notch : 0),
                Math.round(7 * density) + (pointTo == Gravity.TOP ? notch : 0),
                pad + Math.round(4 * density) + (pointTo == Gravity.RIGHT ? notch : 0),
                Math.round(7 * density) + (pointTo == Gravity.BOTTOM ? notch : 0));
        card.setBackground(new CardBackground(
                context.getColor(R.color.materialColorPrimary), 14 * density, notch,
                pointTo));
        card.setElevation(6 * density);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final int icon = Math.round(18 * density);
        final boolean rtl = context.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < texts.length; i++) {
            final TextView row = new TextView(context);
            row.setText(texts[i]);
            row.setTextColor(ink);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            row.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
            row.setSingleLine(true);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(Math.round(26 * density));
            final Drawable glyph = new GestureIcon(gestures[i], ink, density);
            glyph.setBounds(0, 0, icon, icon);
            // On its side as the language reads, set now: a relative one waits for the layout
            // direction, and a cue measured before it shows would leave it out
            if (rtl) {
                row.setCompoundDrawables(null, null, glyph, null);
            } else {
                row.setCompoundDrawables(glyph, null, null, null);
            }
            row.setCompoundDrawablePadding(Math.round(8 * density));
            card.addView(row);
        }
        return card;
    }

    /** Shows {@code text} beside the wheel, for the window {@code anchor} is in. */
    public static void show(View anchor, CharSequence text) {
        show(anchor, new int[] {TURN}, new CharSequence[] {text});
    }

    /** {@link #show(View, CharSequence)} with a string resource. */
    public static void show(View anchor, int text) {
        show(anchor, anchor.getContext().getText(text));
    }

    /**
     * Shows a turn's and a press's meaning, from string resources, beside the wheel, as a
     * grabbed slider's "Turn - set" and "Press - let go".
     */
    public static void show(View anchor, int turn, int press) {
        final Context context = anchor.getContext();
        show(anchor, new int[] {TURN, PRESS},
                new CharSequence[] {context.getText(turn), context.getText(press)});
    }

    /** Shows the card of {@code gestures} and {@code texts} beside the wheel. */
    public static void show(View anchor, int[] gestures, CharSequence[] texts) {
        show(anchor, gestures, texts, SHOW_MS);
    }

    /** Shows the card for {@code ms}, as a screen's first guidance that has more to say. */
    public static void show(View anchor, int[] gestures, CharSequence[] texts, long ms) {
        hide();
        if (!anchor.isAttachedToWindow() || anchor.getDisplay() == null) {
            Toast.makeText(anchor.getContext(), String.join(" - ", texts), Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        final PopupWindow popup = popup(anchor, gestures, texts);
        anchor.announceForAccessibility(String.join(", ", texts));
        sShowing = popup;
        popup.getContentView().postDelayed(() -> {
            if (sShowing == popup) {
                hide();
            }
        }, ms);
    }

    /**
     * Shows the card beside the wheel for as long as what it is about lasts, as the lock
     * screen's: the caller dismisses it, other cues come and go beside it. Null while
     * {@code anchor} has no window.
     */
    public static PopupWindow keep(View anchor, int[] gestures, CharSequence[] texts) {
        if (!anchor.isAttachedToWindow() || anchor.getDisplay() == null) {
            return null;
        }
        return popup(anchor, gestures, texts);
    }

    private static PopupWindow popup(View anchor, int[] gestures, CharSequence[] texts) {
        final Context context = anchor.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        final int rotation = anchor.getDisplay().getRotation();
        // The wheel's edge at the screen's rotation: right in portrait
        final int edge = switch (rotation) {
            case Surface.ROTATION_90 -> Gravity.TOP;
            case Surface.ROTATION_180 -> Gravity.LEFT;
            case Surface.ROTATION_270 -> Gravity.BOTTOM;
            default -> Gravity.RIGHT;
        };
        final View cue = card(context, gestures, texts, edge);
        cue.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

        final PopupWindow popup = new PopupWindow(cue, cue.getMeasuredWidth(),
                cue.getMeasuredHeight(), false);
        popup.setTouchable(false);
        popup.setClippingEnabled(false);
        popup.setAnimationStyle(R.style.Animation_Toast);

        // Where it goes on the screen, then in the window the anchor is in
        final Rect screen = context.getSystemService(WindowManager.class)
                .getCurrentWindowMetrics().getBounds();
        final WindowInsets windowInsets = anchor.getRootWindowInsets();
        final Insets bars = windowInsets != null
                ? windowInsets.getInsets(WindowInsets.Type.systemBars()) : Insets.NONE;
        float centre = context.getResources().getFraction(R.fraction.config_wheelCentre, 1, 1);
        // Turned over, the wheel's middle is as far from the other end
        if (rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270) {
            centre = 1 - centre;
        }
        final int margin = Math.round(6 * density);
        final int w = cue.getMeasuredWidth();
        final int h = cue.getMeasuredHeight();
        int x;
        int y;
        switch (edge) {
            case Gravity.TOP:
                x = Math.round(centre * screen.width()) - w / 2;
                y = bars.top + margin;
                break;
            case Gravity.LEFT:
                x = bars.left + margin;
                y = Math.round(centre * screen.height()) - h / 2;
                break;
            case Gravity.BOTTOM:
                x = Math.round(centre * screen.width()) - w / 2;
                y = screen.height() - bars.bottom - margin - h;
                break;
            default:
                x = screen.width() - bars.right - margin - w;
                y = Math.round(centre * screen.height()) - h / 2;
                break;
        }
        x = Math.max(margin, Math.min(x, screen.width() - margin - w));
        y = Math.max(margin, Math.min(y, screen.height() - margin - h));
        final View root = anchor.getRootView();
        final int[] at = new int[2];
        root.getLocationOnScreen(at);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x - at[0], y - at[1]);
        return popup;
    }

    /** Takes the cue away early, as when the grab ends. */
    public static void hide() {
        final PopupWindow showing = sShowing;
        sShowing = null;
        if (showing != null && showing.isShowing()) {
            try {
                showing.dismiss();
            } catch (IllegalArgumentException e) {
                // Its window already went with the app's
            }
        }
    }

    /** A rounded card, with a notch on one side pointing out of it. */
    private static final class CardBackground extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mBody = new RectF();
        private final float mRadius;
        private final int mNotch;
        private final int mSide;

        CardBackground(int color, float radius, int notch, int side) {
            mPaint.setColor(color);
            mRadius = radius;
            mNotch = notch;
            mSide = side;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            final int n = mNotch;
            mBody.set(bounds.left + (mSide == Gravity.LEFT ? n : 0),
                    bounds.top + (mSide == Gravity.TOP ? n : 0),
                    bounds.right - (mSide == Gravity.RIGHT ? n : 0),
                    bounds.bottom - (mSide == Gravity.BOTTOM ? n : 0));
            mPath.reset();
            mPath.addRoundRect(mBody, mRadius, mRadius, Path.Direction.CW);
            final float cx = mBody.centerX();
            final float cy = mBody.centerY();
            switch (mSide) {
                case Gravity.LEFT -> triangle(mBody.left + 1, cy - n, bounds.left, cy,
                        mBody.left + 1, cy + n);
                case Gravity.RIGHT -> triangle(mBody.right - 1, cy - n, bounds.right, cy,
                        mBody.right - 1, cy + n);
                case Gravity.TOP -> triangle(cx - n, mBody.top + 1, cx, bounds.top,
                        cx + n, mBody.top + 1);
                case Gravity.BOTTOM -> triangle(cx - n, mBody.bottom - 1, cx, bounds.bottom,
                        cx + n, mBody.bottom - 1);
                default -> { }
            }
        }

        private void triangle(float x0, float y0, float x1, float y1, float x2, float y2) {
            mPath.moveTo(x0, y0);
            mPath.lineTo(x1, y1);
            mPath.lineTo(x2, y2);
            mPath.close();
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(mPath, mPaint);
        }

        @Override
        public void getOutline(android.graphics.Outline outline) {
            outline.setRoundRect(Math.round(mBody.left), Math.round(mBody.top),
                    Math.round(mBody.right), Math.round(mBody.bottom), mRadius);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            mPaint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * A gesture drawn small: a turn as an arrow round, a press as the button, a hold as the
     * button with time going round it, a double press as two.
     */
    private static final class GestureIcon extends Drawable {
        private static final float TURN_START = -50;
        private static final float TURN_SWEEP = 280;
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mHead = new Path();
        private final RectF mArc = new RectF();
        private final int mGesture;

        GestureIcon(int gesture, int color, float density) {
            mGesture = gesture;
            mFill.setColor(color);
            mLine.setColor(color);
            mLine.setStyle(Paint.Style.STROKE);
            mLine.setStrokeWidth(2 * density);
            mLine.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            final float s = bounds.width();
            final float r = s * 0.36f;
            mArc.set(bounds.exactCenterX() - r, bounds.exactCenterY() - r,
                    bounds.exactCenterX() + r, bounds.exactCenterY() + r);
            mHead.reset();
            if (mGesture == TURN) {
                // The head at the arc's end, along it clockwise
                final double end = Math.toRadians(TURN_START + TURN_SWEEP);
                final float cos = (float) Math.cos(end);
                final float sin = (float) Math.sin(end);
                final float x = bounds.exactCenterX() + r * cos;
                final float y = bounds.exactCenterY() + r * sin;
                final float tx = -sin * s;
                final float ty = cos * s;
                mHead.moveTo(x + cos * s * 0.16f - tx * 0.02f, y + sin * s * 0.16f - ty * 0.02f);
                mHead.lineTo(x + tx * 0.2f, y + ty * 0.2f);
                mHead.lineTo(x - cos * s * 0.16f - tx * 0.02f, y - sin * s * 0.16f - ty * 0.02f);
                mHead.close();
            }
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect b = getBounds();
            final float cx = b.exactCenterX();
            final float cy = b.exactCenterY();
            final float s = b.width();
            switch (mGesture) {
                case TURN -> {
                    canvas.drawArc(mArc, TURN_START, TURN_SWEEP, false, mLine);
                    canvas.drawPath(mHead, mFill);
                }
                case PRESS -> canvas.drawCircle(cx, cy, s * 0.26f, mFill);
                case HOLD -> {
                    canvas.drawCircle(cx, cy, s * 0.18f, mFill);
                    canvas.drawArc(mArc, -90, 270, false, mLine);
                }
                case DOUBLE -> {
                    canvas.drawCircle(cx - s * 0.22f, cy, s * 0.17f, mFill);
                    canvas.drawCircle(cx + s * 0.22f, cy, s * 0.17f, mFill);
                }
                case TRIPLE -> {
                    canvas.drawCircle(cx - s * 0.32f, cy, s * 0.13f, mFill);
                    canvas.drawCircle(cx, cy, s * 0.13f, mFill);
                    canvas.drawCircle(cx + s * 0.32f, cy, s * 0.13f, mFill);
                }
                default -> { }
            }
        }

        @Override
        public void setAlpha(int alpha) {
            mFill.setAlpha(alpha);
            mLine.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            mFill.setColorFilter(filter);
            mLine.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
