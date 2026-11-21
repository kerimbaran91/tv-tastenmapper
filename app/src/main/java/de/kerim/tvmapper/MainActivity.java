package de.kerim.tvmapper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int BG = 0xFF101418;
    private static final int CARD = 0xFF1C232B;
    private static final int ACCENT = 0xFF3D8BFD;
    private static final int OK = 0xFF4CD27A;
    private static final int WARN = 0xFFFFB547;
    private static final int BAD = 0xFFFF5C5C;
    private static final int MUTED = 0xFFAAB4C0;
    private static final int TEXT = 0xFFD0D7DE;

    /** Ab Android 13 sperrt das System die Bedienungshilfe für nachinstallierte Apps oft. */
    private static final boolean RESTRICTED = Build.VERSION.SDK_INT >= 33;

    private TextView tvService, tvHeal, tvKey, tvLearn, tvUpdate, tvDiag, tvDiagTitle;
    private Button btnLearn, btnToggle, btnUpdate, btnDiag, btnMethod;
    private LinearLayout diagCard;
    private volatile Updater.Release latest;
    private volatile boolean updateBusy;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Prefs.ACTION_LEARNED.equals(intent.getAction())) onLearned();
            else refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.load(this);
        setContentView(buildUi());
        Watchdog.schedule(this);
        checkForUpdate(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Prefs.ACTION_LEARNED);
        f.addAction(Prefs.ACTION_DIAG);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        // Falls die ADB-Berechtigung inzwischen erteilt wurde: Dienst gleich einschalten
        new Thread(() -> Healer.ensure(getApplicationContext(), false)).start();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        try {
            unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
    }

    /** Fallback, falls der Dienst nicht läuft: Tasten, die hier ankommen. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        boolean firstDown = ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0;
        if (Prefs.isDiag() && firstDown && !Healer.isRunning(this)) {
            Diag.record(ev, "App");
            refresh();
        }
        if (Prefs.isLearning() && !Prefs.ignoredForLearning(ev.getKeyCode())) {
            if (firstDown) {
                Prefs.saveLearned(this, ev.getKeyCode(), ev.getScanCode());
                onLearned();
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    private void onLearned() {
        Toast.makeText(this, "Taste gespeichert: " + Prefs.describeLearned(this), Toast.LENGTH_LONG).show();
        refresh();
    }

    private void refresh() {
        boolean running = Healer.isRunning(this);
        boolean listed = Healer.isEnabledInSettings(this);
        boolean perm = Healer.hasPermission(this);

        if (running) {
            setStatus(tvService, "Dienst: aktiv", OK);
        } else if (listed) {
            setStatus(tvService, "Dienst: eingeschaltet, startet gerade …", WARN);
        } else if (RESTRICTED && !perm) {
            setStatus(tvService, "Dienst: AUS – bei Google TV per ADB einrichten (siehe rechts)", BAD);
        } else {
            setStatus(tvService, "Dienst: AUS – bitte aktivieren", BAD);
        }

        if (perm) {
            setStatus(tvHeal, "Selbstheilung: eingerichtet (läuft nach Neustart automatisch)", OK);
        } else {
            setStatus(tvHeal, "Selbstheilung: fehlt – einmalig ADB-Befehl nötig", WARN);
        }

        setStatus(tvKey, "TV+-Taste: " + Prefs.describeLearned(this),
                Prefs.hasLearned(this) ? OK : WARN);

        long left = Prefs.learningRemainingMs();
        if (left > 0) {
            tvLearn.setVisibility(View.VISIBLE);
            tvLearn.setText("Jetzt die Xiaomi-TV+-Taste drücken … (" + ((left + 999) / 1000) + " s)");
            btnLearn.setText("Anlernen abbrechen");
        } else {
            tvLearn.setVisibility(View.GONE);
            btnLearn.setText("TV+-Taste anlernen");
        }

        btnToggle.setText(Prefs.isEnabled(this) ? "Umbelegung: AN" : "Umbelegung: AUS");
        btnMethod.setText("Methode: " + Prefs.methodName(Prefs.getMethod(this)));

        long dleft = Prefs.diagRemainingMs();
        if (dleft > 0) {
            diagCard.setVisibility(View.VISIBLE);
            tvDiagTitle.setText("Tasten-Diagnose läuft (" + ((dleft + 999) / 1000)
                    + " s) – drück jetzt die TV+-Taste");
            tvDiag.setText(Diag.text());
            btnDiag.setText("Diagnose beenden");
        } else {
            btnDiag.setText("Tasten-Diagnose");
            tvDiagTitle.setText("Tasten-Diagnose (aus) – letzte Tasten:");
            tvDiag.setText(Diag.text());
        }
    }

    private void setStatus(TextView tv, String text, int color) {
        tv.setText("●  " + text);
        tv.setTextColor(color);
    }

    // ---------- Aktionen ----------

    private void onLearnClicked() {
        if (Prefs.isLearning()) {
            Prefs.stopLearning();
        } else {
            if (!Healer.isRunning(this)) {
                Toast.makeText(this, "Hinweis: Dienst läuft nicht – Anlernen klappt dann evtl. nicht.",
                        Toast.LENGTH_LONG).show();
            }
            Prefs.startLearning();
        }
        refresh();
    }

    private void onDiagClicked() {
        if (Prefs.isDiag()) {
            Prefs.stopDiag();
        } else {
            Diag.clear();
            Prefs.startDiag();
            diagCard.setVisibility(View.VISIBLE);
            if (!Healer.isRunning(this)) {
                Toast.makeText(this, "Dienst läuft nicht – es werden nur Tasten angezeigt, die bei der App ankommen.",
                        Toast.LENGTH_LONG).show();
            }
        }
        refresh();
    }

    private void onToggleClicked() {
        Prefs.setEnabled(this, !Prefs.isEnabled(this));
        refresh();
    }

    private void onActivateClicked() {
        if (Healer.hasPermission(this)) {
            new Thread(() -> {
                boolean ok = Healer.ensure(getApplicationContext(), true);
                runOnUiThread(() -> {
                    Toast.makeText(this, ok ? "Dienst wird aktiviert …" : "Aktivieren fehlgeschlagen",
                            Toast.LENGTH_SHORT).show();
                    refresh();
                });
            }).start();
            return;
        }
        if (RESTRICTED) {
            Toast.makeText(this, "Google TV sperrt das oft. Am sichersten: den ADB-Befehl rechts ausführen, "
                    + "dann schaltet sich die App selbst ein.", Toast.LENGTH_LONG).show();
        }
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(this, "Einstellungen gesperrt – bitte ADB-Befehl verwenden (siehe rechts)",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onMethodClicked() {
        int m = (Prefs.getMethod(this) + 1) % Prefs.METHOD_COUNT;
        Prefs.setMethod(this, m);
        Toast.makeText(this, "Methode: " + Prefs.methodName(m) + " – jetzt im Video mit TV+ testen",
                Toast.LENGTH_LONG).show();
        refresh();
    }

    private void onTestClicked() {
        try {
            MapperService.sendPlayPause(this, MapperService.instance);
            Toast.makeText(this, "Play/Pause gesendet (" + Prefs.methodName(Prefs.getMethod(this)) + ")",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Fehler beim Senden", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Updates ----------

    private void setUpdateText(String s, int color) {
        runOnUiThread(() -> {
            tvUpdate.setText(s);
            tvUpdate.setTextColor(color);
        });
    }

    private void onUpdateClicked() {
        if (updateBusy) return;
        Updater.Release r = latest;
        if (r != null && r.build > Updater.installedBuild(this)) {
            installUpdate(r);
        } else {
            checkForUpdate(true);
        }
    }

    private void checkForUpdate(boolean manual) {
        if (updateBusy) return;
        updateBusy = true;
        setUpdateText("Suche nach Updates …", MUTED);
        new Thread(() -> {
            try {
                Updater.Release r = Updater.fetchLatest();
                latest = r;
                int installed = Updater.installedBuild(this);
                if (r.build > installed) {
                    setUpdateText("Neue Version " + r.name + " verfügbar (installiert: "
                            + Updater.installedName(this) + ")", WARN);
                    runOnUiThread(() -> btnUpdate.setText("Update auf " + r.name + " installieren"));
                } else {
                    setUpdateText("Version " + Updater.installedName(this) + " ist aktuell", OK);
                    runOnUiThread(() -> btnUpdate.setText("Nach Updates suchen"));
                }
            } catch (Throwable t) {
                setUpdateText(manual ? "Update-Suche fehlgeschlagen: " + t.getMessage()
                                : "Version " + Updater.installedName(this) + " (GitHub nicht erreichbar)",
                        manual ? BAD : MUTED);
            } finally {
                updateBusy = false;
            }
        }).start();
    }

    private void installUpdate(Updater.Release r) {
        if (!Updater.canInstall(this)) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(this, "Bitte „Installation erlauben“ einschalten, dann zurück und erneut drücken",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                setUpdateText("Installation nicht erlaubt – bitte einmalig per ADB freigeben (siehe rechts)", BAD);
            }
            return;
        }
        updateBusy = true;
        setUpdateText("Lade Version " + r.name + " herunter …", ACCENT);
        new Thread(() -> {
            try {
                Updater.downloadAndInstall(getApplicationContext(), r,
                        pct -> setUpdateText("Lade Version " + r.name + " herunter … " + pct + " %", ACCENT));
                setUpdateText("Download fertig – bitte „Installieren“ bestätigen", OK);
            } catch (Throwable t) {
                setUpdateText("Update fehlgeschlagen: " + t.getMessage(), BAD);
            } finally {
                updateBusy = false;
            }
        }).start();
    }

    // ---------- Oberfläche ----------

    private static String deviceLine() {
        String model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        return model + "  ·  Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(48), dp(32), dp(48), dp(32));
        scroll.addView(root);

        // ---- Linke Spalte: Status + Knöpfe ----
        LinearLayout left = column();
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = text("TV-Tastenmapper", 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);
        left.addView(text("Xiaomi-TV+-Taste  →  Play/Pause", 18, MUTED));
        left.addView(text(deviceLine(), 14, MUTED));
        left.addView(space(16));

        LinearLayout statusCard = card();
        tvService = text("", 17, Color.WHITE);
        tvHeal = text("", 17, Color.WHITE);
        tvKey = text("", 17, Color.WHITE);
        statusCard.addView(tvService);
        statusCard.addView(space(8));
        statusCard.addView(tvHeal);
        statusCard.addView(space(8));
        statusCard.addView(tvKey);
        left.addView(statusCard);
        left.addView(space(12));

        tvLearn = text("", 20, ACCENT);
        tvLearn.setTypeface(Typeface.DEFAULT_BOLD);
        tvLearn.setVisibility(View.GONE);
        left.addView(tvLearn);

        btnLearn = button("TV+-Taste anlernen", v -> onLearnClicked());
        btnDiag = button("Tasten-Diagnose", v -> onDiagClicked());
        btnToggle = button("Umbelegung: AN", v -> onToggleClicked());
        btnMethod = button("Methode", v -> onMethodClicked());
        Button btnActivate = button("Dienst aktivieren", v -> onActivateClicked());
        Button btnTest = button("Play/Pause testen", v -> onTestClicked());
        left.addView(btnLearn);
        left.addView(btnMethod);
        left.addView(btnDiag);
        left.addView(btnToggle);
        left.addView(btnActivate);
        left.addView(btnTest);

        // Updates
        left.addView(space(20));
        LinearLayout updCard = card();
        TextView uh = text("Updates", 18, Color.WHITE);
        uh.setTypeface(Typeface.DEFAULT_BOLD);
        updCard.addView(uh);
        updCard.addView(space(6));
        tvUpdate = text("Version " + Updater.installedName(this), 16, MUTED);
        updCard.addView(tvUpdate);
        left.addView(updCard);
        btnUpdate = button("Nach Updates suchen", v -> onUpdateClicked());
        left.addView(btnUpdate);

        root.addView(space(32), new LinearLayout.LayoutParams(dp(32), 1));

        // ---- Rechte Spalte: Diagnose oben, Anleitung darunter ----
        LinearLayout rightCol = column();
        root.addView(rightCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // Diagnose-Anzeige
        diagCard = card();
        tvDiagTitle = text("", 16, ACCENT);
        tvDiagTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvDiag = text("", 14, TEXT);
        tvDiag.setTypeface(Typeface.MONOSPACE);
        diagCard.addView(tvDiagTitle);
        diagCard.addView(space(6));
        diagCard.addView(tvDiag);
        diagCard.addView(space(6));
        diagCard.addView(text("[Dienst] = kommt beim Dienst an → lässt sich umbelegen.\n"
                + "Erscheint bei TV+ gar nichts, fängt das System die Taste vorher ab.", 13, MUTED));

        rightCol.addView(diagCard);
        rightCol.addView(space(16));
        LinearLayout right = card();
        rightCol.addView(right);
        TextView h = text(RESTRICTED ? "Einrichtung für Google TV (einmalig)" : "Einrichtung (einmalig)", 20, Color.WHITE);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        right.addView(h);
        right.addView(space(8));
        String pkg = getPackageName();

        right.addView(text(RESTRICTED
                ? "Google TV sperrt die Bedienungshilfe für nachinstallierte Apps. Deshalb läuft die Einrichtung über ADB "
                + "(z. B. mit Termux auf dem Handy):\n"
                : "1. „Dienst aktivieren“ drücken und den TV-Tastenmapper einschalten.\n\n"
                + "2. Für die Selbstheilung nach Neustarts einmalig per ADB (z. B. Termux):\n", 15, TEXT));
        right.addView(mono("adb connect <IP-des-Sticks>\n"
                + "adb shell pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS\n"
                + "adb shell appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow"));
        right.addView(text("\nDanach diese App einmal öffnen – sie schaltet ihren Dienst selbst ein "
                + "und hält ihn nach jedem Neustart und Update aktiv.\n\n"
                + "Dann: „Tasten-Diagnose“ drücken und TV+ drücken. Steht dort [Dienst], klappt die Umbelegung.\n\n"
                + "Zum Schluss „TV+-Taste anlernen“ und TV+ drücken. Fertig – die App muss danach nicht mehr geöffnet werden.", 15, TEXT));

        btnLearn.post(btnLearn::requestFocus);
        return scroll;
    }

    private TextView mono(String s) {
        TextView t = text(s, 14, OK);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = column();
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        l.setBackground(g);
        l.setPadding(dp(20), dp(18), dp(20), dp(18));
        return l;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private View space(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(20), dp(12), dp(20), dp(12));
        b.setFocusable(true);
        b.setOnClickListener(l);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(CARD);
        normal.setCornerRadius(dp(12));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(ACCENT);
        focused.setCornerRadius(dp(12));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, normal);
        b.setBackground(sl);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
