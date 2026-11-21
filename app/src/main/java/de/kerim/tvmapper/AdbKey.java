package de.kerim.tvmapper;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * Der ADB-Schlüssel (von Termux übernommen) und ein passendes Zertifikat für die TLS-Verbindung.
 * Das Zertifikat wird selbst erzeugt – dafür braucht es keine fremde Bibliothek.
 */
final class AdbKey {
    private static final String KEY_FILE = "adbkey.pk8";

    final PrivateKey privateKey;
    final X509Certificate cert;

    private AdbKey(PrivateKey k, X509Certificate c) {
        privateKey = k;
        cert = c;
    }

    static boolean exists(Context c) {
        return new File(c.getFilesDir(), KEY_FILE).exists();
    }

    /** Übernimmt den Schlüssel aus dem Text einer adbkey-Datei (PEM). Wirft bei Fehlern. */
    static void importPem(Context c, String pem) throws Exception {
        byte[] der = pemToPkcs8(pem);
        build(der); // prüfen, ob alles klappt
        File f = new File(c.getFilesDir(), KEY_FILE);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(der);
        }
    }

    static AdbKey load(Context c) throws Exception {
        File f = new File(c.getFilesDir(), KEY_FILE);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        }
        return build(bos.toByteArray());
    }

    private static AdbKey build(byte[] pkcs8) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        if (!(priv instanceof RSAPrivateCrtKey)) throw new Exception("Kein RSA-Schlüssel");
        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
        PublicKey pub = kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        byte[] certDer = selfSignedCert(priv, pub);
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certDer));
        return new AdbKey(priv, cert);
    }

    // ---------------- PEM ----------------

    static byte[] pemToPkcs8(String pem) throws Exception {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        if (!pem.contains("PRIVATE KEY")) throw new Exception("Keine gültige adbkey-Datei");
        StringBuilder b64 = new StringBuilder();
        for (String line : pem.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("-----")) continue;
            b64.append(line);
        }
        byte[] der = android.util.Base64.decode(b64.toString(), android.util.Base64.DEFAULT);
        if (pkcs1) {
            // PKCS#1 in PKCS#8 verpacken
            der = seq(cat(
                    integer(BigInteger.ZERO),
                    seq(cat(RSA_OID, NULL)),
                    tlv(0x04, der)));
        }
        return der;
    }

    // ---------------- Zertifikat (minimal, DER) ----------------

    private static final byte[] RSA_OID = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01};
    private static final byte[] SHA256_RSA_OID = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] CN_OID = {0x06, 0x03, 0x55, 0x04, 0x03};
    private static final byte[] NULL = {0x05, 0x00};

    private static byte[] selfSignedCert(PrivateKey priv, PublicKey pub) throws Exception {
        byte[] algId = seq(cat(SHA256_RSA_OID, NULL));
        byte[] name = seq(tlv(0x31, seq(cat(CN_OID, tlv(0x0C, "tvmapper".getBytes("UTF-8"))))));
        byte[] validity = seq(cat(
                tlv(0x17, "200101000000Z".getBytes("US-ASCII")),
                tlv(0x17, "491231235959Z".getBytes("US-ASCII"))));
        byte[] tbs = seq(cat(
                tlv(0xA0, integer(BigInteger.valueOf(2))),
                integer(BigInteger.ONE),
                algId,
                name,
                validity,
                name,
                pub.getEncoded()));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(priv);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bitString = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bitString, 1, sig.length);
        return seq(cat(tbs, algId, tlv(0x03, bitString)));
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] seq(byte[] content) {
        return tlv(0x30, content);
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = content.length;
        if (len < 0x80) {
            o.write(len);
        } else if (len < 0x100) {
            o.write(0x81);
            o.write(len);
        } else if (len < 0x10000) {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len & 0xFF);
        } else {
            o.write(0x83);
            o.write(len >> 16);
            o.write((len >> 8) & 0xFF);
            o.write(len & 0xFF);
        }
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
}
