package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        return RemoteKeys.onKey(this, event);
    }

    private final android.os.Handler hold = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Touche de volume tenue : après 450 ms, un cran toutes les 80 ms jusqu'au relâchement (6 s au plus). */
    void volumeHold(KeyEvent e) {
        hold.removeCallbacksAndMessages(null);   // relâchement, autre touche, ou répétition du système : on arrête
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return;
        int dir = e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER;
        long until = android.os.SystemClock.uptimeMillis() + 6_000;
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        hold.postDelayed(new Runnable() {
            @Override public void run() {
                if (android.os.SystemClock.uptimeMillis() > until) return;
                am.adjustSuggestedStreamVolume(dir, android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI);
                hold.postDelayed(this, 80);
            }
        }, 450);
    }

    private PanelView panel;

    /** Le panneau de contrôle dans une fenêtre du service, par-dessus l'appli en cours (qui continue de tourner). */
    void togglePanel() {
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        if (panel != null) {
            panel.toggle();
            return;
        }
        Ui.init(this);
        panel = new PanelView(this, () -> {
            if (panel == null || panel.isOpen()) return;   // rouvert pendant qu'il sortait
            wm.removeView(panel);
            panel = null;
        });
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(panel.widthPx(), panel.heightPx(),
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        lp.y = panel.topPx();
        wm.addView(panel, lp);
        panel.requestFocus();
        panel.open();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Une autre fenêtre arrive (touche Accueil, appli lancée) : le panneau ne reste pas par-dessus.
        if (panel != null && panel.isOpen() && event.getPackageName() != null
                && !getPackageName().contentEquals(event.getPackageName())) panel.close();
    }

    @Override public void onInterrupt() {}
}
