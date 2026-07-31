package com.example.visualeyes;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A small mic icon with two "sonar" rings that pulse outward on a loop, plus a
 * subtle breathing scale on the mic itself driven by setAmplitude() (fed from
 * SpeechRecognizer's onRmsChanged). All motion is done with standard View
 * property animators (ObjectAnimator on scaleX/scaleY/alpha) rather than a
 * manual per-frame Canvas redraw — much lighter on the main thread, so it
 * doesn't compete with the recognizer for CPU while it's listening.
 *
 * setAmplitude() only ever writes a field; the actual visual reaction is
 * applied on a throttled ~180ms tick, not on every callback.
 */
public class MicWaveView extends FrameLayout {

    private static final int  CORE_COLOR   = 0xFF8C4356;
    private static final long RING_DURATION_MS = 1700L;
    private static final long RING_STAGGER_MS  = 550L;
    private static final long AMPLITUDE_TICK_MS = 180L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View ring1;
    private View ring2;
    private ImageView icon;

    private ObjectAnimator ring1Animator;
    private ObjectAnimator ring2Animator;

    private volatile float latestAmplitude = 0f; // 0..1, written by setAmplitude()
    private boolean running = false;

    private final Runnable amplitudeTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            float target = 1f + Math.min(1f, Math.max(0f, latestAmplitude)) * 0.22f;
            icon.animate().scaleX(target).scaleY(target)
                    .setDuration(AMPLITUDE_TICK_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            mainHandler.postDelayed(this, AMPLITUDE_TICK_MS);
        }
    };

    public MicWaveView(Context context) { super(context); init(); }
    public MicWaveView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public MicWaveView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        int coreSize = dp(26);
        int ringSize = dp(26);

        ring2 = new View(getContext());
        ring2.setBackgroundResource(R.drawable.bg_mic_ring);
        addView(ring2, gravityCenter(ringSize, ringSize));

        ring1 = new View(getContext());
        ring1.setBackgroundResource(R.drawable.bg_mic_ring);
        addView(ring1, gravityCenter(ringSize, ringSize));

        View core = new View(getContext());
        core.setBackgroundResource(R.drawable.bg_mic_core);
        addView(core, gravityCenter(coreSize, coreSize));

        icon = new ImageView(getContext());
        icon.setImageResource(android.R.drawable.ic_btn_speak_now);
        icon.setColorFilter(0xFFFFFFFF);
        int iconSize = dp(15);
        addView(icon, gravityCenter(iconSize, iconSize));

        ring1.setAlpha(0f);
        ring2.setAlpha(0f);
        setVisibility(GONE);
    }

    private LayoutParams gravityCenter(int w, int h) {
        LayoutParams lp = new LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private ObjectAnimator buildRingAnimator(View ring, long startDelay) {
        PropertyValuesHolder sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.5f);
        PropertyValuesHolder sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.5f);
        PropertyValuesHolder a  = PropertyValuesHolder.ofFloat(View.ALPHA, 0.45f, 0f);
        ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(ring, sx, sy, a);
        anim.setDuration(RING_DURATION_MS);
        anim.setStartDelay(startDelay);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new DecelerateInterpolator());
        return anim;
    }

    /** Begin the ripple + reactive pulse. Call once actual listening starts. */
    public void start() {
        if (running) return;
        running = true;
        setVisibility(VISIBLE);
        latestAmplitude = 0f;
        icon.setScaleX(1f);
        icon.setScaleY(1f);

        ring1Animator = buildRingAnimator(ring1, 0L);
        ring2Animator = buildRingAnimator(ring2, RING_STAGGER_MS);
        ring1Animator.start();
        ring2Animator.start();

        mainHandler.postDelayed(amplitudeTick, AMPLITUDE_TICK_MS);
    }

    /** Stop and hide. Call once listening ends. */
    public void stop() {
        running = false;
        mainHandler.removeCallbacks(amplitudeTick);
        if (ring1Animator != null) { ring1Animator.cancel(); ring1Animator = null; }
        if (ring2Animator != null) { ring2Animator.cancel(); ring2Animator = null; }
        ring1.setAlpha(0f);
        ring2.setAlpha(0f);
        icon.animate().cancel();
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        setVisibility(GONE);
    }

    /**
     * Feed live volume, straight from SpeechRecognizer's onRmsChanged(). Cheap by
     * design — only stores the value; the visual update happens on the throttled tick.
     */
    public void setAmplitude(float rms) {
        latestAmplitude = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
