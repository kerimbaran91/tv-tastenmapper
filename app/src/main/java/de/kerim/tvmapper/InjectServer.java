package de.kerim.tvmapper;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Hilfsprogramm, das mit Shell-Rechten läuft (gestartet per ADB über app_process).
 * Es löst echte Tastendrücke aus – genau wie eine echte Fernbedienungstaste.
 * Lauscht nur auf 127.0.0.1 und akzeptiert nur Verbindungen mit dem richtigen Token.
 *
 * Aufruf: CLASSPATH=<apk> app_process /system/bin de.kerim.tvmapper.InjectServer <token> <port> <version>
 */
public final class InjectServer {

    private static Object inputManager;
    private static Method injectMethod;

    public static void main(String[] args) {
        try {
            if (args.length < 3) return;
            final String token = args[0];
            int port = Integer.parseInt(args[1]);
            final String version = args[2];

            try {
                Looper.prepareMainLooper();
            } catch (Throwable ignored) {
            }
            initInput();

            // Port sofort wieder belegen dürfen, auch wenn das alte Hilfsprogramm ihn gerade erst freigegeben hat
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
            while (true) {
                final Socket s = server.accept();
                Thread t = new Thread(() -> handle(s, token, version));
                t.setDaemon(true);
                t.start();
            }
        } catch (Throwable t) {
            // Port belegt (läuft schon) oder anderer Fehler: beenden
            System.exit(1);
        }
    }

    private static void initInput() throws Exception {
        try {
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal"); // Android 14+
            inputManager = g.getMethod("getInstance").invoke(null);
        } catch (Throwable t) {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            Method gi = im.getDeclaredMethod("getInstance");
            gi.setAccessible(true);
            inputManager = gi.invoke(null);
        }
        injectMethod = inputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    private static boolean inject(KeyEvent ev) {
        try {
            Object r = injectMethod.invoke(inputManager, ev, 0); // 0 = asynchron
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ist die Verbindung zum Eingabe-System noch lebendig? Nach einem internen Neustart von Android
     * wäre sie tot – dann beendet sich das Hilfsprogramm, und die App startet ein frisches.
     */
    static boolean inputAlive() {
        Method m;
        try {
            m = inputManager.getClass().getMethod("getInputDeviceIds");
        } catch (Throwable t) {
            return true; // Prüfung auf diesem Gerät nicht möglich → als lebendig werten (nie grundlos beenden)
        }
        try {
            m.invoke(inputManager);
            return true;
        } catch (java.lang.reflect.InvocationTargetException dead) {
            return false; // Aufruf ist im System fehlgeschlagen → Verbindung tot
        } catch (Throwable other) {
            return true;
        }
    }

    static boolean pressKey(int code) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        boolean a = inject(down);
        boolean b = inject(up);
        return a && b;
    }

    private static void handle(Socket s, String token, String version) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = sock.getOutputStream();
            String first = in.readLine();
            if (first == null || !first.equals("AUTH " + token)) return;
            out.write(("HELLO " + version + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                String reply;
                boolean exitAfter = false;
                if (line.equals("PING")) {
                    if (inputAlive()) {
                        reply = "PONG " + version;
                    } else {
                        reply = "DEAD";
                        exitAfter = true;
                    }
                } else if (line.startsWith("KEY ")) {
                    int code = Integer.parseInt(line.substring(4).trim());
                    if (pressKey(code)) {
                        reply = "OK";
                    } else {
                        // Eingabe-System tot (z. B. nach internem Android-Neustart): melden und beenden
                        reply = "FAIL";
                        exitAfter = true;
                    }
                } else if (line.equals("QUIT")) {
                    out.write("BYE\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    System.exit(0);
                    return;
                } else {
                    reply = "ERR";
                }
                out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (exitAfter) {
                    System.exit(2);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
