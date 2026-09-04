package com.funchole.backend.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.certificate.CertificateBundle;
import com.funchole.backend.certificate.CertificateReference;
import com.funchole.backend.certificate.store.CertificateLoader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public final class OpenBaoCertificateLoader implements CertificateLoader {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baoAddress;
    private final String baoToken;

    public OpenBaoCertificateLoader(String baoAddress, String baoToken) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.baoAddress = baoAddress;
        this.baoToken = baoToken;
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
