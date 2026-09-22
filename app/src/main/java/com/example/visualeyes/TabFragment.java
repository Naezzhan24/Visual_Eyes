package com.example.visualeyes;

/**
 * Implemented by HomeFragment/MaterialsFragment/ProfileFragment so MainActivity's
 * shared bottom nav can announce tab switches through whichever fragment's own
 * TTS is currently live, without MainActivity owning any voice engine itself.
 */
interface TabFragment {
    /** Speak before this fragment is torn down for a tab switch (no listen-after). */
    void speakBeforeLeaving(String text);

    /** Speak while staying on this same fragment (listens again afterward). */
    void announceStillOnThisTab(String text);

    /** Triple-tap-anywhere gesture, forwarded from MainActivity.dispatchTouchEvent
     *  since only an Activity (not a Fragment) can override that method. */
    void repeatLastInstruction();
}
