package de.kerim.tvmapper;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Selbstheilung: schaltet den Dienst wieder ein, wenn Android ihn abgeschaltet hat.
 * Funktioniert, sobald einmalig per ADB WRITE_SECURE_SETTINGS erteilt wurde.
 */
final class Healer {
    private Healer() {}

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static ComponentName component(Context c) {
        return new ComponentName(c.getPackageName(), MapperService.class.getName());
    }

    private static boolean isOurs(Context c, String entry) {
        ComponentName parsed = ComponentName.unflattenFromString(entry.trim());
        return parsed != null && parsed.equals(component(c));
    }

    private static List<String> enabledList(Context c) {
        List<String> out = new ArrayList<>();
        String raw = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (!TextUtils.isEmpty(raw)) {
            for (String s : raw.split(":")) {
                if (!TextUtils.isEmpty(s.trim())) out.add(s.trim());
            }
        }
        return out;
    }

    /** Ist der Dienst in den Einstellungen eingeschaltet? */
    static boolean isEnabledInSettings(Context c) {
        try {
            for (String s : enabledList(c)) if (isOurs(c, s)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Läuft der Dienst wirklich (vom System gebunden)? */
    static boolean isRunning(Context c) {
        if (MapperService.running) return true;
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) return false;
            String ours = component(c).flattenToString();
            String oursShort = component(c).flattenToShortString();
            for (AccessibilityServiceInfo i : list) {
                String id = i.getId();
                if (ours.equals(id) || oursShort.equals(id)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void write(Context c, List<String> entries) {
        Settings.Secure.putString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", entries));
        Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    /**
     * Schaltet den Dienst ein, falls er fehlt.
     * @param allowRestart true = auch neu starten, wenn er eingeschaltet ist, aber nicht läuft
     *                     (nur aus einem Hintergrund-Thread aufrufen, wartet kurz).
     * @return true, wenn der Dienst danach eingeschaltet ist.
     */
    static synchronized boolean ensure(Context c, boolean allowRestart) {
        try {
            if (!hasPermission(c)) return isEnabledInSettings(c);

            List<String> list = enabledList(c);
            List<String> others = new ArrayList<>();
            boolean listed = false;
            for (String s : list) {
                if (isOurs(c, s)) listed = true;
                else others.add(s);
            }

            if (listed && (isRunning(c) || !allowRestart)) {
                return true;
            }

            Protokoll.add(c, listed ? "Dienst hing – wird neu gestartet" : "Dienst war aus – wird eingeschaltet");
            if (listed) {
                // Eingetragen, aber hängt: einmal aus- und wieder einschalten
                write(c, others);
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
            }

            List<String> withUs = new ArrayList<>(others);
            withUs.add(component(c).flattenToString());
            write(c, withUs);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
