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
 * Draws and animates the VisualED mark (eye + open book) by matching
 * logo_visualed.png layer for layer: a bright outer lid ribbon, a white
 * crease gap, a darker inner ribbon, then a deep maroon fill holding the
 * book and its light-burst. Plays once as an intro: the book opens (with
 * a page-turn flourish), then the eye blinks, then it settles on the
 * static mark.
 */
public class IntroLogoView extends View {

    private static final int PETAL_COUNT = 5;
    private static final float[] PETAL_ANGLES_DEG = {-52f, -26f, -2f, 20f, 42f};
    private static final float[] PETAL_LEN_FACTOR = {0.60f, 0.72f, 0.64f, 0.54f, 0.46f};
    private static final float[] PETAL_WIDTH_FACTOR = {0.13f, 0.16f, 0.15f, 0.13f, 0.11f};

    private final Paint outerRedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gapWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerRedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillMaroonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pageStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pageLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] petalPaints = new Paint[PETAL_COUNT];

    private final Path eyeOuterRed = new Path();
    private final Path eyeGapWhite = new Path();
    private final Path eyeInnerRed = new Path();
    private final Path eyeFillMaroon = new Path();
    private final Path leftPagePath = new Path();
    private final Path rightPagePath = new Path();
    private final Path[] petalPaths = new Path[PETAL_COUNT];

    private float cx, cy, halfW, halfH;
    private float bookHalfWFull, bookTopY, bookBottomY, bookNotchDepth, bookOuterBulge;

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
        outerRedPaint.setStyle(Paint.Style.FILL);
        gapWhitePaint.setStyle(Paint.Style.FILL);
        gapWhitePaint.setColor(Color.WHITE);
        innerRedPaint.setStyle(Paint.Style.FILL);
        fillMaroonPaint.setStyle(Paint.Style.FILL);

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

        for (int i = 0; i < PETAL_COUNT; i++) {
            petalPaths[i] = new Path();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            petalPaints[i] = p;
        }
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

        // Four nested almond layers, painted back-to-front, reproduce the
        // logo's banded lid: outer ribbon -> white crease -> inner ribbon
        // -> deep fill. Each layer just needs to be a bit smaller than the
        // one behind it for its band to peek through.
        buildEyePath(eyeOuterRed, halfW, halfH);
        buildEyePath(eyeGapWhite, halfW * 0.88f, halfH * 0.86f);
        buildEyePath(eyeInnerRed, halfW * 0.80f, halfH * 0.76f);
        buildEyePath(eyeFillMaroon, halfW * 0.66f, halfH * 0.60f);

        outerRedPaint.setShader(new LinearGradient(
                cx, cy - halfH, cx, cy + halfH * 0.3f,
                ContextCompat.getColor(getContext(), R.color.color_logo_red_light),
                ContextCompat.getColor(getContext(), R.color.color_logo_red_dark),
                Shader.TileMode.CLAMP));
        innerRedPaint.setShader(new LinearGradient(
                cx, cy - halfH * 0.7f, cx, cy + halfH * 0.7f,
                ContextCompat.getColor(getContext(), R.color.color_logo_red_dark),
                ContextCompat.getColor(getContext(), R.color.color_logo_maroon_deep),
                Shader.TileMode.CLAMP));
        fillMaroonPaint.setShader(new LinearGradient(
                cx, cy - halfH * 0.55f, cx, cy + halfH * 0.55f,
                blend(ContextCompat.getColor(getContext(), R.color.color_logo_maroon_deep), Color.BLACK, 0.0f),
                blend(ContextCompat.getColor(getContext(), R.color.color_logo_maroon_deep), Color.BLACK, 0.35f),
                Shader.TileMode.CLAMP));

        bookHalfWFull = halfW * 0.34f;
        bookTopY = cy - halfH * 0.60f;
        bookBottomY = cy + halfH * 0.60f;
        bookNotchDepth = (bookBottomY - bookTopY) * 0.34f;
        bookOuterBulge = bookHalfWFull * 0.10f;

        pageStrokePaint.setStrokeWidth(Math.max(1.5f, halfH * 0.03f));
        pageLinePaint.setStrokeWidth(Math.max(1.5f, halfH * 0.032f));
        spinePaint.setStrokeWidth(Math.max(2f, halfH * 0.05f));

        buildPetals();
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

    /**
     * Builds one open-book page as a "bowtie" petal: the inner (spine) edge
     * is pulled into a sharp notch top and bottom, the outer edge bulges
     * gently outward, matching the book silhouette in the logo.
     */
    private void buildPagePath(Path path, boolean leftPage, float halfBookW) {
        float spineX = cx;
        float outerX = leftPage ? cx - halfBookW : cx + halfBookW;
        float sign = leftPage ? -1f : 1f;
        float midY = (bookTopY + bookBottomY) / 2f;

        path.reset();
        path.moveTo(outerX, bookTopY);
        path.quadTo(spineX, bookTopY, spineX, bookTopY + bookNotchDepth);
        path.lineTo(spineX, bookBottomY - bookNotchDepth);
        path.quadTo(spineX, bookBottomY, outerX, bookBottomY);
        path.quadTo(outerX + sign * bookOuterBulge, midY, outerX, bookTopY);
        path.close();
    }

    private void buildPetals() {
        float baseX = cx + halfW * 0.58f;
        float baseY = cy - halfH * 0.02f;
        float unit = halfH;

        for (int i = 0; i < PETAL_COUNT; i++) {
            double rad = Math.toRadians(PETAL_ANGLES_DEG[i]);
            float dx = (float) Math.cos(rad);
            float dy = (float) Math.sin(rad);
            float px = -dy;
            float py = dx;

            float length = unit * PETAL_LEN_FACTOR[i];
            float width = unit * PETAL_WIDTH_FACTOR[i];

            float tipX = baseX + dx * length;
            float tipY = baseY + dy * length;
            float ctrlOutX = baseX + dx * length * 0.62f + px * (width * 0.42f);
            float ctrlOutY = baseY + dy * length * 0.62f + py * (width * 0.42f);
            float ctrlInX = baseX + dx * length * 0.62f - px * (width * 0.42f);
            float ctrlInY = baseY + dy * length * 0.62f - py * (width * 0.42f);
            float capCtrlX = tipX + dx * (width * 0.20f);
            float capCtrlY = tipY + dy * (width * 0.20f);
            float tipLX = tipX + px * (width / 2f);
            float tipLY = tipY + py * (width / 2f);
            float tipRX = tipX - px * (width / 2f);
            float tipRY = tipY - py * (width / 2f);

            Path p = petalPaths[i];
            p.reset();
            p.moveTo(baseX, baseY);
            p.quadTo(ctrlOutX, ctrlOutY, tipLX, tipLY);
            p.quadTo(capCtrlX, capCtrlY, tipRX, tipRY);
            p.quadTo(ctrlInX, ctrlInY, baseX, baseY);
            p.close();

            petalPaints[i].setShader(new LinearGradient(
                    baseX, baseY, tipX, tipY,
                    ContextCompat.getColor(getContext(), R.color.color_logo_maroon_deep),
                    ContextCompat.getColor(getContext(), R.color.color_logo_red_light),
                    Shader.TileMode.CLAMP));
        }
    }

    private static int blend(int color, int toward, float ratio) {
        float r = clamp01(ratio);
        int red = (int) (Color.red(color) + (Color.red(toward) - Color.red(color)) * r);
        int g = (int) (Color.green(color) + (Color.green(toward) - Color.green(color)) * r);
        int b = (int) (Color.blue(color) + (Color.blue(toward) - Color.blue(color)) * r);
        return Color.argb(255, red, g, b);
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

        canvas.drawPath(eyeOuterRed, outerRedPaint);
        canvas.drawPath(eyeGapWhite, gapWhitePaint);
        canvas.drawPath(eyeInnerRed, innerRedPaint);
        canvas.drawPath(eyeFillMaroon, fillMaroonPaint);

        if (bookOpenProgress > 0.25f) {
            int alpha = (int) (255 * clamp01((bookOpenProgress - 0.25f) / 0.55f));
            for (int i = 0; i < PETAL_COUNT; i++) {
                petalPaints[i].setAlpha(alpha);
                canvas.drawPath(petalPaths[i], petalPaints[i]);
            }
        }

        drawBook(canvas);

        canvas.restore();
        canvas.restoreToCount(saveCount);
    }

    private void drawBook(Canvas canvas) {
        float bookHalfW = bookHalfWFull * bookOpenProgress;
        if (bookHalfW < 0.5f) {
            canvas.drawLine(cx, bookTopY, cx, bookBottomY, spinePaint);
            return;
        }

        buildPagePath(leftPagePath, true, bookHalfW);
        buildPagePath(rightPagePath, false, bookHalfW);

        canvas.drawPath(leftPagePath, pagePaint);
        canvas.drawPath(leftPagePath, pageStrokePaint);
        canvas.drawPath(rightPagePath, pagePaint);
        canvas.drawPath(rightPagePath, pageStrokePaint);

        if (pageLineAlpha > 0f) {
            pageLinePaint.setAlpha((int) (255 * pageLineAlpha));
            drawPageLines(canvas, true, bookHalfW);
            drawPageLines(canvas, false, bookHalfW);
        }

        // Page-turn flourish: the right leaf briefly rotates edge-on and
        // darkens like a shadowed page, then lands back down.
        canvas.save();
        canvas.scale(flipScaleX, 1f, cx, cy);
        float shade = 1f - Math.abs(flipScaleX);
        int flipColor = blend(
                ContextCompat.getColor(getContext(), R.color.color_logo_page),
                ContextCompat.getColor(getContext(), R.color.color_logo_page_shadow),
                shade);
        pagePaint.setColor(flipColor);
        canvas.drawPath(rightPagePath, pagePaint);
        pagePaint.setColor(ContextCompat.getColor(getContext(), R.color.color_logo_page));
        canvas.restore();

        canvas.drawLine(cx, bookTopY + bookNotchDepth * 0.9f, cx, bookBottomY - bookNotchDepth * 0.9f, spinePaint);
    }

    private void drawPageLines(Canvas canvas, boolean leftPage, float halfBookW) {
        float spineX = cx;
        float outerX = leftPage ? cx - halfBookW : cx + halfBookW;
        float pad = halfBookW * 0.24f;
        float xOuter = leftPage ? outerX + pad : outerX - pad;
        float xSpine = leftPage ? spineX - pad * 0.55f : spineX + pad * 0.55f;

        int lines = 3;
        for (int i = 0; i < lines; i++) {
            float frac = (i + 1f) / (lines + 1f);
            float y = bookTopY + (bookBottomY - bookTopY) * frac;
            float droop = (y - cy) * -0.20f;
            Path line = new Path();
            line.moveTo(xOuter, y);
            line.quadTo((xOuter + xSpine) / 2f, y, xSpine, y + droop);
            canvas.drawPath(line, pageLinePaint);
        }
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
