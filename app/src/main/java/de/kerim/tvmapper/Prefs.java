package de.kerim.tvmapper;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/**
 * Einstellungen mit Zwischenspeicher im Arbeitsspeicher.
 * Der Dienst liest bei jedem Tastendruck nur diese Felder – kein Datei-Zugriff, keine Verzögerung.
 */
final class Prefs {
    static final String ACTION_LEARNED = "de.kerim.tvmapper.LEARNED";
    static final String ACTION_DIAG = "de.kerim.tvmapper.DIAG";

    private static final String FILE = "mapper";
    private static final String K_CODE = "key_code";
    private static final String K_SCAN = "scan_code";
    private static final String K_ENABLED = "enabled";

    static final long LEARN_WINDOW_MS = 20_000;
    static final long DIAG_WINDOW_MS = 60_000;

    // ---- Zwischenspeicher (vom Dienst bei jedem Tastendruck gelesen) ----
    static volatile boolean loaded = false;
    static volatile int code = -1;
    static volatile int scan = 0;
    static volatile boolean enabled = true;
    static volatile long learnUntil = 0;   // nur im Speicher
    static volatile long diagUntil = 0;    // nur im Speicher

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Einmal laden (beim Start von Dienst oder App). */
    static void load(Context c) {
        if (loaded) return;
        synchronized (Prefs.class) {
            if (loaded) return;
            SharedPreferences p = sp(c);
            code = p.getInt(K_CODE, -1);
            scan = p.getInt(K_SCAN, 0);
            enabled = p.getBoolean(K_ENABLED, true);
            loaded = true;
        }
    }

    static boolean isEnabled(Context c) {
        load(c);
        return enabled;
    }

    static void setEnabled(Context c, boolean on) {
        load(c);
        enabled = on;
        sp(c).edit().putBoolean(K_ENABLED, on).apply();
    }

    static boolean hasLearned(Context c) {
        load(c);
        return code >= 0;
    }

    static void saveLearned(Context c, int keyCode, int scanCode) {
        load(c);
        code = keyCode;
        scan = scanCode;
        learnUntil = 0;
        sp(c).edit().putInt(K_CODE, keyCode).putInt(K_SCAN, scanCode).apply();
    }

    static void startLearning() {
        diagUntil = 0;
        learnUntil = System.currentTimeMillis() + LEARN_WINDOW_MS;
    }

    static void stopLearning() {
        learnUntil = 0;
    }

    static long learningRemainingMs() {
        long left = learnUntil - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    static boolean isLearning() {
        return learnUntil != 0 && learningRemainingMs() > 0;
    }

    static void startDiag() {
        learnUntil = 0;
        diagUntil = System.currentTimeMillis() + DIAG_WINDOW_MS;
    }

    static void stopDiag() {
        diagUntil = 0;
    }

    static long diagRemainingMs() {
        long left = diagUntil - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    static boolean isDiag() {
        return diagUntil != 0 && diagRemainingMs() > 0;
    }

    /** Tasten, die beim Anlernen ignoriert werden (Navigation, Zurück, Lautstärke). */
    static boolean ignoredForLearning(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER:
                return true;
            default:
                return false;
        }
    }

    /** Passt das Tasten-Ereignis zur angelernten Taste? (nur Speicherzugriff) */
    static boolean matches(int keyCode, int scanCode) {
        int learned = code;
        if (learned < 0) return false;
        if (learned != KeyEvent.KEYCODE_UNKNOWN) return keyCode == learned;
        // Manche Sondertasten senden keinen Android-Code, nur einen Scancode
        int s = scan;
        return s != 0 && keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode == s;
    }

    static String describeLearned(Context c) {
        load(c);
        if (code < 0) return "noch nicht angelernt";
        if (code == KeyEvent.KEYCODE_UNKNOWN) return "angelernt (Scancode " + scan + ")";
        return "angelernt (" + KeyEvent.keyCodeToString(code) + ", Code " + code + ")";
    }
}
