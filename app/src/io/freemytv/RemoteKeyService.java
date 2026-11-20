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
