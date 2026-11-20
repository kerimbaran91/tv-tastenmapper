package de.kerim.tvmapper;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Holt die neueste Version von GitHub und installiert sie über den System-Installer. */
final class Updater {
    static final String REPO = "kerimbaran91/tv-tastenmapper";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    static final class Release {
        int build;        // Build-Nummer (= versionCode)
        String name;      // z. B. "1.3"
        String apkUrl;
        long size;
    }

    interface Progress {
        void onProgress(int percent);
    }

    private Updater() {}

    static int installedBuild(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Fragt GitHub nach der neuesten Version. Nur im Hintergrund-Thread aufrufen. */
    static Release fetchLatest() throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(15_000);
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("User-Agent", "TV-Tastenmapper");
        int code = con.getResponseCode();
        if (code == 404) throw new Exception("Keine Version auf GitHub gefunden");
        if (code != 200) throw new Exception("GitHub antwortet mit Fehler " + code);

        JSONObject json;
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            json = new JSONObject(bos.toString("UTF-8"));
        } finally {
            con.disconnect();
        }

        Release r = new Release();
        String tag = json.optString("tag_name", "");
        Matcher m = Pattern.compile("(\\d+)$").matcher(tag);
        if (!m.find()) throw new Exception("Unbekanntes Versionsformat: " + tag);
        r.build = Integer.parseInt(m.group(1));
        r.name = tag.startsWith("v") ? tag.substring(1) : tag;

        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                    r.apkUrl = a.optString("browser_download_url", null);
                    r.size = a.optLong("size", 0);
                    break;
                }
            }
        }
        if (r.apkUrl == null) throw new Exception("Keine APK in der neuesten Version gefunden");
        return r;
    }

    /** Darf die App Updates installieren („Unbekannte Quellen“)? */
    static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            return c.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Lädt die APK herunter und übergibt sie an den System-Installer. Hintergrund-Thread! */
    static void downloadAndInstall(Context c, Release r, Progress p) throws Exception {
        HttpURLConnection con = open(r.apkUrl);
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        long total = con.getContentLengthLong() > 0 ? con.getContentLengthLong() : r.size;
        if (total > 0) params.setSize(total);

        int sessionId = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(sessionId);
        try {
            try (InputStream in = con.getInputStream();
                 OutputStream out = session.openWrite("update.apk", 0, total > 0 ? total : -1)) {
                byte[] buf = new byte[65536];
                long done = 0;
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0 && p != null) {
                        int pct = (int) (done * 100 / total);
                        if (pct != last) {
                            last = pct;
                            p.onProgress(pct);
                        }
                    }
                }
                session.fsync(out);
            } finally {
                con.disconnect();
            }

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            Intent cb = new Intent(c, InstallReceiver.class).setPackage(c.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(c, sessionId, cb, flags);
            session.commit(pending.getIntentSender());
        } catch (Exception e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    /** Öffnet eine Verbindung und folgt Weiterleitungen (GitHub leitet Downloads um). */
    private static HttpURLConnection open(String url) throws Exception {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            con.setRequestProperty("User-Agent", "TV-Tastenmapper");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) break;
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                con.disconnect();
                throw new Exception("Download fehlgeschlagen (Fehler " + code + ")");
            }
            return con;
        }
        throw new Exception("Download fehlgeschlagen (zu viele Weiterleitungen)");
    }
}
