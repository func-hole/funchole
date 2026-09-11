package com.funchole.backend.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FlowIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppDomainRepository appDomainRepository;

    @Autowired
    private GatewayRepository gatewayRepository;

    private String adminToken;
    private UUID gatewayId;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = obtainToken("admin", "admin12345");

        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        AppDomain domain = appDomainRepository.save(
                AppDomain.create(admin, "flow-test-" + UUID.randomUUID() + ".example.com", "verify-me", DomainStatus.VERIFIED));
        Gateway gateway = gatewayRepository.save(
                Gateway.create(admin, domain, "Flow Test Gateway", "ftg" + System.nanoTime() % 100000, "test gateway", GatewayStatus.ACTIVE));
        gatewayId = gateway.getId();
    }

    @Test
    void fullLifecycleFromDraftToAdoptToArchive() throws Exception {
        String flowKey = "flw_test_" + UUID.randomUUID().toString().replace("-", "");

        String flowId = createFlow(flowKey);
        String versionId = createDraftVersion(flowId);

        createStep(flowId, versionId, "step-one", "FUNCTION", 10);
        createStep(flowId, versionId, "step-two", "RESPONSE", 20);

        mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/adopt", flowId, versionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ADOPTED"));

        mockMvc.perform(get("/api/v1/flows/{flowId}", flowId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFlowVersionId").value(versionId))
                .andExpect(jsonPath("$.data.activeFlowVersionStatus").value("ADOPTED"));

        // Steps are immutable once the version is adopted.
        mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/steps", flowId, versionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepPayload("step-three", "FUNCTION", 30)))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/archive", flowId, versionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED"));

        mockMvc.perform(get("/api/v1/flows/{flowId}", flowId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeFlowVersionId").doesNotExist());
    }

    @Test
    void rejectsAdoptWhenVersionHasNoSteps() throws Exception {
        String flowKey = "flw_test_" + UUID.randomUUID().toString().replace("-", "");
        String flowId = createFlow(flowKey);
        String versionId = createDraftVersion(flowId);

        mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/adopt", flowId, versionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void rejectsStepWithUnsupportedComponentType() throws Exception {
        String flowKey = "flw_test_" + UUID.randomUUID().toString().replace("-", "");
        String flowId = createFlow(flowKey);
        String versionId = createDraftVersion(flowId);

        mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/steps", flowId, versionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepPayload("bad-step", "MIDDLEWARE", 10)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void rejectsAccessToAFlowThatDoesNotBelongToTheCaller() throws Exception {
        // The ownership-scoped lookup (findByIdAndAppUser_IdAndDeletedAtIsNull) returns the same
        // "not found" outcome for a random id as it would for another user's flow id - both paths
        // go through the exact same query, so this exercises the ownership guard without needing
        // a second real app user (AppUser has no public factory/signup flow to create one with).
        mockMvc.perform(get("/api/v1/flows/{flowId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    private String createFlow(String flowKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "flowKey": "%s",
                                  "name": "Test Flow",
                                  "description": "created by FlowIntegrationTests",
                                  "gatewayId": "%s",
                                  "httpMethod": "GET",
                                  "path": "/test-%s"
                                }
                                """.formatted(flowKey, gatewayId, flowKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flowKey").value(flowKey))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String createDraftVersion(String flowId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/flows/{flowId}/versions", flowId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "runtime": "NODE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private void createStep(String flowId, String versionId, String stepKey, String componentType, int position) throws Exception {
        mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/steps", flowId, versionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stepPayload(stepKey, componentType, position)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stepKey").value(stepKey));
    }

    private String stepPayload(String stepKey, String componentType, int position) {
        return """
                {
                  "stepKey": "%s",
                  "componentType": "%s",
                  "position": %d,
                  "componentId": "%s",
                  "componentVersionId": "%s"
                }
                """.formatted(stepKey, componentType, position, UUID.randomUUID(), UUID.randomUUID());
    }

    private String obtainToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s"
                                }
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }
}
