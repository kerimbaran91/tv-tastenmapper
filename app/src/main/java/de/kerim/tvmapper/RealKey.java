package de.kerim.tvmapper;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Echte Play/Pause-Taste: verwaltet das Hilfsprogramm (InjectServer).
 * - startet es bei Bedarf über ADB („Debugging über WLAN“, wird selbst eingeschaltet)
 * - hält eine Verbindung offen, damit der Tastendruck ohne Verzögerung ankommt
 */
final class RealKey {
    static final int PORT = 47811;
    static final int KEYCODE_MEDIA_PLAY_PAUSE = 85;

    static volatile String status = "noch nicht geprüft";
    static volatile boolean active = false;

    private static final Object lock = new Object();
    private static Socket sock;
    private static BufferedReader in;
    private static OutputStream out;
    private static volatile long lastStartAttempt = 0;

    private RealKey() {}

    private static String token(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("realkey", Context.MODE_PRIVATE);
        String t = p.getString("token", null);
        if (t == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            t = sb.toString();
            p.edit().putString("token", t).commit();
        }
        return t;
    }

    private static String version(Context c) {
        return String.valueOf(Updater.installedBuild(c));
    }

    // ---------------- Verbindung zum Hilfsprogramm ----------------

    private static void closeQuietly() {
        try {
            if (sock != null) sock.close();
        } catch (Throwable ignored) {
        }
        sock = null;
        in = null;
        out = null;
    }

    /** Verbindet sich mit dem Hilfsprogramm. Liefert dessen Version oder null. Nur im Hintergrund! */
    private static String connect(Context c) {
        closeQuietly();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
            s.setSoTimeout(1500);
            s.setTcpNoDelay(true);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream o = s.getOutputStream();
            o.write(("AUTH " + token(c) + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            String hello = r.readLine();
            if (hello == null || !hello.startsWith("HELLO ")) {
                s.close();
                return null;
            }
            sock = s;
            in = r;
            out = o;
            return hello.substring(6).trim();
        } catch (Throwable t) {
            closeQuietly();
            return null;
        }
    }

    private static String request(String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        String r = in.readLine();
        if (r == null) throw new Exception("getrennt");
        return r;
    }

    /** Sendet einen echten Tastendruck. Nur im Hintergrund aufrufen! */
    static boolean pressKey(Context c, int code) {
        synchronized (lock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (sock == null && connect(c) == null) break;
                    if ("OK".equals(request("KEY " + code))) {
                        active = true;
                        return true;
                    }
                } catch (Throwable t) {
                    closeQuietly();
                }
            }
            active = false;
            return false;
        }
    }

    /** Läuft das Hilfsprogramm in der aktuellen Version? */
    static boolean isRunning(Context c) {
        synchronized (lock) {
            String v = connect(c);
            boolean ok = v != null && v.equals(version(c));
            active = ok;
            return ok;
        }
    }

    // ---------------- Starten / Selbstheilung ----------------

    /**
     * Stellt sicher, dass das Hilfsprogramm läuft (startet es bei Bedarf über ADB).
     * Blockiert bis zu ~60 s – nur im Hintergrund aufrufen!
     */
    static boolean ensure(Context ctx) {
        Context c = ctx.getApplicationContext();
        synchronized (RealKey.class) {
            try {
                // Läuft schon?
                synchronized (lock) {
                    String v = connect(c);
                    if (v != null) {
                        if (v.equals(version(c))) {
                            active = true;
                            status = "aktiv";
                            return true;
                        }
                        // Alte Version (nach App-Update): beenden und neu starten
                        try {
                            request("QUIT");
                        } catch (Throwable ignored) {
                        }
                        closeQuietly();
                        Thread.sleep(500);
                    }
                }
                active = false;

                if (!AdbKey.exists(c)) {
                    status = "nicht eingerichtet – Termux-Befehl nötig (siehe rechts)";
                    return false;
                }
                if (!Healer.hasPermission(c)) {
                    status = "Selbstheilungs-Berechtigung fehlt";
                    return false;
                }
                lastStartAttempt = System.currentTimeMillis();
                status = "wird gestartet …";

                // „Debugging über WLAN“ einschalten
                ContentResolver cr = c.getContentResolver();
                try {
                    if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 1) {
                        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
                    }
                    if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1) {
                        Settings.Global.putInt(cr, "adb_wifi_enabled", 1);
                        Thread.sleep(3000);
                    }
                } catch (Throwable ignored) {
                }

                // Port suchen (bis zu ~30 s, WLAN braucht nach dem Start evtl. etwas)
                Object[] target = null;
                long until = System.currentTimeMillis() + 30_000;
                while (target == null && System.currentTimeMillis() < until) {
                    target = AdbClient.findTlsPort(c, 5000);
                    if (target == null) Thread.sleep(2000);
                }
                if (target == null) {
                    status = "Debugging über WLAN nicht erreichbar (WLAN verbunden?)";
                    return false;
                }

                AdbKey key = AdbKey.load(c);
                String pkg = c.getPackageName();
                // Genau der Befehl, der in Termux funktioniert hat – plus 2 s warten und Port prüfen,
                // damit sich das Hilfsprogramm sauber von der ADB-Sitzung löst.
                String cmd = "CLASSPATH=$(pm path " + pkg + " | head -1 | cut -d: -f2) setsid app_process /system/bin "
                        + InjectServer.class.getName() + " " + token(c) + " " + PORT + " " + version(c)
                        + " </dev/null >/dev/null 2>&1 & sleep 2; "
                        + "if grep -qi ':" + String.format("%04X", PORT) + " ' /proc/net/tcp /proc/net/tcp6; "
                        + "then echo PORT_OFFEN; else echo PORT_ZU; fi";
                String host = (String) target[0];
                int port = (Integer) target[1];
                String output;
                try {
                    output = AdbClient.shell(key, host, port, cmd);
                } catch (Throwable first) {
                    if (!"127.0.0.1".equals(host)) {
                        output = AdbClient.shell(key, "127.0.0.1", port, cmd);
                    } else {
                        throw first;
                    }
                }
                output = output == null ? "" : output.trim();

                // Warten, bis es antwortet
                for (int i = 0; i < 20; i++) {
                    synchronized (lock) {
                        String v = connect(c);
                        if (v != null && v.equals(version(c))) {
                            active = true;
                            status = "aktiv";
                            return true;
                        }
                    }
                    Thread.sleep(300);
                }
                if (output.contains("PORT_OFFEN")) {
                    status = "Hilfsprogramm läuft, aber die App kann sich nicht verbinden";
                } else if (output.contains("PORT_ZU")) {
                    status = "Hilfsprogramm startet nicht (Port bleibt zu)";
                } else if (output.isEmpty()) {
                    status = "ADB-Befehl ohne Antwort";
                } else {
                    status = "Hilfsprogramm startet nicht: " + (output.length() > 120 ? output.substring(0, 120) : output);
                }
                return false;
            } catch (Throwable t) {
                active = false;
                String msg = t.getMessage();
                status = "Fehler: " + (msg != null ? msg : t.getClass().getSimpleName());
                return false;
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean busy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Prüfen/Starten im Hintergrund (ohne Zeitsperre), nie doppelt gleichzeitig. */
    static void checkAsync(Context c, boolean restart) {
        if (!busy.compareAndSet(false, true)) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (restart) {
                    synchronized (lock) {
                        if (connect(app) != null) {
                            try {
                                request("QUIT");
                            } catch (Throwable ignored) {
                            }
                        }
                        closeQuietly();
                    }
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException ignored) {
                    }
                }
                ensure(app);
            } finally {
                busy.set(false);
            }
        }).start();
    }

    /** Im Hintergrund neu starten, höchstens alle 30 s. */
    static void ensureAsync(Context c) {
        if (System.currentTimeMillis() - lastStartAttempt < 30_000) return;
        lastStartAttempt = System.currentTimeMillis();
        final Context app = c.getApplicationContext();
        new Thread(() -> ensure(app)).start();
    }
}
