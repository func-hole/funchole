package com.funchole.backend.certificate.generator;

import com.funchole.backend.certificate.CertificateBundle;
import com.funchole.backend.certificate.CertificateRequest;
import com.funchole.backend.certificate.GeneratedCertificate;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public class SelfSignedCertificateGenerator implements CertificateGenerator {
    private static final String PROVIDER_NAME = "BC";

    private final Duration validity;
    private final SecureRandom secureRandom;

    public SelfSignedCertificateGenerator(Duration validity) {
        this.validity = validity;
        this.secureRandom = new SecureRandom();
        ensureProviderRegistered();
    }

    @Override
    public GeneratedCertificate generate(CertificateRequest request) {
        try {
            KeyPair keyPair = generateKeyPair();
            Instant issuedAt = Instant.now();
            Instant expiresAt = issuedAt.plus(validity);
            X509Certificate certificate = generateCertificate(request, keyPair, issuedAt, expiresAt);
            CertificateBundle bundle = new CertificateBundle(
                    toPem(certificate).getBytes(StandardCharsets.UTF_8),
                    toPem(keyPair.getPrivate()).getBytes(StandardCharsets.UTF_8)
            );

            return new GeneratedCertificate(
                    bundle,
                    OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
            );
        } catch (GeneralSecurityException | IOException | OperatorCreationException exception) {
            throw new IllegalStateException("Failed to generate self-signed certificate", exception);
        }
    }

    private KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048, secureRandom);
        return keyPairGenerator.generateKeyPair();
    }

    private X509Certificate generateCertificate(
            CertificateRequest request,
            KeyPair keyPair,
            Instant issuedAt,
            Instant expiresAt
    ) throws IOException, OperatorCreationException, GeneralSecurityException {
        X500Name subject = new X500Name("CN=" + request.commonName());
        BigInteger serialNumber = new BigInteger(160, secureRandom);
        X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                subject,
                serialNumber,
                Date.from(issuedAt),
                Date.from(expiresAt),
                subject,
                keyPair.getPublic()
        );

        certificateBuilder.addExtension(
                Extension.subjectAlternativeName,
                false,
                new GeneralNames(request.subjectAlternativeNames().stream()
                        .map(name -> new GeneralName(GeneralName.dNSName, name))
                        .toArray(GeneralName[]::new))
        );

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(PROVIDER_NAME)
                .build(keyPair.getPrivate());
        X509CertificateHolder holder = certificateBuilder.build(contentSigner);
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(PROVIDER_NAME)
                .getCertificate(holder);
        certificate.verify(keyPair.getPublic());
        return certificate;
    }

    private String toPem(Object value) throws IOException {
        try (StringWriter stringWriter = new StringWriter(); JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
            pemWriter.writeObject(value);
            pemWriter.flush();
            return stringWriter.toString();
        }
    }

    private void ensureProviderRegistered() {
        if (java.security.Security.getProvider(PROVIDER_NAME) == null) {
            java.security.Security.addProvider(new BouncyCastleProvider());
        }
    }
}
