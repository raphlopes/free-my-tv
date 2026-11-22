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

    // TV TCL : Android traite les touches de volume avant tout service de touches, et ne les répète plus dès qu'un
    // service filtre les touches (relevé le 22/11/2026). On lit donc l'état brut de la télécommande (getevent, par l'ADB
    // local) pour répéter le volume tant que la touche est tenue ; l'appui simple reste celui du système.
    private final android.os.Handler hold = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean watching;
    private volatile Adb watch;   // le flux en cours, fermé avec le service (sinon il resterait bloqué en lecture)

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (Maison.projo() || watching) return;
        watching = true;
        new Thread(() -> {
            while (watching) {
                try (Adb adb = Adb.connect(getApplicationContext(), 5_000, null)) {
                    watch = adb;
                    if (watching) adb.lines("getevent -q", this::rawKey);
                } catch (Exception e) {
                    AppLog.w("volume tenu : " + e.getMessage());
                }
                hold.post(() -> hold.removeCallbacksAndMessages(null));   // flux coupé : ne jamais laisser le volume monter seul
                android.os.SystemClock.sleep(5_000);
            }
        }, "volume").start();
    }

    @Override public void onDestroy() {
        watching = false;
        Adb adb = watch;
        if (adb != null) adb.close();
        hold.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Une ligne de getevent : « /dev/input/event7: 0001 0073 00000001 » (type touche, code, 1 = appui, 0 = relâchement). */
    private void rawKey(String line) {
        String[] f = line.split("\\s+");
        if (f.length < 4 || !f[1].equals("0001") || !(f[2].equals("0073") || f[2].equals("0072"))) return;
        boolean down = f[3].endsWith("1"), up = f[3].endsWith("0");
        int dir = f[2].equals("0073") ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER;
        if ((down || up) && watching) hold.post(() -> volumeHold(down, dir));
    }

    /** Touche de volume tenue : après 450 ms, un cran toutes les 80 ms jusqu'au relâchement (6 s au plus). */
    private void volumeHold(boolean down, int dir) {
        hold.removeCallbacksAndMessages(null);
        if (!down) return;
        long until = android.os.SystemClock.uptimeMillis() + 6_000;
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        hold.postDelayed(new Runnable() {
            @Override public void run() {
                if (android.os.SystemClock.uptimeMillis() > until) return;
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI);
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
