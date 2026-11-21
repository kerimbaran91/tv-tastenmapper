package de.kerim.tvmapper;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Echte Play/Pause-Taste: verwaltet das Hilfsprogramm (InjectServer).
 * - startet es bei Bedarf über ADB („Debugging über WLAN“, wird selbst eingeschaltet)
 * - hält eine Verbindung offen, damit der Tastendruck ohne Verzögerung ankommt
 * - sendet einen Druck nie doppelt
 */
final class RealKey {
    static final int PORT = 47811;
    static final int KEYCODE_MEDIA_PLAY_PAUSE = 85;

    private static final String PREFS = "realkey";
    private static final long REFRESH_INTERVAL_MS = 20L * 60 * 60 * 1000; // ADB-Freigabe ca. täglich auffrischen

    static volatile String status = "noch nicht geprüft";
    static volatile boolean active = false;

    private static final Object lock = new Object();
    private static Socket sock;
    private static BufferedReader in;
    private static OutputStream out;
    private static volatile long lastStartAttempt = 0;
    private static volatile long lastEnsureFail = 0;
    private static volatile String seenOtherVersion = null;

    private RealKey() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static final Object tokenLock = new Object(); // eigenes Schloss, verhindert Verklemmungen
    private static volatile String cachedToken;

    /** Geheimes Passwort für das Hilfsprogramm (einmal erzeugt, danach fest). */
    private static String token(Context c) {
        String t = cachedToken;
        if (t != null) return t;
        synchronized (tokenLock) {
            if (cachedToken != null) return cachedToken;
            SharedPreferences p = sp(c);
            t = p.getString("token", null);
            if (t == null) {
                byte[] b = new byte[16];
                new SecureRandom().nextBytes(b);
                StringBuilder sb = new StringBuilder();
                for (byte x : b) sb.append(String.format("%02x", x));
                t = sb.toString();
                p.edit().putString("token", t).commit();
            }
            cachedToken = t;
            return t;
        }
    }

    private static String version(Context c) {
        return String.valueOf(Updater.installedBuild(c));
    }

    private static void markAdbUsed(Context c) {
        sp(c).edit().putLong("last_adb", System.currentTimeMillis()).apply();
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
        if (r == null) throw new IOException("getrennt");
        return r;
    }

    /**
     * Sendet einen echten Tastendruck. Nur im Hintergrund aufrufen!
     * Wird nie doppelt gesendet: Wiederholt wird nur, wenn sicher ist, dass nichts angekommen ist
     * (Verbindung war schon tot). Bei einer langsamen Antwort gilt der Druck als gesendet.
     * @return true = gesendet (kein Rückfall nötig), false = sicher nicht gesendet
     */
    static boolean pressKey(Context c, int code) {
        synchronized (lock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (sock == null && connect(c) == null) break;
                try {
                    out.write(("KEY " + code + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Throwable t) {
                    closeQuietly(); // nichts gesendet → neu verbinden und nochmal
                    continue;
                }
                try {
                    String r = in.readLine();
                    if (r == null) {
                        // Alte Verbindung war tot (z. B. Hilfsprogramm neu gestartet) → kam nicht an
                        closeQuietly();
                        continue;
                    }
                    if ("OK".equals(r)) {
                        active = true;
                        return true;
                    }
                    // Hilfsprogramm konnte nicht auslösen und beendet sich → Rückfall + frisch starten
                    closeQuietly();
                    active = false;
                    lastStartAttempt = 0; // Neustart nicht durch die 30-s-Sperre aufhalten
                    Protokoll.add(c, "Tastendruck: Hilfsprogramm war taub – Kopfhörer-Taste genutzt, starte neu");
                    return false;
                } catch (SocketTimeoutException slow) {
                    // Antwort zu langsam: Druck ist sehr wahrscheinlich angekommen → NICHT nochmal senden
                    closeQuietly();
                    Protokoll.add(c, "Tastendruck: Antwort langsam (nicht wiederholt)");
                    return true;
                } catch (Throwable t) {
                    // Verbindung zurückgesetzt → kam nicht an
                    closeQuietly();
                }
            }
            active = false;
            Protokoll.add(c, "Tastendruck: Hilfsprogramm nicht erreichbar – Kopfhörer-Taste genutzt");
            return false;
        }
    }

    /** Lebt das Hilfsprogramm (in der aktuellen Version)? Für die Minuten-Prüfung. Nur im Hintergrund! */
    static boolean ping(Context c) {
        synchronized (lock) {
            String ver = version(c);
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (sock == null) {
                        String v = connect(c);
                        if (v == null) break;
                        if (!v.equals(ver)) {
                            seenOtherVersion = v;
                            break;
                        }
                    }
                    String r = request("PING");
                    if (r.equals("PONG " + ver)) {
                        active = true;
                        return true;
                    }
                    break;
                } catch (Throwable t) {
                    closeQuietly();
                }
            }
            closeQuietly();
            active = false;
            return false;
        }
    }

    // ---------------- ADB vorbereiten ----------------

    /**
     * Schaltet alles ein, was ADB braucht, und sucht den Port von „Debugging über WLAN“.
     * - Entwickleroptionen und USB-Debugging an (falls ein Update sie abgeschaltet hat)
     * - Verfall der ADB-Freigabe nach 7 Tagen abschalten
     * @return {host, port} oder null
     */
    private static Object[] prepareAdb(Context c) throws InterruptedException {
        ContentResolver cr = c.getContentResolver();
        try {
            if (Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 1) {
                Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
                Protokoll.add(c, "Entwickleroptionen waren aus – eingeschaltet");
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
                Protokoll.add(c, "USB-Debugging war aus – eingeschaltet");
            }
        } catch (Throwable ignored) {
        }
        try {
            // 0 = ADB-Freigaben verfallen nie (Entwickleroption „ADB-Autorisierungszeitlimit deaktivieren“)
            if (Settings.Global.getLong(cr, "adb_allowed_connection_time", -1) != 0) {
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0);
                Protokoll.add(c, "ADB-Freigabe auf „verfällt nie“ gestellt");
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1) {
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1);
                Protokoll.add(c, "Debugging über WLAN war aus – eingeschaltet");
                Thread.sleep(3000);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable ignored) {
        }

        Object[] target = null;
        long until = System.currentTimeMillis() + 30_000;
        while (target == null && System.currentTimeMillis() < until) {
            target = AdbClient.findTlsPort(c, 5000);
            if (target == null) Thread.sleep(2000);
        }
        Protokoll.add(c, AdbClient.lastNote);
        return target;
    }

    /** Führt einen Befehl per ADB aus (erst gefundene Adresse, dann 127.0.0.1). */
    private static String adbShell(AdbKey key, Object[] target, String cmd) throws Exception {
        String host = (String) target[0];
        int port = (Integer) target[1];
        try {
            return AdbClient.shell(key, host, port, cmd);
        } catch (Throwable first) {
            if (!"127.0.0.1".equals(host)) return AdbClient.shell(key, "127.0.0.1", port, cmd);
            throw first;
        }
    }

    // ---------------- Schlüssel übernehmen (mit Prüfung) ----------------

    /**
     * Übernimmt einen Schlüssel nur, wenn er auf diesem Gerät wirklich gekoppelt ist
     * (Test-Verbindung). So kann keine andere App einen falschen Schlüssel unterschieben.
     * Nur im Hintergrund! @return null bei Erfolg, sonst Fehlermeldung
     */
    static String importVerified(Context ctx, String pem) {
        Context c = ctx.getApplicationContext();
        try {
            AdbKey key = AdbKey.parsePem(pem);
            if (!Healer.hasPermission(c)) return "Selbstheilungs-Berechtigung fehlt (zuerst pm grant ausführen)";
            Object[] target = prepareAdb(c);
            if (target == null) return "Debugging über WLAN nicht erreichbar – einschalten und erneut senden";
            String r = adbShell(key, target, "echo TVMAPPER_OK");
            if (r == null || !r.contains("TVMAPPER_OK")) return "Schlüssel ist auf diesem Gerät nicht gekoppelt";
            key.save(c);
            markAdbUsed(c);
            Protokoll.add(c, "ADB-Schlüssel geprüft und übernommen");
            return null;
        } catch (Throwable t) {
            String m = t.getMessage();
            String err = "Schlüssel abgelehnt: " + (m != null ? m : t.getClass().getSimpleName());
            Protokoll.add(c, err);
            return err;
        }
    }

    // ---------------- Starten / Selbstheilung ----------------

    /**
     * Stellt sicher, dass das Hilfsprogramm läuft (startet es bei Bedarf über ADB).
     * Blockiert bis zu ~60 s – nur im Hintergrund aufrufen!
     */
    static boolean ensure(Context ctx) {
        Context c = ctx.getApplicationContext();
        appCtx = c;
        synchronized (RealKey.class) {
            try {
                // Läuft schon in der richtigen Version?
                seenOtherVersion = null;
                if (ping(c)) {
                    status = "aktiv";
                    return true;
                }
                active = false;
                if (seenOtherVersion != null) {
                    Protokoll.add(c, "Altes Hilfsprogramm (Version " + seenOtherVersion + ") läuft noch – wird ersetzt");
                } else {
                    Protokoll.add(c, "Hilfsprogramm antwortet nicht");
                }

                if (!AdbKey.exists(c)) {
                    status = "nicht eingerichtet – Termux-Befehl nötig (siehe rechts)";
                    return fail();
                }
                if (!Healer.hasPermission(c)) {
                    status = "Selbstheilungs-Berechtigung fehlt";
                    return fail();
                }
                lastStartAttempt = System.currentTimeMillis();
                status = "wird gestartet …";

                Object[] target = prepareAdb(c);
                if (target == null) {
                    status = "Debugging über WLAN nicht erreichbar (WLAN verbunden?)";
                    return fail();
                }

                AdbKey key = AdbKey.load(c);
                String pkg = c.getPackageName();
                String cls = InjectServer.class.getName();          // de.kerim.tvmapper.InjectServer
                String clsHead = cls.substring(0, cls.length() - 6); // de.kerim.tvmapper.Inject
                String pattern = clsHead + "[S]erver";               // passt nicht auf die eigene Shell
                // 1) alte/fremde Hilfsprogramme beenden  2) neu starten (bewährter Befehl)
                // 3) 2 s warten, damit es sich von der Sitzung löst  4) Port prüfen
                String cmd = "echo VORHER=$(pgrep -f '" + pattern + "' | wc -l); "
                        + "pkill -f '" + pattern + "' 2>/dev/null; "
                        // warten, bis das alte wirklich weg ist (max. 3 s), sonst hart beenden
                        + "i=0; while [ $(pgrep -f '" + pattern + "' | wc -l) -gt 0 ] && [ $i -lt 6 ]; do sleep 0.5; i=$((i+1)); done; "
                        + "if [ $(pgrep -f '" + pattern + "' | wc -l) -gt 0 ]; then pkill -9 -f '" + pattern + "' 2>/dev/null; sleep 0.3; echo HART_BEENDET; fi; "
                        + "echo NACHHER=$(pgrep -f '" + pattern + "' | wc -l); "
                        + "C=" + clsHead + "; "
                        + "CLASSPATH=$(pm path " + pkg + " | head -1 | cut -d: -f2) setsid app_process /system/bin "
                        + "${C}Server " + token(c) + " " + PORT + " " + version(c)
                        + " </dev/null >/dev/null 2>&1 & sleep 2; "
                        + "if grep -qi ':" + String.format("%04X", PORT) + " ' /proc/net/tcp /proc/net/tcp6; "
                        + "then echo PORT_OFFEN; else echo PORT_ZU; fi";
                String output = adbShell(key, target, cmd);
                markAdbUsed(c);
                output = output == null ? "" : output.trim();
                Protokoll.add(c, "Startbefehl: " + output.replace('\n', ' '));

                // Warten, bis es antwortet
                for (int i = 0; i < 50; i++) {
                    if (ping(c)) {
                        status = "aktiv";
                        lastEnsureFail = 0;
                        Protokoll.add(c, "Hilfsprogramm aktiv (Version " + version(c) + ")");
                        return true;
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
                return fail();
            } catch (Throwable t) {
                active = false;
                String msg = t.getMessage();
                status = "Fehler: " + (msg != null ? msg : t.getClass().getSimpleName());
                return fail();
            }
        }
    }

    private static boolean fail() {
        lastEnsureFail = System.currentTimeMillis();
        try {
            if (appCtx != null) Protokoll.add(appCtx, "Echte Taste: " + status);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static volatile Context appCtx;

    /**
     * Frischt die ADB-Freigabe ca. einmal täglich auf (kurze Test-Verbindung),
     * damit sie auch bei langer Laufzeit ohne Neustart nie verfällt. Nur im Hintergrund!
     */
    static void refreshAdbIfDue(Context ctx) {
        Context c = ctx.getApplicationContext();
        try {
            if (!AdbKey.exists(c) || !Healer.hasPermission(c)) return;
            long last = sp(c).getLong("last_adb", 0);
            if (System.currentTimeMillis() - last < REFRESH_INTERVAL_MS) return;
            synchronized (RealKey.class) {
                Object[] target = prepareAdb(c);
                if (target == null) return;
                String r = adbShell(AdbKey.load(c), target, "echo TVMAPPER_OK");
                if (r != null && r.contains("TVMAPPER_OK")) {
                    markAdbUsed(c);
                    Protokoll.add(c, "ADB-Freigabe aufgefrischt");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- Hintergrund-Aufrufe ----------------

    private static final AtomicBoolean busy = new AtomicBoolean(false);

    /** Prüfen/Starten im Hintergrund (ohne Zeitsperre), nie doppelt gleichzeitig. */
    static void checkAsync(Context c, boolean restart) {
        if (!busy.compareAndSet(false, true)) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (restart) {
                    synchronized (lock) {
                        closeQuietly();
                    }
                    active = false;
                    // Neustart erzwingen: ensure() beendet alte Hilfsprogramme per pkill
                    forceStart(app);
                } else {
                    ensure(app);
                }
            } finally {
                busy.set(false);
            }
        }).start();
    }

    /** Wie ensure(), startet aber auch neu, wenn das alte Hilfsprogramm noch antwortet. */
    private static void forceStart(Context app) {
        Protokoll.add(app, "Neustart der echten Taste per Knopf");
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
        ensure(app);
    }

    /** Nach einem fehlgeschlagenen Tastendruck: im Hintergrund neu starten, höchstens alle 30 s. */
    static void ensureAsync(Context c) {
        if (System.currentTimeMillis() - lastStartAttempt < 30_000) return;
        lastStartAttempt = System.currentTimeMillis();
        final Context app = c.getApplicationContext();
        new Thread(() -> ensure(app)).start();
    }

    /**
     * Minuten-Prüfung (vom Dienst): Lebt das Hilfsprogramm? Wenn nicht, sofort neu starten.
     * Nach einem Fehlschlag (z. B. kein WLAN) erst nach 5 Minuten wieder versuchen. Nur im Hintergrund!
     */
    static void keepAlive(Context c) {
        if (ping(c)) return;
        Protokoll.add(c, "Minuten-Prüfung: Hilfsprogramm antwortet nicht");
        if (lastEnsureFail != 0 && System.currentTimeMillis() - lastEnsureFail < 5 * 60_000) return;
        if (busy.get()) return;
        ensure(c);
    }
}
