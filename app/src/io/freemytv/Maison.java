package io.freemytv;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Données de l'accueil maison (MaisonView) : « Reprendre » (la file Watch Next du TvProvider), les apps récentes
 * (UsageStats) et la rangée Salon (le hub, qui garde le jeton Home Assistant : la TV n'en a pas).
 *
 * Droits accordés une fois par ADB (rien ne plante sans eux, la rangée reste juste vide) :
 *   pm grant io.freemytv android.permission.READ_TV_LISTINGS
 *   appops set io.freemytv GET_USAGE_STATS allow
 */
final class Maison {
    private Maison() {}

    /** Un titre en cours, tel qu'une app l'a publié dans Watch Next. */
    static final class Resume {
        String pkg, app, title, meta, intentUri, image;
        float progress = -1;        // 0..1, ou -1 : inconnu
        long engaged;               // dernière lecture (epoch ms)
    }

    static boolean projo() {
        String d = Config.DEVICE.trim().toLowerCase(java.util.Locale.ROOT);
        if (d.equals("projo")) return true;
        if (d.equals("tv")) return false;
        return Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT).contains("xgimi")
                || Build.BRAND.toLowerCase(java.util.Locale.ROOT).contains("xgimi");
    }

    // MARK: - Reprendre

    private static final String[] COLS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
    };

    /** La file « Reprendre », la plus récente d'abord. Vide sans READ_TV_LISTINGS (on ne voit alors que nos lignes). */
    static List<Resume> resume(Context c, int max) {
        List<Resume> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLS, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < max) {
                if (cur.getInt(12) == 0) continue;   // retiré par l'utilisateur
                Resume r = new Resume();
                r.pkg = cur.getString(0);
                r.title = cur.getString(1);
                r.intentUri = cur.getString(5);
                if (r.pkg == null || r.title == null || r.intentUri == null || r.pkg.equals(c.getPackageName())) continue;
                if (!seen.add(r.pkg + "\n" + r.title)) continue;   // une série = une carte, son dernier épisode
                String episode = cur.getString(2), season = cur.getString(3), number = cur.getString(4);
                StringBuilder meta = new StringBuilder();
                if (season != null && !season.isEmpty()) meta.append("Saison").append(' ').append(season);
                if (number != null && !number.isEmpty()) {
                    if (meta.length() > 0) meta.append(" · ");
                    meta.append("Épisode").append(' ').append(number);
                }
                if (episode != null && !episode.isEmpty() && meta.length() == 0) meta.append(episode);
                long position = cur.getLong(8), duration = cur.getLong(9);
                if (duration > 0 && position > 0) {
                    r.progress = Math.min(1f, (float) position / duration);
                    long left = (duration - position) / 60_000L;
                    if (left > 0) {
                        if (meta.length() > 0) meta.append(" · ");
                        meta.append(left).append(" min restantes");
                    }
                }
                r.meta = meta.toString();
                r.image = cur.getString(7) != null ? cur.getString(7) : cur.getString(6);   // 16:9 d'abord
                r.engaged = cur.getLong(10);
                r.app = label(c, r.pkg);
                out.add(r);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            AppLog.w("reprendre : " + e.getMessage());
        }
        return out;
    }

    // MARK: - Apps récentes

    /**
     * Les apps ouvertes ces 14 derniers jours, la plus récente d'abord, parmi celles qu'on peut lancer. Vide sans le
     * droit GET_USAGE_STATS : la rangée Apps garde alors l'ordre de l'étagère.
     */
    static List<String> recent(Context c, Set<String> launchable, int max) {
        List<String> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        List<UsageStats> stats;
        try {
            stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 14 * 86_400_000L, now);
        } catch (SecurityException e) {
            return out;
        }
        if (stats == null) return out;
        Map<String, Long> last = new HashMap<>();
        for (UsageStats s : stats) {
            if (s.getTotalTimeInForeground() < 60_000L) continue;   // un passage d'une seconde ne compte pas
            String pkg = s.getPackageName();
            if (!launchable.contains(pkg) || pkg.equals(c.getPackageName())) continue;
            Long t = last.get(pkg);
            if (t == null || s.getLastTimeUsed() > t) last.put(pkg, s.getLastTimeUsed());
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
        Collections.sort(sorted, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> e : sorted) {
            if (out.size() >= max) break;
            out.add(e.getKey());
        }
        return out;
    }

    static String label(Context c, String pkg) {
        try {
            return String.valueOf(c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    // MARK: - Salon (via le hub)

    private static final ExecutorService io = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    static boolean hasHub() {
        return !Config.HUB_URL.trim().isEmpty();
    }

    /** POST {hub}/api/salon/{action} ; `done` reçoit true si le hub a lancé le script, sur le fil principal. */
    static void salon(String action, Consumer<Boolean> done) {
        io.execute(() -> {
            boolean ok = false;
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(Config.HUB_URL.trim().replaceAll("/+$", "") + "/api/salon/" + action).openConnection();
                h.setRequestMethod("POST");
                h.setConnectTimeout(3_000);
                h.setReadTimeout(5_000);
                h.setDoOutput(true);
                h.getOutputStream().close();
                // 200 ne suffit pas : un hub plus ancien répond 200 avec sa page web à toute adresse inconnue.
                if (h.getResponseCode() == 200) {
                    try (InputStream in = h.getInputStream()) {
                        byte[] buf = new byte[512];
                        int n = in.read(buf);
                        ok = n > 0 && new org.json.JSONObject(new String(buf, 0, n, "UTF-8")).optBoolean("ok");
                    } catch (org.json.JSONException e) {
                        ok = false;
                    }
                }
                if (!ok) AppLog.w("salon " + action + " : HTTP " + h.getResponseCode() + ", pas la réponse du hub");
            } catch (IOException e) {
                AppLog.w("salon " + action + " : " + e.getMessage());
            } finally {
                if (h != null) h.disconnect();
            }
            boolean result = ok;
            main.post(() -> done.accept(result));
        });
    }

    // MARK: - Images (affiches Watch Next : content:// ou https)

    private static final Map<String, Bitmap> IMAGES = Collections.synchronizedMap(new java.util.LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > 12; }
    });
    private static final Set<String> LOADING = Collections.synchronizedSet(new HashSet<>());

    /** L'image si elle est prête ; sinon la charge en fond (à la taille demandée) et appelle `ready` ensuite. */
    static Bitmap image(Context c, String uri, int width, Runnable ready) {
        if (uri == null) return null;
        Bitmap b = IMAGES.get(uri);
        if (b != null || !LOADING.add(uri)) return b;
        ContentResolver cr = c.getContentResolver();
        io.execute(() -> {
            try {
                byte[] data = read(cr, uri);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= width) o.inSampleSize *= 2;
                Bitmap img = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (img != null) {
                    IMAGES.put(uri, img);
                    main.post(ready);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("image reprendre : " + e.getMessage());
            } finally {
                LOADING.remove(uri);
            }
        });
        return null;
    }

    private static byte[] read(ContentResolver cr, String uri) throws IOException {
        Uri u = Uri.parse(uri);
        InputStream in;
        HttpURLConnection h = null;
        if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
            h = (HttpURLConnection) new URL(uri).openConnection();
            h.setConnectTimeout(5_000);
            h.setReadTimeout(10_000);
            in = h.getInputStream();
        } else {
            in = cr.openInputStream(u);
            if (in == null) throw new IOException("pas de flux");
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16_384];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8_000_000) throw new IOException("image trop lourde");
            }
            return out.toByteArray();
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
