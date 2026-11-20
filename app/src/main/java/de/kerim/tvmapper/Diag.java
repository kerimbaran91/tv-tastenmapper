package de.kerim.tvmapper;

import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** Tasten-Diagnose: merkt sich die letzten Tastendrücke zur Anzeige. */
final class Diag {
    private static final int MAX = 8;
    private static final List<String> lines = new ArrayList<>();

    private Diag() {}

    static void record(KeyEvent ev, String source) {
        String dev = "";
        try {
            InputDevice d = ev.getDevice();
            if (d != null) dev = d.getName();
        } catch (Throwable ignored) {
        }
        String line = KeyEvent.keyCodeToString(ev.getKeyCode())
                + "   Code " + ev.getKeyCode()
                + "   Scan " + ev.getScanCode()
                + "   [" + source + "]"
                + (dev.isEmpty() ? "" : "   " + dev);
        synchronized (lines) {
            lines.add(0, line);
            while (lines.size() > MAX) lines.remove(lines.size() - 1);
        }
    }

    static String text() {
        synchronized (lines) {
            if (lines.isEmpty()) return "Noch keine Taste gedrückt.";
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString().trim();
        }
    }

    static void clear() {
        synchronized (lines) {
            lines.clear();
        }
    }
}
