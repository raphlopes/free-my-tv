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
 * Projo : l'en-tête (heure, date, météo), « Ce soir » et « Apps » en images 16:9 avec légende, puis les raccourcis (réglages du projecteur, entrées).
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

    private static final int C_RESUME = 0, C_PICK = 1, C_APP = 2, C_ALL = 3, C_CONTROL = 8;

    private static final class Cell {
        int kind;
        final RectF r = new RectF();
        float radius;
        Maison.Resume resume;
        Recs.Pick pick;
        HomeApps.Item item;
        String action, title, sub, icon;
        boolean frame, warm;
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
    private String movingApp;  // dock : l'app qu'on déplace (appui long sur OK, puis Gauche/Droite, OK pour poser)
    private Cell removing;     // carte Reprendre en attente de confirmation (appui long sur OK)
    private boolean okDown;    // OK enfoncé sur une carte Reprendre : on n'ouvre qu'au relâchement, si ce n'était pas un appui long
    private boolean touched;   // faux tant que personne n'a bougé : le focus suit alors le premier élément (Reprendre)

    // Écran « Toutes les apps » / « Entrées »
    private boolean sheet;
    private String sheetTitle;
    private final List<Cell> sheetCells = new ArrayList<>();
    private int sheetIndex;

    // Projo : la pastille de l'en-tête (au-dessus de la première rangée) ouvre le panneau (PanelView, posé sur l'accueil)
    private boolean head;
    private final Cell headCell = new Cell();
    private final PanelView panel;
    private List<Cell> tonight = new ArrayList<>();
    private Cell shelf;            // le titre mis en avant : le dernier contenu focalisé (fond et texte du haut)
    private float dockRight;
    private float tonightScroll;   // défilement de « Ce soir » (px maquette), animé vers la carte focalisée

    // Données
    private List<Maison.Resume> resumes = new ArrayList<>();
    private List<HomeApps.Item> apps = new ArrayList<>();
    private List<HomeApps.Item> allApps = new ArrayList<>();
    private List<HomeApps.Item> inputs = new ArrayList<>();
    private int loadGeneration;

    // Dessin
    private float k = 1;
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
        panel = new PanelView(a, this::invalidate);
        a.getApplicationContext().registerReceiver(unhide, new android.content.IntentFilter(PanelView.UNHIDE));
        headCell.kind = C_CONTROL;
        headCell.radius = 28;
        headCell.r.set(W - SAFE_X - 56, SAFE_Y, W - SAFE_X, SAFE_Y + 56);
        rebuild();
    }

    static Typeface font(Context c, String path, int weight) {
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

    private final android.content.BroadcastReceiver unhide = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {   // « Titres retirés » du panneau
            Maison.unhideAll(c);
            if (running) load();
        }
    };

    void destroy() {
        pause();
        activity.getApplicationContext().unregisterReceiver(unhide);
    }

    /** Accueil pressé sur l'accueil : retour au début, écran des apps fermé. */
    void reset(boolean animated) {
        touched = false;
        sheet = false;
        head = false;
        panel.close();
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
            List<Maison.Resume> res = Maison.resume(c, 6, () -> { if (running) load(); });
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
            List<String> pinned = Maison.dock(c);
            if (pinned != null) {   // dock choisi à la main : son ordre, puis le reste
                order.removeAll(pinned);
                order.addAll(0, pinned);
            } else if (order.remove(Maison.STREMIO)) order.add(0, Maison.STREMIO);   // sinon l'app de tous les soirs en premier
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
        buildProjo(recs.now(null, 5, Maison.hiddenPicks(activity)));
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

    private static final float CARDS_Y = 518, DOCK_Y = 776, DOCK_PAD = 42, DOCK_W = 250, DOCK_H = 141, DOCK_GAP = 24;

    private void buildProjo(List<Recs.Pick> picks) {
        // En haut, le titre mis en avant sur son image ; puis « Ce soir » (reprises, puis suggestions) ; puis le dock des apps.
        tonight = new ArrayList<>();
        for (Maison.Resume r : resumes) if (tonight.size() < 5) tonight.add(resumeCell(r));
        int room = tonight.size() + 5;   // puis jusqu'à cinq suggestions : la rangée défile
        for (Recs.Pick p : picks) if (tonight.size() < room) tonight.add(pickCell(p));
        if (!tonight.isEmpty()) {
            float x = SAFE_X;
            for (Cell c : tonight) {
                c.frame = true;
                c.radius = 16;
                c.r.set(x, CARDS_Y, x + 320, CARDS_Y + 180);
                x += 320 + GAP;
            }
            addRow("Ce soir", tonight);
        }
        List<Cell> dock = new ArrayList<>();
        float x = SAFE_X + DOCK_PAD, y = DOCK_Y + 28;
        for (HomeApps.Item it : apps) {
            if (dock.size() >= 5) break;
            Cell c = new Cell();
            c.kind = C_APP;
            c.item = it;
            dock.add(c);
        }
        Cell all = new Cell();
        all.kind = C_ALL;
        all.title = "Toutes les apps";
        dock.add(all);
        for (Cell c : dock) {
            c.radius = 14;
            c.r.set(x, y, x + DOCK_W, y + DOCK_H);
            x += DOCK_W + DOCK_GAP;
        }
        dockRight = x - DOCK_GAP + DOCK_PAD;
        addRow(null, dock);
        shelf = null;
    }

    private void saveDock() {
        List<String> pkgs = new ArrayList<>();
        for (HomeApps.Item it : apps) if (pkgs.size() < 5) pkgs.add(it.pkg);
        Maison.saveDock(activity, pkgs);
    }

    PanelView panel() {
        return panel;
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
        if (panel.isOpen()) return panel.key(e);
        boolean ok = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER
                || e.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (e.getAction() == KeyEvent.ACTION_UP && ok && okDown) {
            okDown = false;
            activate();
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return isOurs(e.getKeyCode());
        boolean held = e.getRepeatCount() > 0;
        if (movingApp != null) {
            int i = -1, n = Math.min(5, apps.size());
            for (int j = 0; j < n; j++) if (apps.get(j).pkg.equals(movingApp)) i = j;
            boolean side = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT;
            int to = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? i - 1 : i + 1;
            if (side) {
                if (i >= 0 && to >= 0 && to < n) {
                    java.util.Collections.swap(apps, i, to);
                    rebuild();
                }
            } else if (!held && isOurs(e.getKeyCode())) {   // toute autre touche pose
                movingApp = null;
                saveDock();
                invalidate();
            }
            return true;
        }
        Cell at = focused();
        if (ok && at != null && (at.kind == C_RESUME || at.kind == C_PICK || (at.kind == C_APP && at.item.kind == HomeApps.APP))) {
            if (!held && removing == at) {   // confirmation
                if (at.resume != null) {
                    Maison.hide(activity, at.resume);
                    resumes.remove(at.resume);
                } else Maison.hidePick(activity, at.pick.id);   // la suivante prend sa place
                removing = null;
                rebuild();
            } else if (!held) {
                okDown = true;
            } else if (okDown && e.isLongPress()) {
                okDown = false;
                if (at.kind == C_RESUME || at.kind == C_PICK) removing = at;
                else if (!sheet) movingApp = at.item.pkg;
                else {   // « Toutes les apps » : épingle en tête du dock
                    apps.remove(at.item);
                    apps.add(0, at.item);
                    saveDock();
                    sheet = false;
                    rebuild();
                    load();
                }
                invalidate();
            }
            return true;
        }
        if (removing != null) {   // toute autre touche : on garde
            removing = null;
            invalidate();
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        }
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
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_NOTIFICATION:   // la roue crantée : sur l'accueil, la touche nous arrive directement
            case KEY_TCL_GEAR:
                if (sheet) return false;
                panel.open();
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

    static final int KEY_TCL_GEAR = 4001;   // télécommande TCL : sa roue crantée envoie ce code maison (relevé le 22/11/2026)

    private static boolean isOurs(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_NOTIFICATION || code == KEY_TCL_GEAR;
    }

    private void move(int dx, int dy, boolean held) {
        touched = true;
        if (head) {        } else if (head) {
            if (dy > 0) head = false;
        } else if (sheet) {
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
                if (next < 0) head = true;
                if (next < 0 || next >= rows.size()) {
                    invalidate();
                    return;
                }
                RectF from = rows.get(row).get(col).r;
                float cx = from.left + Math.min(from.width(), 219) / 2;   // depuis le héros : la première app, pas celle du milieu
                if (tonight.contains(rows.get(row).get(col))) cx -= tonightScroll;   // position à l'écran
                else if (rows.get(next) == tonight) cx += tonightScroll;
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
        if (head) return headCell;
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
                if (HomeApps.launchIntent(activity.getPackageManager(), Maison.STREMIO) != null) {
                    // Stremio a tout : sa fiche du titre (identifiant IMDb, demandé à TMDB) ; à défaut, sa recherche.
                    Recs.Pick pick = c.pick;
                    recs.imdb(pick, id -> handler.post(() -> {
                        String uri = id == null ? "stremio:///search?search=" + android.net.Uri.encode(pick.title)
                                : "movie".equals(pick.kind) ? "stremio:///detail/movie/" + id + "/" + id : "stremio:///detail/series/" + id;
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                                    .setPackage(Maison.STREMIO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (RuntimeException e) {
                            AppLog.w("suggestion : " + e.getMessage());
                        }
                    }));
                    break;
                }
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
            case C_CONTROL:
                panel.open();
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
        for (Cell c : allCells()) c.f = c == f ? 1 : 0;
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
        float want = tonightScroll;
        if (f != null && tonight.contains(f)) {   // la carte focalisée entière à l'écran
            if (f.r.right - want > W - SAFE_X) want = f.r.right - (W - SAFE_X);
            if (f.r.left - want < SAFE_X) want = f.r.left - SAFE_X;
        } else if (tonight.isEmpty()) want = 0;
        if (Math.abs(want - tonightScroll) > 0.5f) {
            tonightScroll += (want - tonightScroll) * Math.min(1, dt / 110);
            moving = true;
        } else tonightScroll = want;
        if (!moving) lastFrame = 0;
        return moving;
    }

    private List<Cell> allCells() {
        List<Cell> all = new ArrayList<>();
        for (List<Cell> r : rows) all.addAll(r);
        all.add(headCell);
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
    }

    @Override protected void onDraw(Canvas c) {
        boolean moving = stepFocus();
        c.drawColor(BG);
        c.save();
        c.scale(k, k);
        if (sheet) drawSheet(c);
        else drawProjo(c);
        c.restore();
        if (moving) postInvalidateOnAnimation();
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
            float right = W - SAFE_X - (56 + 28);
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
        boolean grows = !cell.frame;
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
                drawFrame(c, cell, r, f);
                break;
            case C_APP:
                if (cell.frame) drawFrame(c, cell, r, f); else drawApp(c, cell.item, r, cell.radius, f);
                break;
            case C_ALL:
                if (cell.frame) drawFrame(c, cell, r, f); else drawAll(c, r, cell.radius);
                break;
            default:
                break;
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

    private static final int WIDE = 1104;

    private static String wideUri(Maison.Resume r, boolean wide) {
        return wide && r.wide != null ? r.wide : r.image;
    }

    private static String pickUri(Recs.Pick p, boolean wide) {
        return p.backdrop == null ? null : "https://image.tmdb.org/t/p/" + (wide ? "w1280" : "w300") + p.backdrop;
    }

    private Bitmap imageOf(Cell cell, boolean wide) {
        if (cell.resume != null) return Maison.image(getContext(), wideUri(cell.resume, wide), wide ? WIDE : 352, this::invalidate);
        if (cell.pick != null) {
            // Projo : l'image TMDB telle quelle. Le Backdrop de l'amont (plein écran + versions floutées, 4 en cache)
            // pèse trop ici et tourne en rond dès qu'il y a cinq suggestions.
            String uri = pickUri(cell.pick, wide);
            return uri == null ? null : Maison.image(getContext(), uri, wide ? WIDE : 352, this::invalidate);
        }
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

    /** De bas en haut depuis `bottom` : jauge, méta, titre (serif), section. */
    private void heroText(Canvas c, Cell cell, float x, float bottom) {
        boolean resume = cell.resume != null;
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

    // MARK: - Projo

    private final RectF screen = new RectF(0, 0, W, H);
    // Le fond d'un titre : l'image et ses deux voiles, composés une fois à mi-résolution hors du fil d'affichage. Dessiner
    // l'image puis deux dégradés plein écran à chaque image coûtait 25 ms sur le projo (mesuré le 20/11).
    private final java.util.LinkedHashMap<String, Bitmap> backdrops = new java.util.LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> e) { return size() > 3; }
    };
    private final Set<String> composing = new HashSet<>();
    private Bitmap lastBackdrop;

    private Bitmap backdropOf(Cell cell) {
        String key = key(cell);
        Bitmap ready = backdrops.get(key);
        if (ready != null || composing.contains(key)) return ready;
        Bitmap src = imageOf(cell, true);
        if (src == null) return null;
        composing.add(key);
        String raw = cell.resume != null ? wideUri(cell.resume, true) : cell.pick != null ? pickUri(cell.pick, true) : null;
        new Thread(() -> {
            Bitmap out = null;
            try {
                out = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
                Canvas g = new Canvas(out);
                g.drawColor(BG);
                g.scale(0.5f, 0.5f);
                float sc = Math.max(W / src.getWidth(), H / src.getHeight());
                Matrix m = new Matrix();
                m.setScale(sc, sc);
                m.postTranslate((W - src.getWidth() * sc) / 2, (H - src.getHeight() * sc) / 2);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                g.drawBitmap(src, m, p);
                p.setShader(new LinearGradient(0, 0, W * 0.75f, 0,
                        new int[] {0xF202070A, 0xB302070A, 0x4002070A}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
                g.drawRect(0, 0, W, H, p);
                p.setShader(new LinearGradient(0, 300, 0, 740, 0x0002070A, 0xFF02070A, Shader.TileMode.CLAMP));
                g.drawRect(0, 0, W, H, p);
                out.setHasAlpha(false);
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("fond : " + e.getMessage());
                out = null;
            }
            Bitmap done = out;
            handler.post(() -> {
                composing.remove(key);
                if (done == null) return;
                backdrops.put(key, done);
                if (raw != null) Maison.forget(raw, WIDE);   // la grande image ne sert plus : seul le fond composé reste
                invalidate();
            });
        }, "maison-fond").start();
        return null;
    }

    private void drawProjo(Canvas c) {
        // Le titre mis en avant suit le focus tant qu'il est sur un contenu ; sur les apps, il garde le dernier.
        Cell f = focused();
        if (f != null && tonight.contains(f)) shelf = f;
        if (shelf == null && !tonight.isEmpty()) shelf = tonight.get(0);
        Bitmap bg = shelf == null ? null : backdropOf(shelf);
        if (bg != null) lastBackdrop = bg;
        else if (shelf == null) lastBackdrop = null;
        if (lastBackdrop != null) c.drawBitmap(lastBackdrop, null, screen, image);   // en attendant le suivant : le précédent
        drawHeader(c);
        if (headCell.f > 0) drawFocus(c, headCell.r, headCell.radius, ease(headCell.f));
        fill.setColor(0x14EEF3F6);
        c.drawRoundRect(headCell.r, 28, 28, fill);
        drawIcon(c, "sliders", headCell.r.centerX(), headCell.r.centerY(), 28, headCell.f > 0.5f ? ink : ink2);
        if (shelf != null) {
            heroText(c, shelf, SAFE_X, CARDS_Y - 68);
        } else {
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            String hello = hour >= 18 || hour < 5 ? "Bonsoir" : "Bonjour";
            heroTitle.setTextSize(96);
            meta.setColor(ink2);
            c.drawText("Rien en cours. Choisis une app.", SAFE_X, CARDS_Y - 68, meta);
            c.drawText(hello, SAFE_X - Ui.leftBearing(heroTitle, hello), CARDS_Y - 68 - 46, heroTitle);
        }
        // Le dock : un verre sans flou (un voile clair et son filet).
        tmp2.set(SAFE_X, DOCK_Y, dockRight, DOCK_Y + 28 + DOCK_H + 52);
        fill.setColor(0x0FEEF3F6);
        c.drawRoundRect(tmp2, 40, 40, fill);
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(0x24EEF3F6);
        c.drawRoundRect(tmp2, 40, 40, stroke);
        for (int r = 0; r < rows.size(); r++) {
            List<Cell> cells = rows.get(r);
            String title = rowTitles.get(r);
            if (title != null) {
                section.setColor(muted);
                c.drawText(title.toUpperCase(java.util.Locale.ROOT), SAFE_X, cells.get(0).r.top - 28, section);
            }
            boolean scrolls = cells == tonight;
            if (scrolls) {
                c.save();
                c.translate(-tonightScroll, 0);
            }
            Cell top = null;   // l'élément focalisé en dernier : il grandit sur ses voisins
            for (Cell cell : cells) {
                if (cell.f > 0 && (top == null || cell.f > top.f)) {
                    if (top != null) drawCell(c, top);   // celui qu'on quitte reste dessiné (sinon il clignote)
                    top = cell;
                } else drawCell(c, cell);
            }
            if (top != null) {
                drawCell(c, top);
            }
            if (scrolls) c.restore();
            for (Cell cell : cells) {   // dock : le nom de l'app focalisée sous sa tuile, en fondu croisé
                if (cell.frame || cell.kind != C_APP || cell.f <= 0) continue;
                meta.setTextSize(22);
                boolean move = cell.item.pkg.equals(movingApp);
                meta.setColor(move ? WARM : Ui.alpha(ink, cell.f * cell.f));
                c.drawText(move ? "‹  Déplacer  ›" : ellipsize(cell.item.label, meta, DOCK_W), cell.r.left, cell.r.bottom + 38, meta);
                meta.setTextSize(24);
            }
        }
    }

    /** Une carte de « Ce soir » : l'image, la jauge dedans, le titre dessous (le détail est en haut de l'écran). */
    private void drawFrame(Canvas c, Cell cell, RectF r, float f) {
        cover(c, imageOf(cell, false), r, cell.radius);
        if (cell.resume != null && cell.resume.progress > 0) {
            float x0 = r.left + 16, x1 = r.right - 16, y = r.bottom - 14;
            fill.setColor(0x9902070A);
            tmp2.set(x0, y - 5, x1, y);
            c.drawRoundRect(tmp2, 2.5f, 2.5f, fill);
            fill.setColor(WARM);
            tmp2.set(x0, y - 5, x0 + (x1 - x0) * cell.resume.progress, y);
            c.drawRoundRect(tmp2, 2.5f, 2.5f, fill);
        }
        boolean ask = cell == removing;
        meta.setColor(ask ? WARM : f > 0.5f ? ink : muted);
        c.drawText(ask ? "Retirer ? OK · Retour" : ellipsize(cell.title == null ? "" : cell.title, meta, 320), cell.r.left, cell.r.bottom + 44, meta);
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
            if (cell.f > 0 && (top == null || cell.f > top.f)) {
                if (top != null) drawSheetCell(c, top);
                top = cell;
            } else drawSheetCell(c, cell);
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
        icon(c, name, cx, cy, size, color);
    }

    private static final Paint ICON_STROKE = new Paint(Paint.ANTI_ALIAS_FLAG), ICON_FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path ICON_PATH = new Path();
    private static final RectF ICON_RECT = new RectF();
    static { ICON_STROKE.setStyle(Paint.Style.STROKE); }

    /** Fil d'affichage seulement (pinceaux partagés). */
    static void icon(Canvas c, String name, float cx, float cy, float size, int color) {
        if (name == null) return;
        Paint stroke = ICON_STROKE, fill = ICON_FILL;
        Path path = ICON_PATH;
        RectF tmp2 = ICON_RECT;
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
            case "moon":
                tmp2.set(3.5f, 4.5f, 19.5f, 20.5f);
                path.arcTo(tmp2, 15, 240, true);
                path.quadTo(10.5f, 13.5f, 19.23f, 14.57f);
                path.close();
                c.drawPath(path, stroke);
                break;
            case "timer":
                c.drawCircle(12, 13.5f, 7.5f, stroke);
                c.drawLine(12, 13.5f, 12, 9.5f, stroke);
                c.drawLine(9.5f, 3, 14.5f, 3, stroke);
                break;
            case "undo":
                path.moveTo(8.5f, 5.5f);
                path.lineTo(4, 10);
                path.lineTo(8.5f, 14.5f);
                path.moveTo(4, 10);
                path.lineTo(15, 10);
                tmp2.set(10.5f, 10, 19.5f, 19);
                path.arcTo(tmp2, -90, 180, false);
                path.lineTo(10, 19);
                c.drawPath(path, stroke);
                break;
            case "sliders":
                c.drawLine(4, 8, 20, 8, stroke);
                c.drawLine(4, 16, 20, 16, stroke);
                c.drawCircle(9, 8, 2.6f, fill);
                c.drawCircle(15, 16, 2.6f, fill);
                break;
            case "bluetooth":
                path.moveTo(7, 7.5f);
                path.lineTo(16.5f, 16.5f);
                path.lineTo(12, 21);
                path.lineTo(12, 3);
                path.lineTo(16.5f, 7.5f);
                path.lineTo(7, 16.5f);
                c.drawPath(path, stroke);
                break;
            case "wifi":
                for (float rad : new float[] {5, 9.5f, 14}) {
                    tmp2.set(12 - rad, 19 - rad, 12 + rad, 19 + rad);
                    c.drawArc(tmp2, -135, 90, false, stroke);
                }
                c.drawCircle(12, 19, 1.4f, fill);
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
