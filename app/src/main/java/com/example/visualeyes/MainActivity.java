package com.example.visualeyes;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

/**
 * Hosts HomeFragment/MaterialsFragment/ProfileFragment as fragment-transaction
 * tabs (no back stack, a fresh fragment instance per switch — see
 * MaterialsDrawerController usage in each fragment for why) instead of the
 * three of them being separate Activities that finish() each other, so
 * switching tabs is an animated fragment swap instead of a full window relaunch.
 *
 * Also owns the pinch-zoom + triple-tap-to-repeat gestures that used to live in
 * each Activity's dispatchTouchEvent override — a Fragment can't override that,
 * so this forwards to whichever fragment is currently showing via TabFragment.
 */
public class MainActivity extends AppCompatActivity {

    private static final String[] TAB_ORDER = {"home", "materials", "profile"};
    private static final long NAV_SWITCH_DELAY_MS = 400L;

    private ImageView iconHome, iconMaterials, iconProfile;
    private TextView textHome, textMaterials, textProfile;
    private LinearLayout navHome, navMaterials, navProfile;

    private String currentTab = "home";
    private boolean isNavPending = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private android.view.ScaleGestureDetector scaleGestureDetector;
    private float currentZoomScale = 1.0f;
    private static final float MIN_ZOOM_SCALE = 1.0f;
    private static final float MAX_ZOOM_SCALE = 3.0f;

    private long lastTapTime = 0L;
    private int  tapCount    = 0;
    private static final long TRIPLE_TAP_WINDOW_MS = 600L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        iconHome      = findViewById(R.id.iconHome);
        iconMaterials = findViewById(R.id.iconMaterials);
        iconProfile   = findViewById(R.id.iconProfile);
        textHome      = findViewById(R.id.textHome);
        textMaterials = findViewById(R.id.textMaterials);
        textProfile   = findViewById(R.id.textProfile);
        navHome       = findViewById(R.id.navHome);
        navMaterials  = findViewById(R.id.navMaterials);
        navProfile    = findViewById(R.id.navProfile);

        navHome.setOnClickListener(v -> onNavTapped("home", "the home screen"));
        navMaterials.setOnClickListener(v -> onNavTapped("materials", "the materials screen"));
        navProfile.setOnClickListener(v -> onNavTapped("profile", "the profile screen"));

        setupZoomGesture();

        // The app was opened already logged in (no login this time): pull the account's saved voice
        // and speed once per launch. A fresh login already did this in LoginActivity.
        VoiceSettingsSync.refreshOncePerProcess(this);

        // One-time, in the background, a few seconds after the app opens (so it doesn't slow the
        // start): work out which phone voices are male or female, so the reader can follow the
        // student's chosen assistant voice.
        handler.postDelayed(() -> DeviceVoiceGuide.ensureClassified(this), 4000);

        if (savedInstanceState == null) {
            String startTab = getIntent().getStringExtra("startTab");
            currentTab = startTab != null ? startTab : "home";
            setActiveNav(currentTab);
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragmentContainer, createFragment(currentTab))
                    .commit();
        }
    }

    private void onNavTapped(String tab, String screenDescription) {
        if (isNavPending) return;

        Fragment current = currentFragment();

        if (tab.equals(currentTab)) {
            if (current instanceof TabFragment) {
                ((TabFragment) current).announceStillOnThisTab(
                        "You are currently on " + screenDescription + ".");
            }
            return;
        }

        isNavPending = true;
        if (current instanceof TabFragment) {
            ((TabFragment) current).speakBeforeLeaving("Opening " + tab + ".");
        }
        handler.postDelayed(() -> {
            isNavPending = false;
            switchTab(tab);
        }, NAV_SWITCH_DELAY_MS);
    }

    /** Package-visible so fragments can also switch tabs directly (voice commands). */
    void switchTab(String tab) {
        if (tab.equals(currentTab)) return;

        int fromIndex = indexOf(currentTab);
        int toIndex   = indexOf(tab);
        boolean forward = toIndex > fromIndex;

        currentTab = tab;
        currentZoomScale = 1.0f;
        setActiveNav(tab);

        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.setCustomAnimations(
                forward ? R.anim.slide_in_right : R.anim.slide_in_left,
                forward ? R.anim.slide_out_left : R.anim.slide_out_right);
        tx.replace(R.id.fragmentContainer, createFragment(tab));
        tx.commit();
    }

    private int indexOf(String tab) {
        for (int i = 0; i < TAB_ORDER.length; i++) {
            if (TAB_ORDER[i].equals(tab)) return i;
        }
        return 0;
    }

    private Fragment createFragment(String tab) {
        switch (tab) {
            case "materials": return new MaterialsFragment();
            case "profile":   return new ProfileFragment();
            default:          return new HomeFragment();
        }
    }

    private Fragment currentFragment() {
        return getSupportFragmentManager().findFragmentById(R.id.fragmentContainer);
    }

    private void setActiveNav(String tab) {
        int inactive = 0xFF8C4356, active = 0xFF2E0D18;
        iconHome.setColorFilter(inactive); iconMaterials.setColorFilter(inactive); iconProfile.setColorFilter(inactive);
        textHome.setTextColor(inactive);   textMaterials.setTextColor(inactive);   textProfile.setTextColor(inactive);
        if ("home".equals(tab))           { iconHome.setColorFilter(active);      textHome.setTextColor(active); }
        else if ("materials".equals(tab)) { iconMaterials.setColorFilter(active); textMaterials.setTextColor(active); }
        else if ("profile".equals(tab))   { iconProfile.setColorFilter(active);   textProfile.setTextColor(active); }
    }

    private void setupZoomGesture() {
        scaleGestureDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        Fragment f = currentFragment();
                        View zoomTarget = f != null ? f.getView() : null;
                        if (zoomTarget == null) return false;

                        currentZoomScale *= detector.getScaleFactor();
                        currentZoomScale = Math.max(MIN_ZOOM_SCALE, Math.min(currentZoomScale, MAX_ZOOM_SCALE));
                        if (currentZoomScale < 1.05f) currentZoomScale = 1.0f;
                        zoomTarget.setPivotX(detector.getFocusX());
                        zoomTarget.setPivotY(detector.getFocusY());
                        zoomTarget.setScaleX(currentZoomScale);
                        zoomTarget.setScaleY(currentZoomScale);
                        return true;
                    }
                });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (scaleGestureDetector != null) {
            scaleGestureDetector.onTouchEvent(ev);
        }
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            if (now - lastTapTime < TRIPLE_TAP_WINDOW_MS) {
                tapCount++;
            } else {
                tapCount = 1;
            }
            lastTapTime = now;
            if (tapCount >= 3) {
                tapCount = 0;
                Fragment current = currentFragment();
                if (current instanceof TabFragment) {
                    ((TabFragment) current).repeatLastInstruction();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
