package de.kerim.tvmapper;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Kleines Protokoll: immer nur die letzten 20 Einträge (ca. 1 KB), bleibt auch nach Neustarts erhalten.
 * Wird in der App angezeigt, damit man bei Problemen den Ablauf sieht.
 */
final class Protokoll {
    private static final int MAX = 20;
    private static final String PREFS = "protokoll";
    private static final String KEY = "eintraege";
    private static final List<String> lines = new ArrayList<>();
    private static boolean loaded = false;

    private Protokoll() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void loadLocked(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            String all = sp(c).getString(KEY, "");
            if (!all.isEmpty()) {
                for (String l : all.split("\n")) {
                    if (!l.isEmpty() && lines.size() < MAX) lines.add(l);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Neuen Eintrag hinzufügen (neueste oben). Darf von jedem Thread aufgerufen werden. */
    static void add(Context c, String msg) {
        try {
            String time = new SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(new Date());
            String line = time + "  " + msg.replace('\n', ' ');
            if (line.length() > 160) line = line.substring(0, 160);
            synchronized (lines) {
                loadLocked(c);
                // Gleiche Meldung nicht wiederholen, wenn sie unter den letzten 4 schon steht
                // (z. B. bei Wiederholungen nach dem Start) – so bleiben wichtige Einträge sichtbar
                String tail = "  " + msg.replace('\n', ' ');
                for (int i = 0; i < Math.min(4, lines.size()); i++) {
                    if (lines.get(i).endsWith(tail)) return;
                }
                lines.add(0, line);
                while (lines.size() > MAX) lines.remove(lines.size() - 1);
                StringBuilder sb = new StringBuilder();
                for (String l : lines) sb.append(l).append('\n');
                sp(c).edit().putString(KEY, sb.toString()).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    static String text(Context c) {
        synchronized (lines) {
            loadLocked(c);
            if (lines.isEmpty()) return "Noch keine Einträge.";
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString().trim();
        }
    }
}
