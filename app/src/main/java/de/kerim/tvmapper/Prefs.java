package de.kerim.tvmapper;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/** Gespeicherte Einstellungen: angelernte Taste, an/aus, Anlern-Modus. */
final class Prefs {
    static final String ACTION_LEARNED = "de.kerim.tvmapper.LEARNED";

    private static final String FILE = "mapper";
    private static final String K_CODE = "key_code";
    private static final String K_SCAN = "scan_code";
    private static final String K_ENABLED = "enabled";
    private static final String K_LEARN_UNTIL = "learn_until";

    static final long LEARN_WINDOW_MS = 20_000;

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return get(c).getBoolean(K_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        get(c).edit().putBoolean(K_ENABLED, on).commit();
    }

    static int learnedCode(Context c) {
        return get(c).getInt(K_CODE, -1);
    }

    static int learnedScan(Context c) {
        return get(c).getInt(K_SCAN, 0);
    }

    static boolean hasLearned(Context c) {
        return learnedCode(c) >= 0;
    }

    static void saveLearned(Context c, int keyCode, int scanCode) {
        get(c).edit()
                .putInt(K_CODE, keyCode)
                .putInt(K_SCAN, scanCode)
                .putLong(K_LEARN_UNTIL, 0)
                .commit();
    }

    static void startLearning(Context c) {
        get(c).edit().putLong(K_LEARN_UNTIL, System.currentTimeMillis() + LEARN_WINDOW_MS).commit();
    }

    static void stopLearning(Context c) {
        get(c).edit().putLong(K_LEARN_UNTIL, 0).commit();
    }

    static long learningRemainingMs(Context c) {
        long left = get(c).getLong(K_LEARN_UNTIL, 0) - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    static boolean isLearning(Context c) {
        return learningRemainingMs(c) > 0;
    }

    /** Tasten, die beim Anlernen ignoriert werden (Navigation, Zurück, Lautstärke). */
    static boolean ignoredForLearning(int code) {
        switch (code) {
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

    /** Passt das Tasten-Ereignis zur angelernten Taste? */
    static boolean matches(Context c, int keyCode, int scanCode) {
        int learned = learnedCode(c);
        if (learned < 0) return false;
        if (learned != KeyEvent.KEYCODE_UNKNOWN) return keyCode == learned;
        // Manche Sondertasten senden keinen Android-Code, nur einen Scancode
        int scan = learnedScan(c);
        return scan != 0 && keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode == scan;
    }

    static String describeLearned(Context c) {
        int code = learnedCode(c);
        if (code < 0) return "noch nicht angelernt";
        if (code == KeyEvent.KEYCODE_UNKNOWN) return "angelernt (Scancode " + learnedScan(c) + ")";
        return "angelernt (" + KeyEvent.keyCodeToString(code) + ")";
    }
}
