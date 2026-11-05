package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * L'accueil maison : le design system de design/ (tokens.css, components.css, maquette.html) dessiné en Canvas.
 * TV : en-tête, héros (Reprendre, sinon une suggestion) et deux affiches, Apps (les récentes d'abord), Salon (hub).
 * Projo : l'heure, une rangée d'images 16:9 avec légende, puis les raccourcis (réglages du projecteur, entrées).
 * Toutes les mesures sont en px de la maquette 1920×1080 ; le canevas est mis à l'échelle de l'écran.
 * Pas de flou, pas de saveLayer, pas de clipPath ; on ne redessine que pendant une animation ou un changement.
 */
final class MaisonView extends View implements HomeData.Listener, Recs.Listener {

    // MARK: - Jetons (tokens.css)

    private static final int BG = 0xFF02070A, SURFACE = 0xFF08141B, SURFACE_2 = 0xFF0D1C25;
    private static final int GLOW = 0xFF4FBBEE, WARM = 0xFFFC734D, WARM_TINT = 0x14FC734D;
    private static final float SAFE_X = 96, SAFE_Y = 54, GAP = 32, W = 1920, H = 1080;
    private static final float FOCUS_GAP = 6, GROW = 12;
    private static final long FOCUS_MS = 150;

    private final boolean projo = Maison.projo();
    private final int ink = projo ? 0xFFDFE7EC : 0xFFEEF3F6;
    private final int ink2 = projo ? 0xCCDFE7EC : 0xD1EEF3F6;
    private final int muted = projo ? 0xFF9AABB8 : 0xFF93A5B2;
    private final float focusInk = projo ? 3 : 4, focusRing = projo ? 0 : 6;

    // MARK: - Cellules

    private static final int C_RESUME = 0, C_PICK = 1, C_APP = 2, C_ALL = 3, C_SALON = 4, C_SOUND = 5, C_INPUTS = 6,
            C_SETTINGS = 7;

    private static final class Cell {
        int kind;
        final RectF r = new RectF();
        float radius;
        Maison.Resume resume;
        Recs.Pick pick;
        HomeApps.Item item;
        String action, title, sub, icon;
        boolean hero, poster, frame, warm;
        float f;                // focus affiché, 0..1
        long noteUntil;         // retour d'une action (« Lancé ») jusqu'à
        String note;
        boolean noteOk;
    }

    /** Hors de la rangée Apps (ils restent dans « Toutes les apps ») : réglages et boutiques, la place va aux apps qu'on regarde. */
    private static final Set<String> OFF_ROW = new HashSet<>(java.util.Arrays.asList(
            "com.android.tv.settings", "com.android.vending", "com.google.android.play.games"));
    /** Et sur le projo, la TV en direct (pas d'antenne). */
    private static final Set<String> OFF_PROJO = new HashSet<>(java.util.Arrays.asList(HomeApps.LIVE_TV_APPS));
    static { OFF_PROJO.add("com.android.tv"); }   // la TV en direct d'Android (AOSP), absente de LIVE_TV_APPS

    private final Activity activity;
    private final HomeData data;
    private final Recs recs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<List<Cell>> rows = new ArrayList<>();
    private final List<String> rowTitles = new ArrayList<>();
    private int row, col;
    private boolean running;
    private boolean touched;   // faux tant que personne n'a bougé : le focus suit alors le premier élément (Reprendre)

    // Écran « Toutes les apps » / « Entrées »
    private boolean sheet;
    private String sheetTitle;
    private final List<Cell> sheetCells = new ArrayList<>();
    private int sheetIndex;

    // Données
    private List<Maison.Resume> resumes = new ArrayList<>();
    private List<HomeApps.Item> apps = new ArrayList<>();
    private List<HomeApps.Item> allApps = new ArrayList<>();
    private List<HomeApps.Item> inputs = new ArrayList<>();
    private int loadGeneration;

    // Dessin
    private float k = 1;
    private Bitmap glows;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final TextPaint time, meta, section, tile, heroTitle, screenTitle, weather, frameTitle;
    private final Matrix matrix = new Matrix();
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private long lastFrame;

    MaisonView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        recs.setLight(false);
        setFocusable(true);
        Typeface geist400 = font(a, "fonts/Geist.ttf", 400), geist500 = font(a, "fonts/Geist.ttf", 500),
                mono = font(a, "fonts/GeistMono.ttf", 500), serif = font(a, "fonts/InstrumentSerif.ttf", 400);
        time = text(geist400, 48, ink);
        time.setLetterSpacing(-0.03f);
        time.setFontFeatureSettings("tnum");
        meta = text(geist500, 24, ink2);
        section = text(mono, 22, muted);
        section.setLetterSpacing(0.08f);
        tile = text(geist500, 28, ink);
        tile.setLetterSpacing(-0.02f);
        frameTitle = text(geist500, 32, ink);
        frameTitle.setLetterSpacing(-0.02f);
        heroTitle = text(serif, 96, ink);
        heroTitle.setLetterSpacing(-0.02f);
        screenTitle = text(serif, 56, ink);
        screenTitle.setLetterSpacing(-0.02f);
        weather = text(geist400, 40, ink);
        weather.setLetterSpacing(-0.03f);
        stroke.setStyle(Paint.Style.STROKE);
        rebuild();
    }

    private static Typeface font(Context c, String path, int weight) {
        try {
            return new Typeface.Builder(c.getAssets(), path).setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException e) {
            return Typeface.create(Typeface.SANS_SERIF, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private static TextPaint text(Typeface face, float size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    // MARK: - Cycle de vie (HomeActivity)

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            invalidate();   // l'heure
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50);
        }
    };

    void resume() {
        if (running) return;
        running = true;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        Sound.refresh(activity, this::rebuild);
        load();
        handler.post(tick);
    }

    void pause() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
    }

    void destroy() {
        pause();
    }

    /** Accueil pressé sur l'accueil : retour au début, écran des apps fermé. */
    void reset(boolean animated) {
        touched = false;
        sheet = false;
        row = 0;
        col = 0;
        snapFocus();
        invalidate();
    }

    @Override public void onData() { invalidate(); }

    @Override public void onRecs() { rebuild(); }

    /** Lit les apps, Reprendre et les entrées en fond (les bannières se décodent), puis reconstruit les rangées. */
    private void load() {
        int gen = ++loadGeneration;
        Context c = activity.getApplicationContext();
        new Thread(() -> {
            List<Maison.Resume> res = Maison.resume(c, 6);
            Set<String> launchable = new HashSet<>();
            List<HomeApps.Item> all = new ArrayList<>();
            for (String[] app : HomeApps.launchable(c)) {
                if (app[0].equals(c.getPackageName())) continue;
                launchable.add(app[0]);
                HomeApps.Item it = HomeApps.app(app[0], app[1]);
                it.tile = HomeApps.appTile(c, null, app[0], 0);
                all.add(it);
            }
            java.util.Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));
            // Les récentes d'abord, puis l'étagère dans son ordre, puis le reste : 6 places.
            List<String> order = new ArrayList<>(Maison.recent(c, launchable, 6));
            for (String pkg : HomeApps.shelfPackages(c)) if (!order.contains(pkg) && launchable.contains(pkg)) order.add(pkg);
            for (HomeApps.Item it : all) if (!order.contains(it.pkg)) order.add(it.pkg);
            List<HomeApps.Item> row = new ArrayList<>();
            for (String pkg : order) {
                if (row.size() >= 6) break;
                if (OFF_ROW.contains(pkg) || (projo && OFF_PROJO.contains(pkg))) continue;
                for (HomeApps.Item it : all) if (it.pkg.equals(pkg)) row.add(it);
            }
            List<HomeApps.Item> ins = HomeApps.allInputs(c);
            handler.post(() -> {
                if (gen != loadGeneration) return;
                resumes = res;
                apps = row;
                allApps = all;
                inputs = ins;
                if (!touched) this.row = this.col = 0;
                rebuild();
                if (!touched) {
                    this.row = this.col = 0;
                    snapFocus();
                }
            });
        }, "maison-load").start();
    }

    // MARK: - Rangées

    private void rebuild() {
        Cell was = focused();
        java.util.Map<String, Cell> old = new java.util.HashMap<>();
        for (List<Cell> r : rows) for (Cell c : r) old.put(key(c), c);
        String wasKey = key(was);
        rows.clear();
        rowTitles.clear();
        List<Recs.Pick> picks = recs.now();
        if (projo) buildProjo(picks); else buildTv(picks);
        for (List<Cell> r : rows) for (Cell c : r) {   // un retour en cours (« Lancé ») survit à la reconstruction
            Cell o = old.get(key(c));
            if (o != null) { c.note = o.note; c.noteOk = o.noteOk; c.noteUntil = o.noteUntil; }
        }
        // Garde le focus sur le même élément si possible, sinon sur la même rangée.
        row = Math.min(row, Math.max(0, rows.size() - 1));
        for (int r = 0; r < rows.size() && wasKey != null; r++)
            for (int i = 0; i < rows.get(r).size(); i++)
                if (wasKey.equals(key(rows.get(r).get(i)))) { row = r; col = i; }
        if (!rows.isEmpty()) col = Math.min(col, rows.get(row).size() - 1);
        snapFocus();
        invalidate();
    }

    private static String key(Cell c) {
        if (c == null) return null;
        if (c.item != null) return "app:" + c.item.pkg;
        if (c.resume != null) return "res:" + c.resume.pkg + c.resume.title;
        if (c.pick != null) return "pick:" + c.pick.id;
        return "k:" + c.kind + c.action;
    }

    private void buildTv(List<Recs.Pick> picks) {
        float y = SAFE_Y + 56 + 32;
        // Héros : Reprendre d'abord ; sinon la première suggestion. Les affiches : les suggestions, puis la suite de Reprendre.
        List<Cell> hero = new ArrayList<>();
        int pickAt = 0, resumeAt = 0;
        Cell h = null;
        if (!resumes.isEmpty()) h = resumeCell(resumes.get(resumeAt++));
        else if (!picks.isEmpty()) h = pickCell(picks.get(pickAt++));
        if (h != null) {
            h.hero = true;
            h.radius = 24;
            h.r.set(SAFE_X, y, SAFE_X + 1104, y + 420);
            hero.add(h);
            float x = SAFE_X + 1104 + GAP;
            while (hero.size() < 3) {
                Cell p = pickAt < picks.size() ? pickCell(picks.get(pickAt++))
                        : resumeAt < resumes.size() ? resumeCell(resumes.get(resumeAt++)) : null;
                if (p == null) break;
                p.poster = true;
                p.radius = 18;
                p.r.set(x, y, x + 280, y + 420);
                hero.add(p);
                x += 280 + GAP;
            }
            addRow(null, hero);
            y += 420 + 40;
        } else {
            // Rien à reprendre ni à suggérer (le cas courant) : Apps et Salon au milieu de la zone utile, pas collés en haut.
            boolean salonRow = Maison.hasHub() || Sound.out() != null || !inputs.isEmpty();
            float block = 26 + 30 + 123 + (salonRow ? 40 + 26 + 30 + 110 : 0);
            y += Math.max(40, (H - SAFE_Y - y - block) / 2);
        }
        // Apps : 6 + « Toutes les apps »
        List<Cell> appRow = new ArrayList<>();
        float x = SAFE_X;
        y += 26 + 30;   // titre de rangée, posé au-dessus du focus
        for (HomeApps.Item it : apps) {
            Cell c = new Cell();
            c.kind = C_APP;
            c.item = it;
            c.radius = 18;
            c.r.set(x, y, x + 219, y + 123);
            appRow.add(c);
            x += 219 + GAP;
        }
        Cell all = new Cell();
        all.kind = C_ALL;
        all.title = "Toutes les apps";
        all.radius = 18;
        all.r.set(x, y, x + 219, y + 123);
        appRow.add(all);
        addRow("Apps", appRow);
        y += 123 + 40;
        // Salon
        List<Cell> salon = new ArrayList<>();
        y += 26 + 30;   // titre de rangée, posé au-dessus du focus
        x = SAFE_X;
        if (Maison.hasHub()) {
            x = salonCell(salon, x, y, "cinema", "Cinéma", "Lumières ambrées", "film");
            x = salonCell(salon, x, y, "ambiance", "Ambiance", "Blanc chaud", "bulb");
            x = salonCell(salon, x, y, "eteindre", "Tout éteindre", "Lumières du salon", "power");
        }
        if (Sound.out() != null) {
            Cell s = tileCell(C_SOUND, x, y, "Son", Sound.OPTICAL.equals(Sound.out()) ? "Barre de son" : "Haut-parleurs TV", "speaker");
            s.warm = Sound.OPTICAL.equals(Sound.out());
            salon.add(s);
            x += 320 + GAP;
        }
        if (!inputs.isEmpty()) {
            salon.add(tileCell(C_INPUTS, x, y, "Entrées", inputsSummary(), "input"));
        }
        if (!salon.isEmpty()) addRow("Salon", salon);
    }

    private float salonCell(List<Cell> row, float x, float y, String action, String title, String sub, String icon) {
        Cell c = tileCell(C_SALON, x, y, title, sub, icon);
        c.action = action;
        // La dernière scène lancée d'ici garde le point cantaloup, avec son heure : on ne connaît pas l'état réel des lampes.
        long at = Maison.sceneAt(activity, action);
        if (at > 0) {
            c.warm = true;
            c.sub = "Lancée à " + Ui.clock(at);
        }
        row.add(c);
        return x + 320 + GAP;
    }

    private Cell tileCell(int kind, float x, float y, String title, String sub, String icon) {
        Cell c = new Cell();
        c.kind = kind;
        c.title = title;
        c.sub = sub;
        c.icon = icon;
        c.radius = 18;
        c.r.set(x, y, x + 320, y + 110);
        return c;
    }

    private String inputsSummary() {
        StringBuilder s = new StringBuilder();
        for (HomeApps.Item it : inputs) {
            if (it.kind != HomeApps.INPUT || !it.connected) continue;
            if (s.length() > 0) s.append(" · ");
            s.append(it.label);
            if (s.length() > 22) break;
        }
        return s.length() > 0 ? s.toString() : "TV et HDMI";
    }

    private void buildProjo(List<Recs.Pick> picks) {
        // Une rangée de 5 au plus, centrée verticalement avec les raccourcis.
        List<Cell> frames = new ArrayList<>();
        for (Maison.Resume r : resumes) if (frames.size() < 2) frames.add(resumeCell(r));
        for (Recs.Pick p : picks) if (frames.size() < 3) frames.add(pickCell(p));
        for (HomeApps.Item it : apps) {
            if (frames.size() >= 4) break;
            Cell c = new Cell();
            c.kind = C_APP;
            c.item = it;
            frames.add(c);
        }
        Cell all = new Cell();
        all.kind = C_ALL;
        all.title = "Toutes les apps";
        all.sub = allApps.size() + " installées";
        frames.add(all);
        float top = 1080 / 2f - 200;
        float x = SAFE_X;
        for (Cell c : frames) {
            c.frame = true;
            c.radius = 12;
            c.r.set(x, top, x + 320, top + 180);
            x += 320 + GAP;
        }
        addRow("Ce soir", frames);
        List<Cell> quick = new ArrayList<>();
        float qy = top + 180 + 22 + 40 + 6 + 30 + 56;
        x = SAFE_X;
        // Les réglages du projecteur (mise au point, trapèze, luminosité) restent ceux de XGIMI : on y mène, rien de plus.
        quick.add(quickCell(C_SETTINGS, x, qy, "Réglages du projecteur", "settings"));
        x = quick.get(0).r.right + 44;
        if (!inputs.isEmpty()) quick.add(quickCell(C_INPUTS, x, qy, "Entrées", "input"));
        addRow(null, quick);
    }

    private Cell quickCell(int kind, float x, float y, String title, String icon) {
        Cell c = new Cell();
        c.kind = kind;
        c.title = title;
        c.icon = icon;
        c.radius = 12;
        c.r.set(x - 14, y - 10, x + 28 + 12 + meta.measureText(title) + 14, y + 38);
        return c;
    }

    private void addRow(String title, List<Cell> cells) {
        if (cells.isEmpty()) return;
        rows.add(cells);
        rowTitles.add(title);
    }

    private Cell resumeCell(Maison.Resume r) {
        Cell c = new Cell();
        c.kind = C_RESUME;
        c.resume = r;
        c.title = r.title;
        c.sub = r.meta;
        c.warm = true;
        return c;
    }

    private Cell pickCell(Recs.Pick p) {
        Cell c = new Cell();
        c.kind = C_PICK;
        c.pick = p;
        c.title = p.title;
        c.sub = p.meta;
        return c;
    }

    // MARK: - Touches

    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return isOurs(e.getKeyCode());
        boolean held = e.getRepeatCount() > 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0, held); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0, held); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1, held); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1, held); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!held) activate();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (sheet) {
                    sheet = false;
                    snapFocus();
                    invalidate();
                }
                return true;   // l'accueil ne se ferme pas
            default:
                return false;
        }
    }

    private static boolean isOurs(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK;
    }

    private void move(int dx, int dy, boolean held) {
        touched = true;
        if (sheet) {
            int cols = 7, n = sheetCells.size();
            if (n == 0) return;
            int next = sheetIndex + dx + dy * cols;
            if (dx != 0 && (next < 0 || next >= n || next / cols != sheetIndex / cols)) return;
            if (next < 0 || next >= n) {
                if (dy > 0 && sheetIndex / cols < (n - 1) / cols) next = n - 1; else return;
            }
            sheetIndex = next;
        } else {
            if (rows.isEmpty()) return;
            if (dx != 0) {
                int next = col + dx;
                if (next < 0 || next >= rows.get(row).size()) return;
                col = next;
            } else {
                int next = row + dy;
                if (next < 0 || next >= rows.size()) return;
                RectF from = rows.get(row).get(col).r;
                float cx = from.left + Math.min(from.width(), 219) / 2;   // depuis le héros : la première app, pas celle du milieu
                row = next;
                col = nearest(rows.get(row), cx);
            }
        }
        if (held) snapFocus();   // touche maintenue : le focus saute, sans animation
        invalidate();
    }

    private static int nearest(List<Cell> cells, float x) {
        int best = 0;
        float d = Float.MAX_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            float di = Math.abs(cells.get(i).r.centerX() - x);
            if (di < d) { d = di; best = i; }
        }
        return best;
    }

    private Cell focused() {
        if (sheet) return sheetIndex < sheetCells.size() ? sheetCells.get(sheetIndex) : null;
        if (row >= rows.size() || rows.get(row).isEmpty()) return null;
        return rows.get(row).get(Math.min(col, rows.get(row).size() - 1));
    }

    private void activate() {
        Cell c = focused();
        if (c == null) return;
        int x = (int) (c.r.left * k), y = (int) (c.r.top * k), w = (int) (c.r.width() * k), h = (int) (c.r.height() * k);
        switch (c.kind) {
            case C_APP:
                if (!HomeApps.open(activity, c.item, this, x, y, w, h)) note(c, "Impossible d'ouvrir", false);
                break;
            case C_PICK:
                if (c.pick.pkg == null || !HomeApps.open(activity, HomeApps.app(c.pick.pkg, Recs.serviceName(c.pick), c.pick.link), this, x, y, w, h))
                    note(c, "Impossible d'ouvrir", false);
                break;
            case C_RESUME:
                try {
                    Intent i = Intent.parseUri(c.resume.intentUri, Intent.URI_INTENT_SCHEME);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                } catch (Exception e) {
                    AppLog.w("reprendre : " + e.getMessage());
                    HomeApps.open(activity, HomeApps.app(c.resume.pkg, c.resume.app), this, x, y, w, h);
                }
                break;
            case C_ALL:
                openSheet("Toutes les apps", allApps);
                break;
            case C_INPUTS:
                openSheet("Entrées", inputs);
                break;
            case C_SETTINGS: {
                HomeApps.Item it = new HomeApps.Item();
                it.kind = HomeApps.ANDROID_SETTINGS;
                it.label = c.title;
                HomeApps.open(activity, it, this, x, y, w, h);
                break;
            }
            case C_SOUND:
                note(c, "…", true);
                Sound.toggle(activity, ok -> {
                    if (!ok) note(c, "Pas de réponse", false);
                    else c.noteUntil = 0;
                    rebuild();
                });
                break;
            case C_SALON:
                note(c, "…", true);
                Maison.salon(c.action, ok -> {
                    if (ok) Maison.lastScene(activity, c.action);
                    note(c, ok ? "Lancé" : "Hub injoignable", ok);
                    rebuild();
                });
                break;
            default:
                break;
        }
    }

    private void note(Cell c, String text, boolean ok) {
        c.note = text;
        c.noteOk = ok;
        c.noteUntil = SystemClock.uptimeMillis() + 4_000;
        invalidate();
        handler.postDelayed(this::invalidate, 4_050);
    }

    private void openSheet(String title, List<HomeApps.Item> items) {
        sheetCells.clear();
        float x0 = SAFE_X, y0 = SAFE_Y + 56 + 48;
        for (int i = 0; i < items.size(); i++) {
            Cell c = new Cell();
            c.kind = C_APP;
            c.item = items.get(i);
            c.radius = 18;
            float x = x0 + (i % 7) * (219 + GAP), y = y0 + (i / 7) * SHEET_STEP;
            c.r.set(x, y, x + 219, y + 123);
            sheetCells.add(c);
        }
        sheetTitle = title;
        sheetIndex = 0;
        sheet = true;
        snapFocus();
        invalidate();
    }

    // MARK: - Focus animé

    private void snapFocus() {
        Cell f = focused();
        for (List<Cell> r : rows) for (Cell c : r) c.f = c == f ? 1 : 0;
        for (Cell c : sheetCells) c.f = c == f ? 1 : 0;
    }

    /** Avance les focus vers leur cible ; vrai s'il reste du mouvement. */
    private boolean stepFocus() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        lastFrame = now;
        float step = dt / FOCUS_MS;
        Cell f = focused();
        boolean moving = false;
        for (Cell c : sheet ? sheetCells : allCells()) {
            float target = c == f ? 1 : 0;
            if (c.f == target) continue;
            c.f = target > c.f ? Math.min(target, c.f + step) : Math.max(target, c.f - step);
            moving = true;
        }
        if (!moving) lastFrame = 0;
        return moving;
    }

    private List<Cell> allCells() {
        List<Cell> all = new ArrayList<>();
        for (List<Cell> r : rows) all.addAll(r);
        return all;
    }

    /** cubic-bezier(.16,1,.3,1), approché : une sortie rapide qui se pose. */
    private static float ease(float t) {
        float u = 1 - t;
        return 1 - u * u * u * u;
    }

    // MARK: - Dessin

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / W;
        glows = null;
    }

    @Override protected void onDraw(Canvas c) {
        boolean moving = stepFocus();
        c.drawColor(BG);
        if (!projo) drawGlows(c);
        c.save();
        c.scale(k, k);
        if (sheet) drawSheet(c);
        else if (projo) drawProjo(c);
        else drawTv(c);
        c.restore();
        if (moving) postInvalidateOnAnimation();
    }

    /** Les deux lueurs du fond, calculées une fois dans un bitmap tramé à mi-résolution. */
    private void drawGlows(Canvas c) {
        if (glows == null && getWidth() > 0) {
            int w = getWidth() / 2, h = getHeight() / 2;
            glows = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas g = new Canvas(glows);
            float s = w / W;
            Paint p = new Paint(Paint.DITHER_FLAG);
            p.setShader(new RadialGradient(0.82f * W * s, -0.10f * H * s, 1100 * s, 0x1F0297DE, 0x000297DE, Shader.TileMode.CLAMP));
            g.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(0, 1.12f * H * s, 900 * s, 0x12FC734D, 0x00FC734D, Shader.TileMode.CLAMP));
            g.drawRect(0, 0, w, h, p);
        }
        if (glows != null) {
            tmp.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(glows, null, tmp, image);
        }
    }

    private void drawTv(Canvas c) {
        drawHeader(c);
        for (int r = 0; r < rows.size(); r++) {
            List<Cell> cells = rows.get(r);
            String title = rowTitles.get(r);
            if (title != null) {
                section.setColor(muted);
                c.drawText(title.toUpperCase(java.util.Locale.ROOT), SAFE_X, cells.get(0).r.top - 36, section);   // au-dessus du focus (28 px)
            }
            // Le focus d'abord en dessous, l'élément focalisé dessiné en dernier (il grandit sur ses voisins).
            Cell top = null;
            for (Cell cell : cells) {
                if (cell.f > 0 && (top == null || cell.f > top.f)) top = cell;
                else drawCell(c, cell);
            }
            if (top != null) drawCell(c, top);
        }
        if (rows.isEmpty()) {
            meta.setColor(muted);
            c.drawText("Chargement…", SAFE_X, 540, meta);
        }
    }

    private void drawHeader(Canvas c) {
        float mid = SAFE_Y + 28;
        long now = System.currentTimeMillis();
        String t = Ui.clock(now);
        float base = mid + Ui.cap(time) / 2;
        c.drawText(t, SAFE_X, base, time);
        meta.setColor(muted);
        c.drawText(capitalize(Ui.date(now)), SAFE_X + time.measureText(t) + 24, base, meta);
        HomeData.Weather wx = data.weather;
        if (wx != null && !Double.isNaN(wx.temp)) {
            String deg = HomeData.degrees(wx.temp), cond = HomeData.condition(wx.code, wx.isDay);
            float right = W - SAFE_X;
            float wc = meta.measureText(cond);
            meta.setColor(ink2);
            c.drawText(cond, right - wc, base, meta);
            float wd = weather.measureText(deg);
            c.drawText(deg, right - wc - 14 - wd, base, weather);
            String sym = weatherSymbol(wx.code, wx.isDay);
            Symbols.draw(getContext(), c, sym, right - wc - 14 - wd - 14 - 17, mid, 28, muted);
        }
    }

    private static String weatherSymbol(int code, boolean day) {
        if (code == 0) return day ? "sunny" : "clear_night";
        if (code == 1 || code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        if (code >= 51) return "rainy";
        return "cloud";
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void drawCell(Canvas c, Cell cell) {
        float f = ease(cell.f);
        RectF r = tmp;
        boolean grows = !cell.frame && cell.kind != C_SETTINGS && !(projo && cell.kind == C_INPUTS);
        float g = grows ? GROW * f : 0;
        if (cell.frame) {
            // Projo : l'image grandit, la légende reste en place.
            r.set(cell.r.left - GROW * f, cell.r.top - GROW * f * 180 / 320, cell.r.right + GROW * f, cell.r.bottom + GROW * f * 180 / 320);
        } else {
            r.set(cell.r.left - g, cell.r.top - g * cell.r.height() / cell.r.width(), cell.r.right + g, cell.r.bottom + g * cell.r.height() / cell.r.width());
        }
        if (f > 0) drawFocus(c, r, cell.radius, f);
        switch (cell.kind) {
            case C_RESUME:
            case C_PICK:
                if (cell.frame) drawFrame(c, cell, r, f); else if (cell.hero) drawHero(c, cell, r); else drawPoster(c, cell, r);
                break;
            case C_APP:
                if (cell.frame) drawFrame(c, cell, r, f); else drawApp(c, cell.item, r, cell.radius, f);
                break;
            case C_ALL:
                if (cell.frame) drawFrame(c, cell, r, f); else drawAll(c, r, cell.radius);
                break;
            case C_SETTINGS:
            case C_INPUTS:
                if (projo) drawQuick(c, cell, f); else drawTile(c, cell, r);
                break;
            default:
                drawTile(c, cell, r);
        }
    }

    /** Le focus, partout le même : un trait encre à 6 px du bord, puis l'anneau bleu à 40 % (sans bleu sur le projo). */
    private void drawFocus(Canvas c, RectF r, float radius, float f) {
        float o = FOCUS_GAP + focusInk / 2;
        stroke.setStrokeWidth(focusInk);
        stroke.setColor(Ui.alpha(ink, f));
        tmp2.set(r.left - o, r.top - o, r.right + o, r.bottom + o);
        c.drawRoundRect(tmp2, radius + o, radius + o, stroke);
        if (focusRing > 0) {
            float o2 = FOCUS_GAP + focusInk + focusRing / 2;
            stroke.setStrokeWidth(focusRing);
            stroke.setColor(GLOW);
            stroke.setAlpha((int) (0x66 * f));   // #4FBBEE à 40 %
            tmp2.set(r.left - o2, r.top - o2, r.right + o2, r.bottom + o2);
            c.drawRoundRect(tmp2, radius + o2, radius + o2, stroke);
        }
    }

    private final RectF tmp2 = new RectF();
    private static final float SHEET_STEP = 123 + 96;   // une rangée d'apps + son nom sous le focus

    private Bitmap imageOf(Cell cell, boolean wide) {
        if (cell.resume != null) return Maison.image(getContext(), cell.resume.image, wide ? 1104 : 640, this::invalidate);
        if (cell.pick != null) {
            Backdrop b = recs.backdrop(cell.pick);
            return b == null ? null : wide ? b.wide : b.tall;
        }
        return null;
    }

    /** Une image en « cover » dans un rectangle arrondi : BitmapShader + drawRoundRect (pas de clipPath). */
    private void cover(Canvas c, Bitmap b, RectF r, float radius) {
        if (b == null || b.isRecycled()) {
            fill.setColor(SURFACE);
            c.drawRoundRect(r, radius, radius, fill);
            return;
        }
        BitmapShader sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float s = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
        matrix.setScale(s, s);
        matrix.postTranslate(r.left + (r.width() - b.getWidth() * s) / 2, r.top + (r.height() - b.getHeight() * s) / 2);
        sh.setLocalMatrix(matrix);
        image.setShader(sh);
        c.drawRoundRect(r, radius, radius, image);
        image.setShader(null);
    }

    private void drawHero(Canvas c, Cell cell, RectF r) {
        cover(c, imageOf(cell, true), r, cell.radius);
        // Voiles : à gauche pour le texte, en bas pour la rangée suivante.
        scrim.setShader(new LinearGradient(r.left, 0, r.left + r.width() * 0.7f, 0,
                new int[] {0xE002070A, 0x8002070A, 0x0002070A}, new float[] {0, 0.6f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, cell.radius, cell.radius, scrim);
        scrim.setShader(new LinearGradient(0, r.bottom, 0, r.bottom - r.height() * 0.55f, 0xD902070A, 0x0002070A, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, cell.radius, cell.radius, scrim);
        scrim.setShader(null);
        float x = r.left + 48, bottom = r.bottom - 44;
        boolean resume = cell.resume != null;
        // De bas en haut : jauge, méta, titre, section.
        if (resume && cell.resume.progress > 0) {
            fill.setColor(0x2EEEF3F6);
            tmp2.set(x, bottom - 6, x + 420, bottom);
            c.drawRoundRect(tmp2, 3, 3, fill);
            fill.setColor(ink);
            tmp2.set(x, bottom - 6, x + 420 * cell.resume.progress, bottom);
            c.drawRoundRect(tmp2, 3, 3, fill);
            bottom -= 6 + 22;
        }
        if (cell.sub != null && !cell.sub.isEmpty()) {
            meta.setColor(ink2);
            c.drawText(ellipsize(cell.sub, meta, 620), x, bottom, meta);
            bottom -= 24 + 22;
        }
        String title = cell.title == null ? "" : cell.title;
        float size = 96;
        heroTitle.setTextSize(size);
        while (size > 56 && heroTitle.measureText(title) > 620) heroTitle.setTextSize(size -= 4);
        c.drawText(ellipsize(title, heroTitle, 620), x - Ui.leftBearing(heroTitle, title), bottom, heroTitle);
        bottom -= Ui.cap(heroTitle) + 26;
        section.setColor(resume ? WARM : muted);
        String label = resume ? "Reprendre" + (cell.resume.app != null ? " · " + cell.resume.app : "") : "À voir · " + (cell.pick.app == null ? "" : Recs.serviceName(cell.pick));
        c.drawText(label.toUpperCase(java.util.Locale.ROOT), x, bottom, section);
    }

    private void drawPoster(Canvas c, Cell cell, RectF r) {
        cover(c, imageOf(cell, false), r, cell.radius);
        scrim.setShader(new LinearGradient(0, r.bottom, 0, r.bottom - r.height() * 0.5f, 0xC002070A, 0x0002070A, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, cell.radius, cell.radius, scrim);
        scrim.setShader(null);
        // Repère d'origine en haut à gauche.
        String origin = cell.resume != null ? cell.resume.app : Recs.serviceName(cell.pick);
        if (origin != null && !origin.isEmpty()) {
            meta.setTextSize(22);
            float w = meta.measureText(origin) + 24;
            fill.setColor(0xB802070A);
            tmp2.set(r.left + 18, r.top + 18, r.left + 18 + w, r.top + 18 + 36);
            c.drawRoundRect(tmp2, 6, 6, fill);
            meta.setColor(cell.resume != null ? WARM : ink);
            c.drawText(origin, r.left + 30, r.top + 18 + 18 + Ui.cap(meta) / 2, meta);
            meta.setTextSize(24);
        }
        // Le titre : l'image TMDB est une photo sans titre, on l'écrit dessous.
        tile.setTextSize(30);
        tile.setColor(0xFFFFFFFF);
        String t = ellipsize(cell.title == null ? "" : cell.title, tile, r.width() - 48);
        c.drawText(t, r.left + 24, r.bottom - 28, tile);
        tile.setTextSize(28);
        tile.setColor(ink);
    }

    private void drawApp(Canvas c, HomeApps.Item it, RectF r, float radius, float f) {
        if (it.tile != null) {
            // Les bannières blanches écrasent le reste (et éblouissent sur le projo) : atténuées hors focus.
            float rest = projo ? 0.70f : 0.85f, g = rest + (1 - rest) * f;
            int v = Math.round(255 * g);
            if (v < 255) image.setColorFilter(new LightingColorFilter(Color.rgb(v, v, v), 0));
            cover(c, it.tile, r, radius);
            image.setColorFilter(null);
        } else {
            fill.setColor(SURFACE_2);
            c.drawRoundRect(r, radius, radius, fill);
            meta.setColor(ink);
            c.drawText(ellipsize(it.label, meta, r.width() - 24), r.centerX() - Math.min(meta.measureText(it.label), r.width() - 24) / 2,
                    r.centerY() + Ui.cap(meta) / 2, meta);
        }
    }

    private void drawAll(Canvas c, RectF r, float radius) {
        fill.setColor(SURFACE_2);
        c.drawRoundRect(r, radius, radius, fill);
        drawIcon(c, "apps", r.centerX(), r.centerY() - 16, 40, ink2);
        meta.setTextSize(22);
        meta.setColor(ink);
        String s = "Toutes les apps";
        c.drawText(s, r.centerX() - meta.measureText(s) / 2, r.centerY() + 34, meta);
        meta.setTextSize(24);
    }

    private void drawTile(Canvas c, Cell cell, RectF r) {
        boolean noting = cell.noteUntil > SystemClock.uptimeMillis();
        boolean on = cell.warm && !noting;   // cantaloup = allumé, jamais pour dire « c'est fait »
        boolean done = noting && cell.noteOk && !"…".equals(cell.note);
        fill.setColor(SURFACE);
        c.drawRoundRect(r, cell.radius, cell.radius, fill);
        if (on) {
            fill.setColor(WARM_TINT);
            c.drawRoundRect(r, cell.radius, cell.radius, fill);
        }
        float s = r.width() / 320;   // la tuile grandit avec le focus : son contenu aussi
        float x = r.left + 26 * s;
        drawIcon(c, cell.icon, x + 18 * s, r.centerY(), 36 * s, on ? WARM : ink2);
        float tx = x + (36 + 20) * s;
        tile.setTextSize(28 * s);
        tile.setColor(ink);
        meta.setTextSize(24 * s);
        float block = Ui.cap(tile) + 6 * s + 24 * s;
        float top = r.centerY() - block / 2;
        c.drawText(cell.title, tx, top + Ui.cap(tile), tile);
        String sub = noting ? cell.note : cell.sub;
        float sx = tx;
        if (on) {
            fill.setColor(WARM);
            tmp2.set(sx, top + block - 10 * s - 3 * s, sx + 10 * s, top + block - 3 * s);
            c.drawRoundRect(tmp2, 2 * s, 2 * s, fill);
            sx += 20 * s;
        } else if (done) {
            drawIcon(c, "check", sx + 10 * s, top + block - Ui.cap(meta) / 2, 24 * s, ink);
            sx += 28 * s;
        }
        meta.setColor(on ? WARM : done ? ink : muted);
        if (sub != null) c.drawText(ellipsize(sub, meta, r.right - sx - 20 * s), sx, top + block, meta);
        tile.setTextSize(28);
        meta.setTextSize(24);
    }

    // MARK: - Projo

    private void drawProjo(Canvas c) {
        time.setTextSize(40);
        time.setColor(muted);
        c.drawText(Ui.clock(System.currentTimeMillis()), SAFE_X, SAFE_Y + 28 + Ui.cap(time) / 2, time);
        time.setTextSize(48);
        time.setColor(ink);
        for (int r = 0; r < rows.size(); r++) {
            List<Cell> cells = rows.get(r);
            String title = rowTitles.get(r);
            if (title != null) {
                section.setColor(muted);
                c.drawText(title.toUpperCase(java.util.Locale.ROOT), SAFE_X, cells.get(0).r.top - 24 - 4, section);
            }
            for (Cell cell : cells) drawCell(c, cell);
        }
    }

    private void drawFrame(Canvas c, Cell cell, RectF r, float f) {
        if (cell.kind == C_APP) drawApp(c, cell.item, r, cell.radius, f);
        else if (cell.kind == C_ALL) {
            fill.setColor(SURFACE);
            c.drawRoundRect(r, cell.radius, cell.radius, fill);
            drawIcon(c, "apps", r.centerX(), r.centerY(), 48, ink2);
        } else cover(c, imageOf(cell, true), r, cell.radius);
        // Légende sous l'image, une ligne chacune.
        String title = cell.kind == C_APP ? cell.item.label : cell.title;
        String sub = cell.kind == C_APP ? null : cell.kind == C_ALL ? cell.sub
                : cell.resume != null ? "Reprendre" + (cell.resume.progress > 0 && cell.sub != null && cell.sub.contains("min") ? " · " + cell.sub.substring(cell.sub.lastIndexOf("· ") + 2).replace(" restantes", "") : "")
                : Recs.serviceName(cell.pick);
        float base = cell.r.bottom + 22 + Ui.cap(frameTitle);
        frameTitle.setColor(ink);
        c.drawText(ellipsize(title == null ? "" : title, frameTitle, 320), cell.r.left, base, frameTitle);
        meta.setColor(cell.resume != null ? WARM : muted);
        if (sub != null) c.drawText(ellipsize(sub, meta, 320), cell.r.left, base + 6 + 30, meta);
    }

    private void drawQuick(Canvas c, Cell cell, float f) {
        // Raccourcis du projo : un pictogramme et un libellé ; le focus entoure le libellé (drawFocus déjà fait).
        float x = cell.r.left + 14, cy = cell.r.centerY();
        drawIcon(c, cell.icon, x + 14, cy, 28, f > 0.5f ? ink : muted);
        meta.setColor(f > 0.5f ? ink : muted);
        c.drawText(cell.title, x + 28 + 12, cy + Ui.cap(meta) / 2, meta);
    }

    // MARK: - Écran des apps / entrées

    private void drawSheet(Canvas c) {
        float base = SAFE_Y + 56 / 2f + Ui.cap(screenTitle) / 2;
        c.drawText(sheetTitle, SAFE_X - Ui.leftBearing(screenTitle, sheetTitle), base, screenTitle);
        if (sheetCells.isEmpty()) {
            meta.setColor(muted);
            c.drawText("Rien ici pour l'instant.", SAFE_X, base + 120, meta);
            return;
        }
        // Défile par rangées pour garder le focus visible.
        int rowOf = sheetIndex / 7;
        float shift = Math.max(0, rowOf - 3) * SHEET_STEP;
        c.save();
        c.translate(0, -shift);
        Cell top = null;
        for (Cell cell : sheetCells) {
            if (cell.r.bottom - shift < SAFE_Y + 80 || cell.r.top - shift > H) continue;
            if (cell.f > 0 && (top == null || cell.f > top.f)) top = cell;
            else drawSheetCell(c, cell);
        }
        if (top != null) drawSheetCell(c, top);
        c.restore();
    }

    private void drawSheetCell(Canvas c, Cell cell) {
        drawCell(c, cell);
        meta.setColor(cell.f > 0.5f ? ink : muted);
        meta.setTextSize(22);
        c.drawText(ellipsize(cell.item.label, meta, 219), cell.r.left, cell.r.bottom + 28 + 34, meta);   // sous le focus (28 px), avec de l'air
        meta.setTextSize(24);
    }

    // MARK: - Pictogrammes (trait 1,8 sur 24, repris de maquette.html)

    private void drawIcon(Canvas c, String name, float cx, float cy, float size, int color) {
        if (name == null) return;
        float s = size / 24;
        c.save();
        c.translate(cx - 12 * s, cy - 12 * s);
        c.scale(s, s);
        stroke.setColor(color);
        stroke.setStrokeWidth(1.8f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(color);
        path.reset();
        switch (name) {
            case "film":
                tmp2.set(3, 5, 21, 19);
                c.drawRoundRect(tmp2, 2, 2, stroke);
                c.drawLine(7.5f, 5, 7.5f, 19, stroke);
                c.drawLine(16.5f, 5, 16.5f, 19, stroke);
                c.drawLine(3, 9.7f, 7.5f, 9.7f, stroke);
                c.drawLine(3, 14.3f, 7.5f, 14.3f, stroke);
                c.drawLine(16.5f, 9.7f, 21, 9.7f, stroke);
                c.drawLine(16.5f, 14.3f, 21, 14.3f, stroke);
                break;
            case "bulb":
                c.drawLine(9.5f, 18, 14.5f, 18, stroke);
                c.drawLine(10.5f, 21, 13.5f, 21, stroke);
                path.moveTo(9.5f, 16);
                path.cubicTo(9.5f, 15.1f, 9.1f, 14.4f, 8.4f, 13.8f);
                path.arcTo(6, 3, 18, 15, 140, 260, false);
                path.cubicTo(14.9f, 14.4f, 14.5f, 15.1f, 14.5f, 16);
                path.close();
                c.drawPath(path, stroke);
                break;
            case "power":
                c.drawLine(12, 3, 12, 11, stroke);
                tmp2.set(4.4f, 5.2f, 19.6f, 20.4f);
                c.drawArc(tmp2, -45, 270, false, stroke);
                break;
            case "speaker":
                path.moveTo(4, 9.5f);
                path.lineTo(4, 14.5f);
                path.lineTo(7.8f, 14.5f);
                path.lineTo(13, 19);
                path.lineTo(13, 5);
                path.lineTo(7.8f, 9.5f);
                path.close();
                c.drawPath(path, stroke);
                tmp2.set(12, 7.5f, 21, 16.5f);
                c.drawArc(tmp2, -42, 84, false, stroke);
                tmp2.set(11, 4, 27, 20);
                c.drawArc(tmp2, -48, 96, false, stroke);
                break;
            case "input":
                path.moveTo(4, 8);
                path.lineTo(20, 8);
                path.lineTo(20, 13);
                path.lineTo(17, 16);
                path.lineTo(7, 16);
                path.lineTo(4, 13);
                path.close();
                c.drawPath(path, stroke);
                c.drawLine(8, 11.5f, 16, 11.5f, stroke);
                break;
            case "check":
                path.moveTo(5, 12.5f);
                path.lineTo(9.5f, 17);
                path.lineTo(19, 7);
                c.drawPath(path, stroke);
                break;
            case "apps":
                for (float[] p : new float[][] {{4, 4}, {14, 4}, {4, 14}, {14, 14}}) {
                    tmp2.set(p[0], p[1], p[0] + 6, p[1] + 6);
                    c.drawRoundRect(tmp2, 1.5f, 1.5f, fill);
                }
                break;
            case "settings":
                c.drawCircle(12, 12, 3.2f, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(12 + (float) Math.cos(a) * 6.2f, 12 + (float) Math.sin(a) * 6.2f,
                            12 + (float) Math.cos(a) * 8.6f, 12 + (float) Math.sin(a) * 8.6f, stroke);
                }
                c.drawCircle(12, 12, 6.2f, stroke);
                break;
            default:
                break;
        }
        c.restore();
    }

    private static String ellipsize(String s, TextPaint p, float width) {
        return TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
    }
}
