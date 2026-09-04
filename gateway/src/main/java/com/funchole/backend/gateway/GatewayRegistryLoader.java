package com.funchole.backend.gateway;

import com.funchole.backend.certificate.CertificateBundle;
import com.funchole.backend.certificate.CertificateReference;
import com.funchole.backend.certificate.store.CertificateLoader;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GatewayRegistryLoader {
    private static final Logger logger = LoggerFactory.getLogger(GatewayRegistryLoader.class);

    private final DataSource dataSource;
    private final CertificateLoader certificateLoader;

    public GatewayRegistryLoader(DataSource dataSource, CertificateLoader certificateLoader) {
        this.dataSource = dataSource;
        this.certificateLoader = certificateLoader;
    }

    public GatewayRegistrySnapshot load() {
        try {
            Map<String, GatewayRuntimeEntry> entries = loadEntries();
            SslContext defaultContext = entries.isEmpty()
                    ? createFallbackSslContext()
                    : entries.values().iterator().next().sslContext();

            if (entries.isEmpty()) {
                logger.warn("No active gateway certificates were found. Gateway will start with a fallback TLS context and return unknown-host responses until gateways are provisioned.");
            }

            return new GatewayRegistrySnapshot(Map.copyOf(entries), defaultContext);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load gateway registry", exception);
        }
    }

    private Map<String, GatewayRuntimeEntry> loadEntries() throws SQLException {
        Map<String, GatewayRuntimeEntry> entries = new LinkedHashMap<>();
        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        select
                            g.id as gateway_id,
                            g.name as gateway_name,
                            g.unique_key,
                            d.domain_name,
                            c.secret_ref,
                            c.provider
                        from gateways g
                        join app_domains d on d.id = g.app_domain_id
                        join certificates c on c.gateway_id = g.id
                        where g.status = 'ACTIVE'
                          and d.status = 'VERIFIED'
                          and c.status = 'ACTIVE'
                          and c.secret_ref is not null
                        order by g.created_at desc
                        """)
        ) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String gatewayKey = resultSet.getString("unique_key");
                    String domainName = resultSet.getString("domain_name");
                    GatewayCertificateRecord record = new GatewayCertificateRecord(
                            UUID.fromString(resultSet.getString("gateway_id")),
                            resultSet.getString("gateway_name"),
                            gatewayKey,
                            domainName,
                            normalizeHostname(gatewayKey + "." + domainName),
                            resultSet.getString("secret_ref"),
                            Enum.valueOf(com.funchole.backend.certificate.CertificateProvider.class, resultSet.getString("provider"))
                    );
                    try {
                        GatewayRuntimeEntry entry = toRuntimeEntry(record);
                        entries.put(entry.hostname(), entry);
                    } catch (IllegalStateException exception) {
                        logger.warn("Skipping gateway {} for hostname {} because TLS material could not be loaded", record.gatewayId(), record.hostname(), exception);
                    }
                }
            }
        }
        return entries;
    }

    private GatewayRuntimeEntry toRuntimeEntry(GatewayCertificateRecord record) {
        CertificateBundle bundle = certificateLoader.load(new CertificateReference(record.secretRef()));
        try {
            SslContext sslContext = SslContextBuilder.forServer(
                    new ByteArrayInputStream(bundle.certificateChain()),
                    new ByteArrayInputStream(bundle.privateKey())
            ).build();
            logger.info("Loaded gateway TLS material for {}", record.hostname());
            return new GatewayRuntimeEntry(
                    record.gatewayId(),
                    record.gatewayName(),
                    record.gatewayKey(),
                    record.domainName(),
                    record.hostname(),
                    record.certificateProvider(),
                    sslContext
            );
        } catch (SSLException exception) {
            throw new IllegalStateException("Failed to build SslContext for " + record.hostname(), exception);
        }
    }

    private SslContext createFallbackSslContext() {
        try {
            SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
            return SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).build();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to build fallback TLS context", exception);
        }
    }

    private String normalizeHostname(String hostname) {
        return hostname == null ? "" : hostname.trim().toLowerCase();
    }
}
