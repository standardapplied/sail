/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Builds a TLS {@link SSLContext} for the control-plane server from an operator-provided PEM
 * certificate chain and PKCS#8 private key. This is the "bring your own certificate" path — the
 * cert may come from a corporate CA, an ACME tool (certbot/Caddy), or sail's own internal CA. The
 * JDK has no public API to *generate* certificates, so cert minting is left to the system {@code
 * openssl} (a separate concern); here we only load existing material.
 */
public final class TlsMaterial {

  private static final char[] NO_PASSWORD = new char[0];

  private TlsMaterial() {}

  /**
   * Builds a server {@link SSLContext} from a PEM certificate chain (leaf first) and an unencrypted
   * PKCS#8 private key (a {@code -----BEGIN PRIVATE KEY-----} block).
   */
  public static SSLContext serverContext(String certChainPem, String privateKeyPem) {
    try {
      var chain = parseCertificates(certChainPem);
      var privateKey =
          parsePrivateKey(
              privateKeyPem, ((X509Certificate) chain[0]).getPublicKey().getAlgorithm());

      var keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(null, null);
      keyStore.setKeyEntry("server", privateKey, NO_PASSWORD, chain);

      var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, NO_PASSWORD);

      var context = SSLContext.getInstance("TLS");
      context.init(kmf.getKeyManagers(), null, null);
      return context;
    } catch (GeneralSecurityException | java.io.IOException e) {
      throw new IllegalArgumentException("Failed to build TLS context from PEM material", e);
    }
  }

  private static Certificate[] parseCertificates(String pem) throws GeneralSecurityException {
    var factory = CertificateFactory.getInstance("X.509");
    var certs =
        factory.generateCertificates(
            new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    if (certs.isEmpty()) {
      throw new IllegalArgumentException("No certificates found in PEM");
    }
    return certs.toArray(new Certificate[0]);
  }

  private static java.security.PrivateKey parsePrivateKey(String pem, String algorithm)
      throws GeneralSecurityException {
    var der = decodePemBlock(pem, "PRIVATE KEY");
    var spec = new PKCS8EncodedKeySpec(der);
    return KeyFactory.getInstance(algorithm).generatePrivate(spec);
  }

  private static byte[] decodePemBlock(String pem, String type) {
    var begin = "-----BEGIN " + type + "-----";
    var end = "-----END " + type + "-----";
    var start = pem.indexOf(begin);
    var finish = pem.indexOf(end);
    if (start < 0 || finish < 0) {
      throw new IllegalArgumentException(
          "Expected a " + begin + " block (unencrypted PKCS#8); convert SEC1/encrypted keys first");
    }
    var body = pem.substring(start + begin.length(), finish).replaceAll("\\s", "");
    return Base64.getDecoder().decode(body);
  }

  /** The leaf certificate subject (distinguished name), for logging which cert is in use. */
  public static String describe(String certChainPem) {
    try {
      var chain = parseCertificates(certChainPem);
      return ((X509Certificate) chain[0]).getSubjectX500Principal().getName();
    } catch (GeneralSecurityException | RuntimeException e) {
      return "<unparseable certificate>";
    }
  }
}
