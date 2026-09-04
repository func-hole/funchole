package com.funchole.backend.controlplane.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.certificate.CertificateBundle;
import com.funchole.backend.certificate.CertificateReference;
import com.funchole.backend.certificate.store.CertificateStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenBaoCertificateStore implements CertificateStore {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baoAddress;
    private final String baoToken;

    public OpenBaoCertificateStore(
            @Value("${BAO_ADDR}") String baoAddress,
            @Value("${BAO_TOKEN}") String baoToken
    ) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.baoAddress = baoAddress;
        this.baoToken = baoToken;
    }

    @Override
    public void save(CertificateReference reference, CertificateBundle bundle) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "data", Map.of(
                            "certificateChain", new String(bundle.certificateChain(), StandardCharsets.UTF_8),
                            "privateKey", new String(bundle.privateKey(), StandardCharsets.UTF_8)
                    )
            ));

            HttpRequest request = HttpRequest.newBuilder(secretUri(reference.secretPath()))
                    .header("X-Vault-Token", baoToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("OpenBao write failed with status " + response.statusCode());
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to save certificate to OpenBao", exception);
        }
    }

    @Override
    public CertificateBundle load(CertificateReference reference) {
        try {
            HttpRequest request = HttpRequest.newBuilder(secretUri(reference.secretPath()))
                    .header("X-Vault-Token", baoToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("OpenBao read failed with status " + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data").path("data");
            String certificateChain = data.path("certificateChain").asText();
            String privateKey = data.path("privateKey").asText();
            if (certificateChain.isBlank() || privateKey.isBlank()) {
                throw new IllegalStateException("Certificate bundle is incomplete in OpenBao: " + reference.secretPath());
            }

            return new CertificateBundle(
                    certificateChain.getBytes(StandardCharsets.UTF_8),
                    privateKey.getBytes(StandardCharsets.UTF_8)
            );
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to load certificate from OpenBao", exception);
        }
    }

    private URI secretUri(String secretPath) {
        return URI.create(baoAddress + "/v1/secret/data/" + secretPath);
    }
}
