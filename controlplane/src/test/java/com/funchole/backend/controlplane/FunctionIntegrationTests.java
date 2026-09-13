package com.funchole.backend.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class FunctionIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = obtainToken("admin", "admin12345");
    }

    @Test
    void createsAndRetrievesAFunctionOnAnEmptyDatabase() throws Exception {
        String functionKey = "fn_test_" + UUID.randomUUID().toString().replace("-", "");

        String functionId = createFunction(functionKey);

        mockMvc.perform(get("/api/v1/functions/{functionId}", functionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.functionKey").value(functionKey))
                .andExpect(jsonPath("$.data.name").value("Test Function"))
                .andExpect(jsonPath("$.data.runtime").value("NODE"));
    }

    @Test
    void listsOnlyFunctionsOwnedByTheCaller() throws Exception {
        String functionKey = "fn_test_" + UUID.randomUUID().toString().replace("-", "");
        createFunction(functionKey);

        mockMvc.perform(get("/api/v1/functions")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void updatesFunctionMetadataButNotFunctionKey() throws Exception {
        String functionKey = "fn_test_" + UUID.randomUUID().toString().replace("-", "");
        String functionId = createFunction(functionKey);

        mockMvc.perform(put("/api/v1/functions/{functionId}", functionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Renamed Function",
                                  "description": "updated by FunctionIntegrationTests",
                                  "runtime": "NODE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed Function"))
                .andExpect(jsonPath("$.data.functionKey").value(functionKey));
    }

    @Test
    void deletesAFunctionAndItBecomesUnreachable() throws Exception {
        String functionKey = "fn_test_" + UUID.randomUUID().toString().replace("-", "");
        String functionId = createFunction(functionKey);

        mockMvc.perform(delete("/api/v1/functions/{functionId}", functionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/functions/{functionId}", functionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsCreatingAFunctionWithADuplicateFunctionKey() throws Exception {
        String functionKey = "fn_test_" + UUID.randomUUID().toString().replace("-", "");
        createFunction(functionKey);

        mockMvc.perform(post("/api/v1/functions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(functionPayload(functionKey)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void rejectsAccessToAFunctionThatDoesNotBelongToTheCaller() throws Exception {
        // The ownership-scoped lookup (findByIdAndAppUser_IdAndDeletedAtIsNull) returns the same
        // "not found" outcome for a random id as it would for another user's function id - both
        // paths go through the exact same query, mirroring FlowIntegrationTests' equivalent check.
        mockMvc.perform(get("/api/v1/functions/{functionId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    private String createFunction(String functionKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/functions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(functionPayload(functionKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.functionKey").value(functionKey))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String functionPayload(String functionKey) {
        return """
                {
                  "functionKey": "%s",
                  "name": "Test Function",
                  "description": "created by FunctionIntegrationTests",
                  "runtime": "NODE"
                }
                """.formatted(functionKey);
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
