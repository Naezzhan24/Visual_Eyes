package com.example.visualeyes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Draws and animates the VisualED mark (eye + open book) using the same
 * geometry and palette as logo_visualed.png. Plays once as an intro: the
 * book opens (with a page-turn flourish), then the eye blinks, then it
 * settles on the static mark.
 */
public class IntroLogoView extends View {

    private final Paint lidWhitePaint = new Paint(Paint.ANTIALIAS_FLAG);
    private final Paint lidFillPaint = new Paint(Paint.ANTIALIAS_FLAG);
    private final Paint lidStrokePaint = new Paint(Paint.ANTIALIAS_FLAG);
    private final Paint irisPaint = new Paint(Paint.ANTIALIAS_FLAG);
    private final Paint pagePaint = new Paint(Paint.ANTIALIAS_FLAG);
    private final Paint pageStrokePaint = new Paint(Paint.ANTIALIAS_FLAG);
    private final Paint pageLinePaint = new Paint(Paint.ANTIALIAS_FLAG);
    private final Paint spinePaint = new Paint(Paint.ANTIALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTIALIAS_FLAG);

    private final Path eyePathOuter = new Path();
    private final Path eyePathWide = new Path();
    private final Path eyePathIris = new Path();
    private final Path leftPagePath = new Path();
    private final Path rightPagePath = new Path();
    private final RectF leftPageRect = new RectF();
    private final RectF rightPageRect = new RectF();
    private final float[] sparkLines = new float[16]; // 4 rays * 4 floats

    private float cx, cy, halfW, halfH, bookHalfWFull, bookHalfH, pageCornerRadius;

    // Animated state, updated by playIntro()'s chained animators.
    private float entranceAlpha = 0f;
    private float entranceScale = 0.8f;
    private float bookOpenProgress = 0f;
    private float pageLineAlpha = 0f;
    private float flipScaleX = 1f;
    private float blinkOpenness = 1f;

    @Nullable private Handler handler;

    public IntroLogoView(Context context) {
        super(context);
        init();
    }

    public IntroLogoView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        lidWhitePaint.setColor(Color.WHITE);
        lidWhitePaint.setStyle(Paint.Style.FILL);

        lidFillPaint.setStyle(Paint.Style.FILL);

        lidStrokePaint.setStyle(Paint.Style.STROKE);
        lidStrokePaint.setColor(ContextCompat.getColor(getContext(), R.color.color_logo_red_dark));

        irisPaint.setStyle(Paint.Style.FILL);
        irisPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_logo_maroon_deep));

        pagePaint.setColor(ContextCompat.getColor(getContext(), R.color.color_logo_page));
        pagePaint.setStyle(Paint.Style.FILL);

        pageStrokePaint.setStyle(Paint.Style.STROKE);
        pageStrokePaint.setColor(ContextCompat.getColor(getContext(), R.color.color_logo_red_dark));

        pageLinePaint.setStyle(Paint.Style.STROKE);
        pageLinePaint.setStrokeCap(Paint.Cap.ROUND);
        pageLinePaint.setColor(ContextCompat.getColor(getContext(), R.color.color_logo_page_line));

        spinePaint.setStyle(Paint.Style.STROKE);
        spinePaint.setStrokeCap(Paint.Cap.ROUND);
        spinePaint.setColor(ContextCompat.getColor(getContext(), R.color.color_logo_maroon_deep));

        sparkPaint.setStyle(Paint.Style.STROKE);
        sparkPaint.setStrokeCap(Paint.Cap.ROUND);
        sparkPaint.setColor(ContextCompat.getColor(getContext(), R.color.color_logo_red_light));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;

        cx = w / 2f;
        cy = h / 2f;

        float eyeW = w * 0.96f;
        float eyeH = Math.min(eyeW * 0.40f, h * 0.94f);
        halfW = eyeW / 2f;
        halfH = eyeH / 2f;

        buildEyePath(eyePathOuter, halfW, halfH);
        buildEyePath(eyePathWide, halfW * 1.08f, halfH * 1.12f);
        buildEyePath(eyePathIris, halfW * 0.80f, halfH * 0.74f);

        lidFillPaint.setShader(new LinearGradient(
                cx, cy - halfH, cx, cy + halfH,
                ContextCompat.getColor(getContext(), R.color.color_logo_red_light),
                ContextCompat.getColor(getContext(), R.color.color_logo_red_dark),
                Shader.TileMode.CLAMP));
        lidStrokePaint.setStrokeWidth(Math.max(2f, halfH * 0.05f));

        bookHalfWFull = halfW * 0.30f;
        bookHalfH = halfH * 0.56f;
        pageCornerRadius = Math.max(4f, bookHalfWFull * 0.18f);

        pageStrokePaint.setStrokeWidth(Math.max(1.5f, halfH * 0.03f));
        pageLinePaint.setStrokeWidth(Math.max(1.5f, halfH * 0.035f));
        spinePaint.setStrokeWidth(Math.max(2f, halfH * 0.055f));
        sparkPaint.setStrokeWidth(Math.max(2f, halfH * 0.045f));

        // Light-ray spark fanning out from the eye's right inner corner.
        float sx = cx + halfW * 0.60f;
        float sy = cy;
        float[] angles = {-38f, -13f, 13f, 38f};
        float rayLen = halfH * 0.55f;
        float rayStart = halfH * 0.15f;
        for (int i = 0; i < angles.length; i++) {
            double rad = Math.toRadians(angles[i]);
            float dx = (float) Math.cos(rad);
            float dy = (float) Math.sin(rad);
            sparkLines[i * 4] = sx + dx * rayStart;
            sparkLines[i * 4 + 1] = sy + dy * rayStart;
            sparkLines[i * 4 + 2] = sx + dx * rayLen;
            sparkLines[i * 4 + 3] = sy + dy * rayLen;
        }
    }

    /** Builds a flat almond ("eye") path centered at (cx, cy) with the given half-extents. */
    private void buildEyePath(Path path, float hw, float hh) {
        float lx = cx - hw;
        float rx = cx + hw;
        float peak = hh * 1.25f;

        path.reset();
        path.moveTo(lx, cy);
        path.cubicTo(cx - hw * 0.5f, cy - peak, cx + hw * 0.5f, cy - peak, rx, cy);
        path.cubicTo(cx + hw * 0.5f, cy + peak, cx - hw * 0.5f, cy + peak, lx, cy);
        path.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;

        int layerAlpha = (int) (255 * clamp01(entranceAlpha));
        int saveCount = canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), layerAlpha);

        canvas.save();
        canvas.scale(entranceScale, entranceScale, cx, cy);
        canvas.scale(1f, clamp(blinkOpenness, 0.045f, 1f), cx, cy);

        canvas.drawPath(eyePathWide, lidWhitePaint);
        canvas.drawPath(eyePathOuter, lidFillPaint);
        canvas.drawPath(eyePathOuter, lidStrokePaint);
        canvas.drawPath(eyePathIris, irisPaint);

        if (bookOpenProgress > 0.3f) {
            sparkPaint.setAlpha((int) (255 * clamp01((bookOpenProgress - 0.3f) / 0.5f)));
            canvas.drawLines(sparkLines, sparkPaint);
        }

        drawBook(canvas);

        canvas.restore();
        canvas.restoreToCount(saveCount);
    }

    private void drawBook(Canvas canvas) {
        float bookHalfW = bookHalfWFull * bookOpenProgress;
        if (bookHalfW < 0.5f) {
            canvas.drawLine(cx, cy - bookHalfH, cx, cy + bookHalfH, spinePaint);
            return;
        }

        leftPageRect.set(cx - bookHalfW, cy - bookHalfH, cx, cy + bookHalfH);
        rightPageRect.set(cx, cy - bookHalfH, cx + bookHalfW, cy + bookHalfH);

        roundedPage(leftPagePath, leftPageRect, true);
        roundedPage(rightPagePath, rightPageRect, false);

        canvas.drawPath(leftPagePath, pagePaint);
        canvas.drawPath(leftPagePath, pageStrokePaint);
        canvas.drawPath(rightPagePath, pagePaint);
        canvas.drawPath(rightPagePath, pageStrokePaint);

        if (pageLineAlpha > 0f) {
            pageLinePaint.setAlpha((int) (255 * pageLineAlpha));
            drawPageLines(canvas, leftPageRect, false);
            drawPageLines(canvas, rightPageRect, true);
        }

        // Page-turn flourish: the right leaf briefly rotates edge-on and
        // darkens like a shadowed page, then lands back down.
        canvas.save();
        canvas.scale(flipScaleX, 1f, cx, cy);
        float shade = 1f - Math.abs(flipScaleX);
        int flipColor = blendColor(
                ContextCompat.getColor(getContext(), R.color.color_logo_page),
                ContextCompat.getColor(getContext(), R.color.color_logo_page_shadow),
                shade);
        pagePaint.setColor(flipColor);
        canvas.drawPath(rightPagePath, pagePaint);
        pagePaint.setColor(ContextCompat.getColor(getContext(), R.color.color_logo_page));
        canvas.restore();

        canvas.drawLine(cx, cy - bookHalfH, cx, cy + bookHalfH, spinePaint);
    }

    private void roundedPage(Path out, RectF rect, boolean leftPage) {
        float r = pageCornerRadius;
        float[] radii = leftPage
                ? new float[]{r, r, 0, 0, 0, 0, r, r}
                : new float[]{0, 0, r, r, r, r, 0, 0};
        out.reset();
        out.addRoundRect(rect, radii, Path.Direction.CW);
    }

    private void drawPageLines(Canvas canvas, RectF rect, boolean nearSpine) {
        float width = rect.right - rect.left;
        float pad = width * 0.22f;
        float xStart = nearSpine ? rect.left + pad * 0.6f : rect.left + pad;
        float xEnd = nearSpine ? rect.right - pad : rect.right - pad * 0.6f;
        int lines = 3;
        for (int i = 0; i < lines; i++) {
            float frac = (i + 1f) / (lines + 1f);
            float y = rect.top + (rect.bottom - rect.top) * frac;
            canvas.drawLine(xStart, y, xEnd, y, pageLinePaint);
        }
    }

    private static int blendColor(int from, int to, float ratio) {
        float r = clamp01(ratio);
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * r);
        int red = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * r);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * r);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * r);
        return Color.argb(a, red, g, b);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Plays the intro sequence once: entrance pop-in, book opening with a
     * page-turn flourish, then a double blink. Invokes {@code onFinished}
     * on the main thread when the whole sequence completes.
     */
    public void playIntro(@Nullable Runnable onFinished) {
        handler = new Handler(Looper.getMainLooper());
        post(() -> startEntrance(onFinished));
    }

    /** Skips straight to the settled, fully-open static mark. */
    public void skipToEnd() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        entranceAlpha = 1f;
        entranceScale = 1f;
        bookOpenProgress = 1f;
        pageLineAlpha = 1f;
        flipScaleX = 1f;
        blinkOpenness = 1f;
        invalidate();
    }

    private void startEntrance(@Nullable Runnable onFinished) {
        ValueAnimator entrance = ValueAnimator.ofFloat(0f, 1f);
        entrance.setDuration(420);
        entrance.setInterpolator(new OvershootInterpolator(1.3f));
        entrance.addUpdateListener(a -> {
            entranceAlpha = a.getAnimatedFraction();
            entranceScale = 0.8f + 0.2f * (float) a.getAnimatedValue();
            invalidate();
        });
        entrance.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startBookOpen(onFinished);
            }
        });
        entrance.start();
    }

    private void startBookOpen(@Nullable Runnable onFinished) {
        ValueAnimator bookOpen = ValueAnimator.ofFloat(0f, 1f);
        bookOpen.setDuration(650);
        bookOpen.setInterpolator(new DecelerateInterpolator(1.4f));
        bookOpen.addUpdateListener(a -> {
            bookOpenProgress = (float) a.getAnimatedValue();
            pageLineAlpha = clamp01((bookOpenProgress - 0.55f) / 0.45f);
            invalidate();
        });
        bookOpen.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startPageFlip(onFinished);
            }
        });
        bookOpen.start();
    }

    private void startPageFlip(@Nullable Runnable onFinished) {
        ValueAnimator flip = ValueAnimator.ofFloat(1f, 0f, 1f);
        flip.setDuration(420);
        flip.setInterpolator(new AccelerateDecelerateInterpolator());
        flip.addUpdateListener(a -> {
            flipScaleX = (float) a.getAnimatedValue();
            invalidate();
        });
        flip.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (handler != null) handler.postDelayed(() -> startBlink(onFinished), 150);
            }
        });
        flip.start();
    }

    private void startBlink(@Nullable Runnable onFinished) {
        // Two quick blinks: open -> closed -> open -> (hold) -> closed -> open.
        Keyframe kf0 = Keyframe.ofFloat(0f, 1f);
        Keyframe kf1 = Keyframe.ofFloat(0.24f, 0.05f);
        Keyframe kf2 = Keyframe.ofFloat(0.42f, 1f);
        Keyframe kf3 = Keyframe.ofFloat(0.58f, 1f);
        Keyframe kf4 = Keyframe.ofFloat(0.80f, 0.05f);
        Keyframe kf5 = Keyframe.ofFloat(1f, 1f);
        PropertyValuesHolder pvh = PropertyValuesHolder.ofKeyframe("blink", kf0, kf1, kf2, kf3, kf4, kf5);
        ValueAnimator blink = ValueAnimator.ofPropertyValuesHolder(pvh);
        blink.setDuration(950);
        blink.addUpdateListener(a -> {
            blinkOpenness = (float) a.getAnimatedValue("blink");
            invalidate();
        });
        blink.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onFinished != null) onFinished.run();
            }
        });
        blink.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }
}
