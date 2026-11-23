package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Le panneau de contrôle (projo ; sur la TV : plus les scènes du salon et la sortie son) : le son en large, quatre tuiles (veille, projecteur, Bluetooth, réseau), puis les
 * entrées HDMI. Une vue à part, pour servir à deux endroits : dans l'accueil (pastille de l'en-tête, touche Menu) et
 * par-dessus n'importe quelle appli (roue crantée, fenêtre du service de touches) sans quitter ce qu'on regarde.
 * Mesures en px de la maquette 1920×1080 ; la vue fait la largeur du panneau plus la marge droite de l'écran, d'où
 * il glisse.
 */
final class PanelView extends View {
    static final float W = 600, MARGIN = 96, TOP = 54 + 56 + 20;
    private static final float PAD = 28;
    private static final int BG = 0xFF0A161D, INK = 0xFFDFE7EC, INK2 = 0xCCDFE7EC, MUTED = 0xFF9AABB8, WARM = 0xFFFC734D;
    private static final int VOLUME = 0, SLEEP = 1, SETTINGS = 2, BLUETOOTH = 3, NETWORK = 4, INPUT = 5, TIMER = 6, RESTORE = 7, SCENE = 8;
    private static final int[] TIMER_STEPS = {0, 30, 60, 120};   // minutes

    private static final class Cell {
        int kind;
        String title, icon, sub, inputId, action;
        boolean warm;
        final RectF r = new RectF();
        float f;
    }

    private final List<Cell> cells = new ArrayList<>();
    private final Runnable closed;
    private final float k;
    private final boolean tv = !Maison.projo();   // la TV : scènes du salon, sortie son, entrées branchées d'abord
    private float bottom, volume, slide;   // slide : 0 fermé .. 1 ouvert
    private String lightsOn;   // les lampes allumées d'après le hub ; null : inconnu
    private long epLeft;       // ce qui reste de l'épisode Stremio en cours (ms), 0 : inconnu
    private int index;
    private boolean open;
    private long lastFrame;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tile, meta, section;
    private final RectF tmp = new RectF();

    /** `closed` : appelé une fois le panneau sorti de l'écran. */
    PanelView(Context c, Runnable closed) {
        super(c);
        this.closed = closed;
        k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        tile = text(MaisonView.font(c, "fonts/Geist.ttf", 500), 28, INK);
        tile.setLetterSpacing(-0.02f);
        meta = text(MaisonView.font(c, "fonts/Geist.ttf", 500), 22, MUTED);
        section = text(MaisonView.font(c, "fonts/GeistMono.ttf", 500), 22, MUTED);
        section.setLetterSpacing(0.08f);
        stroke.setStyle(Paint.Style.STROKE);
        setFocusable(true);
        build();
    }

    private static TextPaint text(android.graphics.Typeface face, float size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    int widthPx() { return Math.round((W + MARGIN) * k); }
    int heightPx() { return Math.round(bottom * k) + 2; }
    int topPx() { return Math.round(TOP * k); }
    boolean isOpen() { return open; }

    private void build() {
        cells.clear();
        float x0 = PAD, x1 = W - PAD, y = PAD, half = (x1 - x0 - 16) / 2;
        cell(VOLUME, "Son", "speaker", x0, y, x1, y + 96);
        y += 96 + 16;
        cell(SLEEP, "Veille", "moon", x0, y, x0 + half, y + 132);
        // Mise au point, trapèze, luminosité : les réglages de XGIMI, on y mène.
        cell(SETTINGS, tv ? "Réglages" : "Projecteur", "settings", x1 - half, y, x1, y + 132);
        y += 132 + 16;
        if (!tv) {   // sur la TV, la place va au salon et aux entrées ; Bluetooth et réseau sont dans Réglages
            cell(BLUETOOTH, "Bluetooth", "bluetooth", x0, y, x0 + half, y + 132);
            cell(NETWORK, "Réseau", "wifi", x1 - half, y, x1, y + 132);
            y += 132 + 16;
        }
        cell(TIMER, timerTitle(), "timer", x0, y, x0 + half, y + 132);
        cell(RESTORE, "Titres retirés", "undo", x1 - half, y, x1, y + 132);
        y += 132;
        if (tv && Maison.hasHub()) {
            y += 62;   // l'intertitre « Salon »
            float third = (x1 - x0 - 24) / 3;
            String[][] scenes = {{"cinema", "Cinéma", "film"}, {"ambiance", "Ambiance", "bulb"}, {"eteindre", "Éteindre", "power"}};
            for (int i = 0; i < 3; i++) {
                Cell c = cell(SCENE, scenes[i][1], scenes[i][2], x0 + i * (third + 12), y, x0 + i * (third + 12) + third, y + 116);
                c.action = scenes[i][0];
                c.warm = Maison.sceneAt(getContext(), c.action) > 0;   // la dernière lancée d'ici
            }
            y += 116;
        }
        List<HomeApps.Item> inputs = new ArrayList<>();
        for (HomeApps.Item it : HomeApps.allInputs(getContext())) if (it.kind == HomeApps.INPUT) inputs.add(it);
        if (tv) inputs.sort((a, b) -> Boolean.compare(b.connected, a.connected));   // tri stable : les branchées d'abord
        float rowH = tv ? 62 : 76, gap = tv ? 10 : 12;
        boolean any = false;
        for (HomeApps.Item it : inputs) {
            float lead = any ? gap : 62;   // la première laisse la place de l'intertitre « Entrées »
            if (TOP + y + lead + rowH + PAD > 1080 - 54) break;   // shortcut: les entrées qui ne tiennent pas sont omises, faire défiler si ça manque
            y += lead;
            any = true;
            Cell c = cell(INPUT, it.label, "input", x0, y, x1, y + rowH);
            c.inputId = it.inputId;
            c.sub = it.connected ? "Branché" : "Rien de branché";
            c.warm = it.connected;
            y += rowH;
        }
        bottom = y + PAD;
    }

    private Cell cell(int kind, String title, String icon, float l, float t, float r, float b) {
        Cell c = new Cell();
        c.kind = kind;
        c.title = title;
        c.icon = icon;
        c.r.set(l, t, r, b);
        cells.add(c);
        return c;
    }

    void open() {
        build();   // les entrées ont pu changer
        readVolume();
        if (tv) Sound.refresh(getContext(), this::invalidate);   // la sortie son : chaque processus (accueil, service) la relit
        readLights();
        epLeft = Maison.episodeLeft(() -> epLeft = Maison.episodeLeft(null));
        index = 0;   // le son : ce qu'on vient régler le plus souvent
        for (Cell c : cells) c.f = c == cells.get(0) ? 1 : 0;
        open = true;
        invalidate();
    }

    private void readLights() {
        if (tv && Maison.hasHub()) Maison.lights(on -> { lightsOn = on; invalidate(); });
    }

    void close() {
        open = false;
        invalidate();
    }

    void toggle() {
        if (open) close(); else open();
    }

    // MARK: - Touches

    @Override public boolean dispatchKeyEvent(KeyEvent e) {   // fenêtre du service : la vue a le focus
        return key(e) || super.dispatchKeyEvent(e);
    }

    /** Vrai tant que le panneau est ouvert : aucune touche ne passe dessous. */
    boolean key(KeyEvent e) {
        if (!open) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); break;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) activate(cells.get(index));
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_NOTIFICATION:   // la roue crantée de la télécommande
            case MaisonView.KEY_TCL_GEAR:
                close();
                break;
            default:
                return false;   // volume, lecture… : comme d'habitude
        }
        return true;
    }

    private void move(int dx, int dy) {
        Cell f = cells.get(index);
        int next = next(dx, dy);
        if (dx != 0 && f.kind == VOLUME) {
            audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, dx > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            readVolume();
        } else if (next >= 0) index = next;
        else if (dx < 0 || dy < 0) close();   // sortir par la gauche ou par le haut referme
        invalidate();
    }

    /** La tuile la plus proche dans la direction donnée, ou -1 (le bord du panneau). */
    private int next(int dx, int dy) {
        RectF from = cells.get(index).r;
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            RectF to = cells.get(i).r;
            float along = dx != 0 ? (to.centerX() - from.centerX()) * dx : (to.centerY() - from.centerY()) * dy;
            // De côté : seulement une tuile de la même ligne. En vertical : la plus alignée (bord gauche d'abord).
            if (along <= 1 || (dx != 0 && Math.abs(to.centerY() - from.centerY()) > 1)) continue;
            float d = along + (dy != 0 ? Math.abs(to.left - from.left) * 0.5f : 0);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void activate(Cell c) {
        Context app = getContext().getApplicationContext();
        switch (c.kind) {
            case SLEEP:
                Maison.sleep(app, null);
                break;
            case TIMER: {   // OK fait défiler : non, fin de l'épisode (-1, si Stremio la connaît), 30 min, 1 h, 2 h
                android.content.SharedPreferences p = timerPrefs();
                int[] steps = epLeft > 0 ? new int[] {0, -1, 30, 60, 120} : TIMER_STEPS;
                int step = 0;
                for (int i = 0; i < steps.length; i++) if (steps[i] == p.getInt("minutes", 0) && timerAt() > 0) step = i;
                int minutes = steps[(step + 1) % steps.length];
                long at = minutes == 0 ? 0 : System.currentTimeMillis() + (minutes < 0 ? epLeft + 60_000 : minutes * 60_000L);
                android.app.AlarmManager am = (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
                android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(app, 1,
                        new Intent(app, HomeReceiver.class).setAction(HomeReceiver.SLEEP),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                if (at == 0) am.cancel(pi);
                else am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
                p.edit().putLong("at", at).putInt("minutes", minutes).commit();
                c.title = timerTitle();
                invalidate();
                return;
            }
            case RESTORE:   // l'accueil (autre processus) tient la liste : on le lui demande
                app.sendBroadcast(new Intent(UNHIDE).setPackage(app.getPackageName()));
                break;
            case SETTINGS:
                start(new Intent(Settings.ACTION_SETTINGS));
                break;
            case BLUETOOTH:
                // Pas d'écran Bluetooth sur Google TV : celui qui appaire un accessoire (télécommande, enceinte).
                start(new Intent("com.google.android.intent.action.CONNECT_INPUT"));
                break;
            case NETWORK:
                start(new Intent(Settings.ACTION_WIFI_SETTINGS));
                break;
            case INPUT:
                start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(c.inputId)));
                break;
            case SCENE:   // le panneau reste ouvert : la tuile dit si le hub a répondu
                c.title = "…";
                invalidate();
                Maison.salon(c.action, ok -> {
                    if (ok) Maison.lastScene(app, c.action);
                    c.title = ok ? "Lancé" : "Injoignable";
                    c.warm = ok && !"eteindre".equals(c.action);
                    invalidate();
                    postDelayed(this::readLights, 3_000);   // le temps que les lampes répondent
                });
                return;
            case VOLUME:   // TV : OK bascule haut-parleurs ⇄ barre de son
                if (tv && Sound.out() != null) Sound.toggle(app, ok -> invalidate());
                return;
            default:
                return;
        }
        close();
    }

    private void start(Intent i) {
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {   // pas d'écran dédié sur ce firmware : les réglages complets
            AppLog.w("panneau : " + e.getMessage());
            try {
                getContext().startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
            }
        }
    }

    static final String UNHIDE = "io.freemytv.UNHIDE";

    // Le panneau vit dans deux processus (accueil, service de touches) : un fichier relu à chaque ouverture.
    @SuppressWarnings("deprecation")
    private android.content.SharedPreferences timerPrefs() {
        return getContext().getSharedPreferences("minuterie", Context.MODE_MULTI_PROCESS);
    }

    private long timerAt() {
        long at = timerPrefs().getLong("at", 0);
        return at > System.currentTimeMillis() ? at : 0;
    }

    private String timerTitle() {
        long at = timerAt();
        return at == 0 ? "Minuterie" : (timerPrefs().getInt("minutes", 0) < 0 ? "Épisode " : "Veille à ") + Ui.clock(at);
    }

    private final android.content.BroadcastReceiver volumeChanged = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {   // réglé à la télécommande pendant que le panneau est ouvert
            readVolume();
            invalidate();
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().registerReceiver(volumeChanged, new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"));
    }

    @Override protected void onDetachedFromWindow() {
        getContext().unregisterReceiver(volumeChanged);
        super.onDetachedFromWindow();
    }

    private AudioManager audio() {
        return (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
    }

    // Lu à l'ouverture et à chaque changement (deux appels système, trop chers à chaque image).
    private void readVolume() {
        AudioManager am = audio();
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        volume = max > 0 ? (float) am.getStreamVolume(AudioManager.STREAM_MUSIC) / max : 0;
    }

    // MARK: - Dessin

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(widthPx(), heightPx());
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        lastFrame = now;
        boolean moving = false;
        float target = open ? 1 : 0;
        if (slide != target) {
            slide = target > slide ? Math.min(1, slide + dt / 220) : Math.max(0, slide - dt / 220);
            moving = true;
            if (slide == 0 && !open) post(closed);
        }
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            float t = i == index ? 1 : 0;
            if (cell.f == t) continue;
            cell.f = t > cell.f ? Math.min(1, cell.f + dt / 150) : Math.max(0, cell.f - dt / 150);
            moving = true;
        }
        if (!moving) lastFrame = 0;
        if (slide <= 0) return;
        c.save();
        c.scale(k, k);
        c.translate((1 - ease(slide)) * (W + MARGIN + 8), 0);   // il glisse depuis le bord droit
        tmp.set(1, 1, W - 1, bottom - 1);
        fill.setColor(BG);
        c.drawRoundRect(tmp, 36, 36, fill);
        stroke.setStrokeWidth(1.5f);
        stroke.setColor(0x24EEF3F6);
        c.drawRoundRect(tmp, 36, 36, stroke);
        boolean label = false;
        for (Cell cell : cells) {
            RectF r = cell.r;
            float f = ease(cell.f);
            if (cell.kind == INPUT && !label) {
                label = true;
                section.setColor(MUTED);
                c.drawText("ENTRÉES", r.left + 4, r.top - 22, section);
            }
            if (cell.kind == SCENE && "cinema".equals(cell.action)) {
                section.setColor(MUTED);
                c.drawText("SALON", r.left + 4, r.top - 22, section);
                if (lightsOn != null) {   // ce qui est allumé, d'après le hub
                    String on = lightsOn.isEmpty() ? "Tout est éteint"
                            : android.text.TextUtils.ellipsize(lightsOn, meta, 380, android.text.TextUtils.TruncateAt.END).toString();
                    meta.setColor(lightsOn.isEmpty() ? MUTED : WARM);
                    c.drawText(on, W - PAD - 4 - meta.measureText(on), r.top - 22, meta);
                }
            }
            if (f > 0) {   // le focus de l'accueil : un trait encre à 6 px du bord
                stroke.setStrokeWidth(3);
                stroke.setColor(Ui.alpha(INK, f));
                tmp.set(r.left - 7.5f, r.top - 7.5f, r.right + 7.5f, r.bottom + 7.5f);
                c.drawRoundRect(tmp, 24 + 7.5f, 24 + 7.5f, stroke);
            }
            fill.setColor(0x12EEF3F6);
            c.drawRoundRect(r, 24, 24, fill);
            if (f > 0) {
                fill.setColor(Ui.alpha(0x1FEEF3F6, f));
                c.drawRoundRect(r, 24, 24, fill);
            }
            int icon = f > 0.5f ? INK : INK2;
            if (cell.kind == VOLUME) {
                MaisonView.icon(c, cell.icon, r.left + 24 + 16, r.top + 36, 32, icon);
                c.drawText(cell.title, r.left + 24 + 32 + 16, r.top + 36 + Ui.cap(tile) / 2, tile);
                String pct = Math.round(volume * 100) + " %";
                section.setColor(INK2);
                float px = r.right - 24 - section.measureText(pct);
                c.drawText(pct, px, r.top + 36 + Ui.cap(section) / 2, section);
                if (tv && Sound.out() != null) {   // la sortie en cours ; OK la bascule
                    boolean bar = Sound.OPTICAL.equals(Sound.out());
                    String out = bar ? "Barre de son" : "Haut-parleurs TV";
                    meta.setColor(bar ? WARM : MUTED);
                    c.drawText(out, px - 20 - meta.measureText(out), r.top + 36 + Ui.cap(meta) / 2, meta);
                }
                float x0 = r.left + 24, x1 = r.right - 24, y = r.bottom - 22;
                fill.setColor(0x2EEEF3F6);
                tmp.set(x0, y - 6, x1, y);
                c.drawRoundRect(tmp, 3, 3, fill);
                fill.setColor(INK);
                tmp.set(x0, y - 6, x0 + (x1 - x0) * volume, y);
                c.drawRoundRect(tmp, 3, 3, fill);
            } else if (cell.kind == INPUT) {
                MaisonView.icon(c, cell.icon, r.left + 24 + 16, r.centerY(), 32, icon);
                c.drawText(android.text.TextUtils.ellipsize(cell.title, tile, 260, android.text.TextUtils.TruncateAt.END).toString(),
                        r.left + 24 + 32 + 16, r.centerY() + Ui.cap(tile) / 2, tile);
                meta.setColor(cell.warm ? WARM : MUTED);
                c.drawText(cell.sub, r.right - 24 - meta.measureText(cell.sub), r.centerY() + Ui.cap(meta) / 2, meta);
            } else if (cell.kind == SCENE) {   // tuile étroite : le nom en petit
                MaisonView.icon(c, cell.icon, r.left + 18 + 16, r.top + 20 + 16, 32, cell.warm ? WARM : icon);
                meta.setColor(f > 0.5f ? INK : INK2);
                c.drawText(cell.title, r.left + 18, r.bottom - 22, meta);
            } else {   // tuile : le pictogramme en haut, le nom en bas
                MaisonView.icon(c, cell.icon, r.left + 24 + 18, r.top + 24 + 18, 36, icon);
                c.drawText(cell.title, r.left + 24, r.bottom - 26, tile);
            }
        }
        c.restore();
        if (moving) postInvalidateOnAnimation();
    }

    /** Une annonce du hub (sonnette, lessive…) : un bandeau en haut à droite, par-dessus ce qu'on regarde. Hauteur : 120 px de maquette. */
    static View banner(Context c, String title, String text) {
        float k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        TextPaint big = text(MaisonView.font(c, "fonts/Geist.ttf", 500), 28, INK), small = text(MaisonView.font(c, "fonts/Geist.ttf", 500), 22, MUTED);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        return new View(c) {
            @Override protected void onDraw(Canvas g) {
                g.scale(k, k);
                p.setStyle(Paint.Style.FILL);
                p.setColor(BG);
                g.drawRoundRect(1, 1, W - 1, 119, 28, 28, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f);
                p.setColor(0x24EEF3F6);
                g.drawRoundRect(1, 1, W - 1, 119, 28, 28, p);
                MaisonView.icon(g, "bulb", PAD + 16, 60, 32, WARM);
                float x = PAD + 32 + 20, w = W - x - PAD;
                g.drawText(android.text.TextUtils.ellipsize(title, big, w, android.text.TextUtils.TruncateAt.END).toString(), x, text.isEmpty() ? 60 + Ui.cap(big) / 2 : 52, big);
                if (!text.isEmpty()) g.drawText(android.text.TextUtils.ellipsize(text, small, w, android.text.TextUtils.TruncateAt.END).toString(), x, 88, small);
            }
        };
    }

    private static float ease(float t) {
        float u = 1 - t;
        return 1 - u * u * u * u;
    }
}
