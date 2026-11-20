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

    private TextView tvService, tvHeal, tvKey, tvLearn, tvUpdate;
    private Button btnLearn, btnToggle, btnUpdate;
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

    private final BroadcastReceiver learnedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onLearned();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        // Beim Öffnen gleich alles sicherstellen
        Healer.ensure(this, false);
        Watchdog.schedule(this);
        checkForUpdate(false);
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
        setUpdateText("Suche nach Updates …", 0xFFAAB4C0);
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
                        : "Version " + Updater.installedName(this) + " (kein Internet / GitHub nicht erreichbar)",
                        manual ? BAD : 0xFFAAB4C0);
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

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Prefs.ACTION_LEARNED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(learnedReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(learnedReceiver, f);
        }
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        try {
            unregisterReceiver(learnedReceiver);
        } catch (Throwable ignored) {
        }
    }

    /** Fallback: Anlernen auch ohne laufenden Dienst, wenn die Taste hier ankommt. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (Prefs.isLearning(this) && !Prefs.ignoredForLearning(ev.getKeyCode())) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
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
        if (running) {
            setStatus(tvService, "Dienst: aktiv", OK);
        } else if (listed) {
            setStatus(tvService, "Dienst: eingeschaltet, startet gerade …", WARN);
        } else {
            setStatus(tvService, "Dienst: AUS – bitte aktivieren", BAD);
        }

        if (Healer.hasPermission(this)) {
            setStatus(tvHeal, "Selbstheilung: eingerichtet (läuft nach Neustart automatisch)", OK);
        } else {
            setStatus(tvHeal, "Selbstheilung: fehlt – einmalig ADB-Befehl nötig (siehe unten)", WARN);
        }

        setStatus(tvKey, "TV+-Taste: " + Prefs.describeLearned(this),
                Prefs.hasLearned(this) ? OK : WARN);

        long left = Prefs.learningRemainingMs(this);
        if (left > 0) {
            tvLearn.setVisibility(View.VISIBLE);
            tvLearn.setText("Jetzt die Xiaomi-TV+-Taste drücken … (" + ((left + 999) / 1000) + " s)");
            btnLearn.setText("Anlernen abbrechen");
        } else {
            tvLearn.setVisibility(View.GONE);
            btnLearn.setText("TV+-Taste anlernen");
        }

        btnToggle.setText(Prefs.isEnabled(this) ? "Umbelegung: AN" : "Umbelegung: AUS");
    }

    private void setStatus(TextView tv, String text, int color) {
        tv.setText("●  " + text);
        tv.setTextColor(color);
    }

    // ---------- Aktionen ----------

    private void onLearnClicked() {
        if (Prefs.isLearning(this)) {
            Prefs.stopLearning(this);
        } else {
            if (!Healer.isRunning(this)) {
                Toast.makeText(this, "Hinweis: Dienst läuft nicht – Anlernen klappt dann evtl. nicht.",
                        Toast.LENGTH_LONG).show();
            }
            Prefs.startLearning(this);
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
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, "„TV-Tastenmapper“ auswählen und einschalten", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Einstellungen gesperrt – bitte ADB-Befehl verwenden (siehe unten)",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onTestClicked() {
        try {
            MapperService.sendPlayPause(this);
            Toast.makeText(this, "Play/Pause gesendet", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Fehler beim Senden", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Oberfläche ----------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(48), dp(32), dp(48), dp(32));
        scroll.addView(root);

        // Linke Spalte: Status + Knöpfe
        LinearLayout left = column();
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = text("TV-Tastenmapper", 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);
        left.addView(text("Xiaomi-TV+-Taste  →  Play/Pause", 18, 0xFFAAB4C0));
        left.addView(space(20));

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
        btnToggle = button("Umbelegung: AN", v -> onToggleClicked());
        Button btnActivate = button("Dienst aktivieren", v -> onActivateClicked());
        Button btnTest = button("Play/Pause testen", v -> onTestClicked());
        left.addView(btnLearn);
        left.addView(btnToggle);
        left.addView(btnActivate);
        left.addView(btnTest);

        left.addView(space(20));
        LinearLayout updCard = card();
        TextView uh = text("Updates", 18, Color.WHITE);
        uh.setTypeface(Typeface.DEFAULT_BOLD);
        updCard.addView(uh);
        updCard.addView(space(6));
        tvUpdate = text("Version " + Updater.installedName(this), 16, 0xFFAAB4C0);
        updCard.addView(tvUpdate);
        left.addView(updCard);
        btnUpdate = button("Nach Updates suchen", v -> onUpdateClicked());
        left.addView(btnUpdate);

        root.addView(space(32), new LinearLayout.LayoutParams(dp(32), 1));

        // Rechte Spalte: Anleitung
        LinearLayout right = card();
        root.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView h = text("Einrichtung (einmalig)", 20, Color.WHITE);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        right.addView(h);
        right.addView(space(8));
        String pkg = getPackageName();
        right.addView(text(
                "1. „Dienst aktivieren“ drücken und den TV-Tastenmapper einschalten.\n\n"
                        + "2. Für die Selbstheilung nach Neustarts einmalig per ADB (z. B. Termux):\n", 15, 0xFFD0D7DE));
        TextView cmd = text(
                "adb connect <IP-des-Sticks>\n"
                        + "adb shell pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS", 14, OK);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setTextIsSelectable(false);
        right.addView(cmd);
        right.addView(text(
                "\nDanach schaltet sich die App selbst ein – auch wenn die Bedienungshilfe-Einstellungen gesperrt sind.\n\n"
                        + "3. „TV+-Taste anlernen“ drücken, dann die TV+-Taste auf der Fernbedienung.\n\n"
                        + "Fertig. Die App muss danach nicht mehr geöffnet werden.\n\n"
                        + "Updates: Falls das Installieren nicht erlaubt ist, einmalig per ADB:\n", 15, 0xFFD0D7DE));
        TextView cmd2 = text("adb shell appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow", 14, OK);
        cmd2.setTypeface(Typeface.MONOSPACE);
        right.addView(cmd2);

        btnLearn.post(btnLearn::requestFocus);
        return scroll;
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
