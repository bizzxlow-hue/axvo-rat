package com.xnullcat.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Warna, ikon, dan helper tampilan putih untuk seluruh aplikasi. */
final class Ui {
    static final int BG = 0xFFF3F5F8;
    static final int SURFACE = 0xFFFFFFFF;
    static final int EDGE = 0xFFE3E7EE;
    static final int INK = 0xFF1B1F27;
    static final int SOFT = 0xFF8B93A1;
    static final int GREEN = 0xFF1F9A57;

    static final int HOME = 0, PHONE = 1, NEWS = 2, GRID = 3, BACK = 4, LOCK = 5, SCREEN = 6, BT = 7, WARN = 8, CAMERA = 9;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable box(Context c, int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) {
            g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        }
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold, boolean mono) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(mono ? Typeface.MONOSPACE : Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    static void pressable(View v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                x.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                x.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            }
            return false;
        });
    }

    static void clipRound(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    /** Lingkaran putih kecil berisi ikon. */
    static FrameLayout chip(Context c, int kind) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(SURFACE);
        g.setStroke(Math.max(1, dp(c, 1)), EDGE);
        f.setBackground(g);
        f.setElevation(dp(c, 3));
        f.setClickable(true);
        f.addView(new IconView(c, kind, INK), new FrameLayout.LayoutParams(dp(c, 20), dp(c, 20), Gravity.CENTER));
        return f;
    }

    static LinearLayout sectionHead(Context c, int kind, String title) {
        return sectionHead(c, kind, title, null);
    }

    /** Sama seperti di atas, tetapi lingkaran ikonnya bisa ditekan. */
    static LinearLayout sectionHead(Context c, int kind, String title, View.OnClickListener onChip) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout chip = chip(c, kind);
        if (onChip != null) {
            chip.setClickable(true);
            chip.setOnClickListener(onChip);
            pressable(chip);
        } else {
            chip.setClickable(false);
        }
        row.addView(chip, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        TextView t = text(c, title.toUpperCase(), 15, INK, true, false);
        t.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 14);
        row.addView(t, lp);
        return row;
    }

    /** Bingkai dengan tinggi mengikuti rasio lebar / tinggi. */
    static final class AspectFrame extends FrameLayout {
        private final float ratio;

        AspectFrame(Context c, float ratio) {
            super(c);
            this.ratio = ratio;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = Math.round(width / ratio);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView yang membungkus tingginya sendiri sampai batas tertentu, lalu bisa discroll. */
    static final class CappedScrollView extends ScrollView {
        private final int maxH;

        CappedScrollView(Context c, int maxH) {
            super(c);
            this.maxH = maxH;
            setVerticalScrollBarEnabled(false);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        }
    }

    /** Ikon garis sederhana, digambar di kotak 24 x 24. */
    static final class IconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;
        private int color;

        IconView(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.color = color;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            cv.save();
            cv.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
            cv.scale(s, s);
            p.setColor(color);
            p.setStrokeWidth(2f);
            path.reset();
            switch (kind) {
                case HOME:
                    path.moveTo(3, 11);
                    path.lineTo(12, 3);
                    path.lineTo(21, 11);
                    cv.drawPath(path, p);
                    path.reset();
                    path.moveTo(5, 10);
                    path.lineTo(5, 20);
                    path.lineTo(10, 20);
                    path.lineTo(10, 14);
                    path.lineTo(14, 14);
                    path.lineTo(14, 20);
                    path.lineTo(19, 20);
                    path.lineTo(19, 10);
                    cv.drawPath(path, p);
                    break;
                case PHONE:
                    cv.drawRoundRect(7, 2.5f, 17, 21.5f, 2.5f, 2.5f, p);
                    cv.drawLine(11, 18.5f, 13, 18.5f, p);
                    break;
                case NEWS:
                    cv.drawRoundRect(3, 4, 21, 20, 3, 3, p);
                    cv.drawLine(3, 9, 21, 9, p);
                    cv.drawLine(7, 14, 12, 14, p);
                    break;
                case GRID:
                    cv.drawRoundRect(4, 4, 11, 11, 2, 2, p);
                    cv.drawRoundRect(13, 4, 20, 11, 2, 2, p);
                    cv.drawRoundRect(4, 13, 11, 20, 2, 2, p);
                    cv.drawRoundRect(13, 13, 20, 20, 2, 2, p);
                    break;
                case BACK:
                    path.moveTo(15, 5);
                    path.lineTo(8, 12);
                    path.lineTo(15, 19);
                    cv.drawPath(path, p);
                    break;
                case LOCK:
                    cv.drawRoundRect(5, 11, 19, 21, 2.5f, 2.5f, p);
                    path.moveTo(8, 11);
                    path.lineTo(8, 8);
                    path.arcTo(new RectF(8, 4, 16, 12), 180, 180);
                    path.lineTo(16, 11);
                    cv.drawPath(path, p);
                    break;
                case SCREEN:
                    cv.drawRoundRect(3, 4, 21, 16, 2, 2, p);
                    cv.drawLine(8, 20, 16, 20, p);
                    cv.drawLine(12, 16, 12, 20, p);
                    break;
                case BT:
                    path.moveTo(6.5f, 7.5f);
                    path.lineTo(17.5f, 16.5f);
                    path.lineTo(12, 21);
                    path.lineTo(12, 3);
                    path.lineTo(17.5f, 7.5f);
                    path.lineTo(6.5f, 16.5f);
                    cv.drawPath(path, p);
                    break;
                case WARN:
                    path.moveTo(12, 3.5f);
                    path.lineTo(21.5f, 20);
                    path.lineTo(2.5f, 20);
                    path.close();
                    cv.drawPath(path, p);
                    cv.drawLine(12, 10, 12, 14.5f, p);
                    cv.drawPoint(12, 17.3f, p);
                    break;
                case CAMERA:
                    path.moveTo(9, 5);
                    path.lineTo(8, 7);
                    path.lineTo(3.5f, 7);
                    path.lineTo(3.5f, 19.5f);
                    path.lineTo(20.5f, 19.5f);
                    path.lineTo(20.5f, 7);
                    path.lineTo(16, 7);
                    path.lineTo(15, 5);
                    path.close();
                    cv.drawPath(path, p);
                    cv.drawCircle(12, 13, 3.6f, p);
                    break;
                default:
                    break;
            }
            cv.restore();
        }
    }
}
