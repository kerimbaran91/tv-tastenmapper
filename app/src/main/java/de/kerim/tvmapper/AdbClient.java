package de.kerim.tvmapper;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimaler ADB-Client: verbindet sich über „Debugging über WLAN“ (TLS) mit dem eigenen Gerät
 * und führt einen Shell-Befehl aus. Keine fremden Bibliotheken.
 */
final class AdbClient {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_AUTH = 0x48545541;
    private static final int A_STLS = 0x534c5453;
    private static final int VERSION = 0x01000001;
    private static final int STLS_VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;

    private AdbClient() {}

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    // ---------------- Port finden ----------------

    /** Sucht den Port von „Debugging über WLAN“ auf diesem Gerät. Liefert {host, port} oder null. */
    static Object[] findTlsPort(Context c, long timeoutMs) {
        // 1) Systemeigenschaft (falls lesbar)
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class).invoke(null, "service.adb.tls.port");
            if (v != null && !v.trim().isEmpty() && Integer.parseInt(v.trim()) > 0) {
                return new Object[]{"127.0.0.1", Integer.parseInt(v.trim())};
            }
        } catch (Throwable ignored) {
        }
        // 2) Netzwerk-Suche (mDNS) nach dem eigenen Gerät
        try {
            return discover(c, timeoutMs);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object[] discover(Context c, long timeoutMs) throws Exception {
        final NsdManager nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return null;
        final List<NsdServiceInfo> found = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch first = new CountDownLatch(1);
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String s, int i) { first.countDown(); }
            @Override public void onStopDiscoveryFailed(String s, int i) {}
            @Override public void onDiscoveryStarted(String s) {}
            @Override public void onDiscoveryStopped(String s) {}
            @Override public void onServiceFound(NsdServiceInfo info) { found.add(info); first.countDown(); }
            @Override public void onServiceLost(NsdServiceInfo info) {}
        };
        nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, l);
        try {
            first.await(timeoutMs, TimeUnit.MILLISECONDS);
            Thread.sleep(1500); // kurz weitere Geräte sammeln
        } finally {
            try {
                nsd.stopServiceDiscovery(l);
            } catch (Throwable ignored) {
            }
        }
        List<InetAddress> local = localAddresses();
        List<NsdServiceInfo> list;
        synchronized (found) {
            list = new ArrayList<>(found);
        }
        for (NsdServiceInfo info : list) {
            final NsdServiceInfo[] res = new NsdServiceInfo[1];
            final CountDownLatch done = new CountDownLatch(1);
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo s, int e) { done.countDown(); }
                @Override public void onServiceResolved(NsdServiceInfo s) { res[0] = s; done.countDown(); }
            });
            done.await(4, TimeUnit.SECONDS);
            if (res[0] == null || res[0].getHost() == null) continue;
            InetAddress h = res[0].getHost();
            for (InetAddress a : local) {
                if (a.equals(h)) return new Object[]{h.getHostAddress(), res[0].getPort()};
            }
        }
        return null;
    }

    private static List<InetAddress> localAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address || !a.isLinkLocalAddress()) out.add(a);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ---------------- Shell-Befehl ausführen ----------------

    /** Führt einen Shell-Befehl über ADB aus und liefert die Ausgabe. */
    static String shell(AdbKey key, String host, int port, String command) throws Exception {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), 5000);
            raw.setSoTimeout(10_000);
            raw.setTcpNoDelay(true);
            InputStream in = raw.getInputStream();
            OutputStream out = raw.getOutputStream();

            write(out, A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            Msg m = read(in);
            if (m.cmd == A_AUTH) throw new Exception("Gerät verlangt alte ADB-Anmeldung statt TLS");
            if (m.cmd != A_STLS) throw new Exception("Unerwartete ADB-Antwort");
            write(out, A_STLS, STLS_VERSION, 0, new byte[0]);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new KeyManager[]{new Km(key)}, new TrustManager[]{new TrustAll()}, null);
            try (SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true)) {
                ssl.setUseClientMode(true);
                ssl.setSoTimeout(10_000);
                ssl.startHandshake();
                InputStream sin = ssl.getInputStream();
                OutputStream sout = ssl.getOutputStream();

                m = read(sin);
                if (m.cmd != A_CNXN) throw new Exception("Keine ADB-Verbindung nach TLS (Schlüssel nicht gekoppelt?)");

                final int localId = 1;
                write(sout, A_OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int remoteId = 0;
                while (true) {
                    m = read(sin);
                    if (m.cmd == A_OKAY) {
                        remoteId = m.arg0;
                    } else if (m.cmd == A_WRTE) {
                        if (output.size() < 64 * 1024) output.write(m.data, 0, m.data.length);
                        write(sout, A_OKAY, localId, m.arg0, new byte[0]);
                    } else if (m.cmd == A_CLSE) {
                        if (remoteId != 0) write(sout, A_CLSE, localId, remoteId, new byte[0]);
                        break;
                    }
                }
                return output.toString("UTF-8");
            }
        }
    }

    private static void write(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xFF;
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private static Msg read(InputStream in) throws Exception {
        byte[] h = readFully(in, 24);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // Prüfsumme
        int magic = b.getInt();
        if (magic != ~m.cmd) throw new Exception("Fehlerhafte ADB-Nachricht");
        if (len < 0 || len > 1024 * 1024) throw new Exception("ADB-Nachricht zu groß");
        m.data = readFully(in, len);
        return m;
    }

    private static byte[] readFully(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("Verbindung beendet");
            off += r;
        }
        return buf;
    }

    // ---------------- TLS-Hilfen ----------------

    private static final class Km extends X509ExtendedKeyManager {
        private final AdbKey key;

        Km(AdbKey k) {
            key = k;
        }

        @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return "adb"; }
        @Override public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) { return "adb"; }
        @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{key.cert}; }
        @Override public PrivateKey getPrivateKey(String a) { return key.privateKey; }
        @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{"adb"}; }
        @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
        @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
    }

    private static final class TrustAll implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
        @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
