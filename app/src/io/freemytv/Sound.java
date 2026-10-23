package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCL: where the sound goes, the TV's speakers or the optical output (a soundbar). TCL's own settings app writes
 * `settings global tcl_device_out_type` (2 = speakers, 32 = optical) and its HDMI service follows it, so writing it is
 * the whole switch. Through the TV's own ADB: apps targeting Android 12+ can't read TCL's keys themselves.
 */
final class Sound {
    private Sound() {}

    static final String SPEAKERS = "2", OPTICAL = "32";

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** The output last read, or null: unknown yet, ADB not allowed, or not a TCL (then the drawer has no button). */
    private static volatile String out;

    static String out() { return out; }

    /** Reads the output in the background; `done` runs on the main thread when it changed. */
    static void refresh(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        io.execute(() -> {
            String now = read(app);
            if (now == null ? out == null : now.equals(out)) return;
            out = now;
            main.post(done);
        });
    }

    /** Speakers ⇄ optical; `done` runs on the main thread with whether it worked. */
    static void toggle(Context c, java.util.function.Consumer<Boolean> done) {
        Context app = c.getApplicationContext();
        String next = OPTICAL.equals(out) ? SPEAKERS : OPTICAL;
        io.execute(() -> {
            boolean ok;
            try (Adb adb = Adb.connect(app, 5_000, null)) {
                adb.shell("settings put global tcl_device_out_type " + next);
                ok = true;
            } catch (Exception e) {
                AppLog.w("sound: " + e.getMessage());
                ok = false;
            }
            String now = read(app);
            out = now;
            boolean result = ok && next.equals(now);
            main.post(() -> done.accept(result));
        });
    }

    private static String read(Context app) {
        try (Adb adb = Adb.connect(app, 5_000, null)) {
            String v = adb.shell("settings get global tcl_device_out_type").trim();
            return SPEAKERS.equals(v) || OPTICAL.equals(v) ? v : null;
        } catch (Exception e) {
            return null;
        }
    }
}
