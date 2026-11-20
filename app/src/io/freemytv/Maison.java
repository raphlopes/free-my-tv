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
        String wide;                // la même image en grand (fond d'écran), si elle existe
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

    // MARK: - Accueil Google (projo)

    /**
     * Sur le projo, l'accueil Google se déclare HOME en priorité 2 et passe devant le nôtre (0, plafond des applis
     * installées) : il doit rester désactivé. Or xrmservice (XGIMI) le réactive à chaque touche YouTube/Netflix de la
     * télécommande. Tant que nous sommes l'accueil, on le redésactive par l'ADB local (Adb, accepté une fois à l'écran).
     * Pas sur la TV : launcherx y reste actif.
     */
    static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx";
    private static final java.util.concurrent.atomic.AtomicBoolean disabling = new java.util.concurrent.atomic.AtomicBoolean();
    private static boolean watching;

    /** Depuis l'accueil : surveille les réactivations (une fois par processus) et vérifie tout de suite. */
    static synchronized void watchGoogleHome(Context c) {
        if (!projo()) return;
        if (!watching) {
            watching = true;
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            f.addDataSchemeSpecificPart(GOOGLE_HOME, android.os.PatternMatcher.PATTERN_LITERAL);
            c.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context context, android.content.Intent intent) { keepGoogleHomeOff(context); }
            }, f);
        }
        keepGoogleHomeOff(c);
    }

    static void keepGoogleHomeOff(Context c) {
        if (!projo() || Build.VERSION.SDK_INT < 29) return;
        final Context app = c.getApplicationContext();
        android.app.role.RoleManager roles = app.getSystemService(android.app.role.RoleManager.class);
        if (roles == null || !roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) || !enabled(app, GOOGLE_HOME)) return;
        if (!disabling.compareAndSet(false, true)) return;
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 60_000, null)) {
                AppLog.w("accueil Google réactivé, désactivé à nouveau : " + adb.shell("pm disable-user --user 0 " + GOOGLE_HOME).trim());
            } catch (IOException e) {
                AppLog.w("accueil Google : " + e.getMessage());
            } finally {
                disabling.set(false);
            }
        }, "accueil-google").start();
    }

    private static boolean enabled(Context c, String pkg) {
        try {
            int s = c.getPackageManager().getApplicationEnabledSetting(pkg);
            return s == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || s == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (IllegalArgumentException e) {
            return false;   // absent (ou invisible)
        }
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
                if (duration > 0 && position > 0) r.progress = Math.min(1f, (float) position / duration);
                left(meta, position, duration);
                r.meta = meta.toString();
                r.image = cur.getString(7) != null ? cur.getString(7) : cur.getString(6);   // 16:9 d'abord
                r.engaged = cur.getLong(10);
                // Un paquet qu'on ne peut pas lancer n'a pas de nom montrable (« Shell ») : pas de nom plutôt qu'un nom technique.
                r.app = HomeApps.launchIntent(c.getPackageManager(), r.pkg) != null ? label(c, r.pkg) : null;
                out.add(r);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            AppLog.w("reprendre : " + e.getMessage());
        }
        // Stremio par son API quand la clé est là : ses lignes Watch Next (s'il en publie) font alors doublon.
        List<Resume> st = stremio();
        if (!st.isEmpty()) {
            Set<String> titles = new HashSet<>();
            for (Resume r : st) titles.add(r.title.toLowerCase(java.util.Locale.ROOT));
            out.removeIf(r -> STREMIO.equals(r.pkg) || titles.contains(r.title.toLowerCase(java.util.Locale.ROOT)));
            for (Resume r : st) r.app = HomeApps.launchIntent(c.getPackageManager(), STREMIO) != null ? "Stremio" : null;
            out.addAll(st);
            out.sort((a, b) -> Long.compare(b.engaged, a.engaged));
            while (out.size() > max) out.remove(out.size() - 1);
        }
        return out;
    }

    /** « 12 min restantes · fin à 23:12 » au bout de `meta`, si la position et la durée sont connues. */
    private static void left(StringBuilder meta, long position, long duration) {
        if (duration <= 0 || position <= 0) return;
        long left = (duration - position) / 60_000L;
        if (left <= 0) return;
        if (meta.length() > 0) meta.append(" · ");
        meta.append(left).append(" min restantes · fin à ")
                .append(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)
                        .format(new java.util.Date(System.currentTimeMillis() + duration - position)));
    }

    // MARK: - Stremio

    static final String STREMIO = "com.stremio.one";
    private static List<Resume> stremioCache = Collections.emptyList();
    private static long stremioAt;

    /**
     * Le « Continuer » de Stremio, lu dans sa bibliothèque (api.strem.io, clé de session de config.properties) : les
     * titres entamés, et pour une série dont le dernier épisode est fini (moins de 3 semaines), l'épisode suivant déjà
     * sorti (Cinemeta). La tuile ouvre Stremio sur l'épisode. Réseau : jamais sur le fil principal. Gardé une minute.
     */
    static synchronized List<Resume> stremio() {
        String key = Config.STREMIO_AUTH.trim();
        long now = System.currentTimeMillis();
        if (key.isEmpty() || now - stremioAt < 60_000) return stremioCache;
        stremioAt = now;
        List<Resume> out = new ArrayList<>();
        try {
            org.json.JSONArray items = post("https://api.strem.io/api/datastoreGet", new org.json.JSONObject()
                    .put("authKey", key).put("collection", "libraryItem").put("all", true)).getJSONArray("result");
            List<org.json.JSONObject> cand = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject it = items.getJSONObject(i), st = it.optJSONObject("state");
                String type = it.optString("type");
                if (st == null || !(type.equals("movie") || type.equals("series"))) continue;
                if (it.optBoolean("removed") && !it.optBoolean("temp")) continue;
                long last = time(st.optString("lastWatched"));
                boolean going = st.optLong("timeOffset") > 0;
                boolean next = type.equals("series") && st.optString("video_id").contains(":") && now - last < 21 * 86_400_000L;
                if (going || next) cand.add(it.put("_last", last));
            }
            cand.sort((a, b) -> Long.compare(b.optLong("_last"), a.optLong("_last")));
            for (org.json.JSONObject it : cand) {
                if (out.size() >= 6) break;
                try {
                    Resume r = stremioItem(it);
                    if (r != null) out.add(r);
                } catch (IOException | org.json.JSONException e) {
                    AppLog.w("stremio " + it.optString("_id") + " : " + e.getMessage());   // un titre raté n'efface pas les autres
                }
            }
        } catch (IOException | org.json.JSONException e) {
            AppLog.w("stremio : " + e.getMessage());   // clé périmée ou pas de réseau : on garde la liste d'avant
            return stremioCache;
        }
        return stremioCache = out;
    }

    private static Resume stremioItem(org.json.JSONObject it) throws IOException, org.json.JSONException {
        org.json.JSONObject st = it.getJSONObject("state");
        String id = it.getString("_id"), vid = st.optString("video_id");
        long position = st.optLong("timeOffset"), duration = st.optLong("duration");
        Resume r = new Resume();
        r.pkg = STREMIO;
        r.title = it.optString("name");
        r.engaged = it.optLong("_last");
        StringBuilder meta = new StringBuilder();
        if (it.optString("type").equals("movie")) {
            r.intentUri = "stremio:///detail/movie/" + id + "/" + id;
            r.image = "https://images.metahub.space/background/small/" + id + "/img";
            r.wide = "https://images.metahub.space/background/medium/" + id + "/img";
        } else if (!id.startsWith("tt")) {   // série hors Cinemeta (Kitsu…) : pas de liste d'épisodes, seulement l'entamé
            if (position <= 0) return null;
            r.intentUri = "stremio:///detail/series/" + id + "/" + vid;
            r.image = it.optString("poster", null);
        } else {
            org.json.JSONArray videos = get("https://v3-cinemeta.strem.io/meta/series/" + id + ".json")
                    .getJSONObject("meta").optJSONArray("videos");
            if (videos == null) return null;
            List<org.json.JSONObject> eps = new ArrayList<>();
            for (int i = 0; i < videos.length(); i++) if (videos.getJSONObject(i).optInt("season") > 0) eps.add(videos.getJSONObject(i));
            eps.sort((a, b) -> a.optInt("season") != b.optInt("season") ? a.optInt("season") - b.optInt("season")
                    : a.optInt("episode") - b.optInt("episode"));
            int at = -1;
            for (int i = 0; i < eps.size(); i++) if (eps.get(i).optString("id").equals(vid)) at = i;
            if (at < 0) return null;
            org.json.JSONObject ep = eps.get(at);
            if (position <= 0) {   // épisode fini : le suivant, s'il est sorti
                if (at + 1 >= eps.size() || time(eps.get(at + 1).optString("released")) > System.currentTimeMillis()) return null;
                ep = eps.get(at + 1);
                duration = 0;
            }
            r.intentUri = "stremio:///detail/series/" + id + "/" + ep.optString("id");
            r.image = ep.optString("thumbnail", null);
            meta.append("Saison ").append(ep.optInt("season")).append(" · Épisode ").append(ep.optInt("episode"));
            if (position <= 0) meta.append(" · à suivre");
        }
        if (duration > 0 && position > 0) r.progress = Math.min(1f, (float) position / duration);
        left(meta, position, duration);
        r.meta = meta.toString();
        return r;
    }

    private static long time(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static org.json.JSONObject post(String url, org.json.JSONObject body) throws IOException, org.json.JSONException {
        return json(url, body.toString().getBytes("UTF-8"));
    }

    private static org.json.JSONObject get(String url) throws IOException, org.json.JSONException {
        return json(url, null);
    }

    private static org.json.JSONObject json(String url, byte[] body) throws IOException, org.json.JSONException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(5_000);
            h.setReadTimeout(10_000);
            if (body != null) {
                h.setRequestMethod("POST");
                h.setRequestProperty("Content-Type", "application/json");
                h.setDoOutput(true);
                h.getOutputStream().write(body);
            }
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16_384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                org.json.JSONObject o = new org.json.JSONObject(out.toString("UTF-8"));
                if (o.has("error")) throw new IOException(o.optJSONObject("error") != null ? o.getJSONObject("error").optString("message") : "erreur");
                return o;
            }
        } finally {
            h.disconnect();
        }
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

    private static final long SCENE_TTL = 3 * 3_600_000L;

    /** Retient la scène lancée d'ici (cinema, ambiance) ; « eteindre » efface tout. */
    static void lastScene(Context c, String action) {
        c.getSharedPreferences("maison", Context.MODE_PRIVATE).edit()
                .putString("scene", "eteindre".equals(action) ? null : action)
                .putLong("sceneAt", System.currentTimeMillis()).apply();
    }

    /** L'heure de lancement si `action` est la dernière scène lancée il y a moins de 3 h, sinon 0. */
    static long sceneAt(Context c, String action) {
        android.content.SharedPreferences p = c.getSharedPreferences("maison", Context.MODE_PRIVATE);
        long at = p.getLong("sceneAt", 0);
        return action.equals(p.getString("scene", null)) && System.currentTimeMillis() - at < SCENE_TTL ? at : 0;
    }

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

    static void forget(String uri) {
        IMAGES.remove(uri);
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
