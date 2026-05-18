package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class FlowUploadResourceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private KeycloakSession session;
    private RealmModel realm;
    private FlowUploadProvider.FlowUploadResource resource;

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        KeycloakContext context = mock(KeycloakContext.class);
        realm = mock(RealmModel.class);

        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);

        resource = new FlowUploadProvider.FlowUploadResource(session);
    }

    @Test
    void listFlowsReturnsOnlyTopLevelFlows() throws Exception {
        AuthenticationFlowModel topLevel = flow("flow-top", "id-top", true, false, "basic-flow", "desc");
        AuthenticationFlowModel nested = flow("flow-nested", "id-nested", false, false, "basic-flow", "desc");
        when(realm.getAuthenticationFlowsStream()).thenReturn(Stream.of(topLevel, nested));

        Response response = resource.listFlows();

        assertEquals(200, response.getStatus());
        JsonNode body = OBJECT_MAPPER.readTree(String.valueOf(response.getEntity()));
        assertEquals(1, body.size());
        assertEquals("flow-top", body.get(0).get("alias").asText());
    }

    @Test
    void listFlowsReturnsServerErrorOnException() {
        when(realm.getAuthenticationFlowsStream()).thenThrow(new RuntimeException("list failed"));

        Response response = resource.listFlows();

        assertEquals(500, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("list failed"));
    }

    @Test
    void deleteFlowReturnsNotFoundWhenAliasIsMissing() {
        when(realm.getFlowByAlias("missing")).thenReturn(null);

        Response response = resource.deleteFlow("missing");

        assertEquals(404, response.getStatus());
    }

    @Test
    void deleteFlowRejectsBuiltInFlow() {
        AuthenticationFlowModel builtIn = flow("browser", "id-browser", true, true, "basic-flow", "Built-in");
        when(realm.getFlowByAlias("browser")).thenReturn(builtIn);

        Response response = resource.deleteFlow("browser");

        assertEquals(400, response.getStatus());
    }

    @Test
    void deleteFlowRemovesCustomFlow() {
        AuthenticationFlowModel custom = flow("custom", "id-custom", true, false, "basic-flow", "Custom");
        when(realm.getFlowByAlias("custom")).thenReturn(custom);

        Response response = resource.deleteFlow("custom");

        assertEquals(200, response.getStatus());
        verify(realm).removeAuthenticationFlow(custom);
    }

    @Test
    void exportFlowReturnsNotFoundForUnknownAlias() {
        when(realm.getFlowByAlias("missing")).thenReturn(null);

        Response response = resource.exportFlow("missing");

        assertEquals(404, response.getStatus());
    }

    @Test
    void exportFlowIncludesSubflowsAndConfigAliases() throws Exception {
        AuthenticationFlowModel top = flow("top-flow", "id-top", true, false, "basic-flow", "Top");
        AuthenticationFlowModel sub = flow("sub-flow", "id-sub", false, false, "basic-flow", "Sub");
        AuthenticationExecutionModel configExec = execution("id-top", false, null, "auth-cookie", "ALTERNATIVE", 10, "cfg-id");
        AuthenticationExecutionModel subFlowExec = execution("id-top", true, "id-sub", null, "ALTERNATIVE", 20, null);
        AuthenticatorConfigModel config = config("cfg-id", "cfg-alias");

        when(realm.getFlowByAlias("top-flow")).thenReturn(top);
        when(realm.getAuthenticationExecutionsStream("id-top")).thenReturn(Stream.of(configExec, subFlowExec));
        when(realm.getAuthenticationExecutionsStream("id-sub")).thenReturn(Stream.empty());
        when(realm.getAuthenticationFlowById("id-sub")).thenReturn(sub);
        when(realm.getAuthenticatorConfigById("cfg-id")).thenReturn(config);

        Response response = resource.exportFlow("top-flow");

        assertEquals(200, response.getStatus());
        JsonNode body = OBJECT_MAPPER.readTree(String.valueOf(response.getEntity()));
        assertEquals("top-flow", body.get("alias").asText());
        assertEquals(2, body.get("authenticationFlows").size());
        assertEquals("cfg-alias", body.get("authenticatorConfigs").get(0).get("alias").asText());
    }

    @Test
    void exportAllFlowsSkipsFlowThatFailsToExport() throws Exception {
        AuthenticationFlowModel top = flow("top-flow", "id-top", true, false, "basic-flow", "Top");
        when(realm.getAuthenticationFlowsStream()).thenReturn(Stream.of(top));
        when(realm.getAuthenticationExecutionsStream("id-top")).thenThrow(new RuntimeException("broken flow"));

        Response response = resource.exportAllFlows();

        assertEquals(200, response.getStatus());
        JsonNode body = OBJECT_MAPPER.readTree(String.valueOf(response.getEntity()));
        assertTrue(body.isArray());
        assertEquals(0, body.size());
    }

    @Test
    void uploadFlowImportsBareFlowFormat() {
        Map<String, AuthenticationFlowModel> flowStore = new HashMap<>();
        when(realm.getFlowByAlias(anyString())).thenAnswer(invocation -> flowStore.get(invocation.getArgument(0)));
        when(realm.addAuthenticationFlow(any(AuthenticationFlowModel.class))).thenAnswer(invocation -> {
            AuthenticationFlowModel added = invocation.getArgument(0);
            added.setId("id-" + added.getAlias());
            flowStore.put(added.getAlias(), added);
            return added;
        });

        String payload = "{\"alias\":\"bare-flow\",\"authenticationExecutions\":[{\"authenticator\":\"auth-cookie\",\"authenticatorFlow\":false,\"requirement\":\"ALTERNATIVE\",\"priority\":10}]}";
        Response response = resource.uploadFlow(payload);

        assertEquals(200, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("bare-flow"));
        verify(realm).addAuthenticationFlow(any(AuthenticationFlowModel.class));
        verify(realm).addAuthenticatorExecution(any(AuthenticationExecutionModel.class));
    }

    @Test
    void uploadFlowImportsWrapperFormatAndReusesExistingConfig() {
        Map<String, AuthenticationFlowModel> flowStore = new HashMap<>();
        when(realm.getFlowByAlias(anyString())).thenAnswer(invocation -> flowStore.get(invocation.getArgument(0)));
        when(realm.addAuthenticationFlow(any(AuthenticationFlowModel.class))).thenAnswer(invocation -> {
            AuthenticationFlowModel added = invocation.getArgument(0);
            added.setId("id-" + added.getAlias());
            flowStore.put(added.getAlias(), added);
            return added;
        });

        AuthenticatorConfigModel existingConfig = config("cfg-existing-id", "cfg-existing");
        when(realm.getAuthenticatorConfigsStream()).thenReturn(Stream.of(existingConfig));

        String payload = """
                {
                  "alias": "wrapper",
                  "authenticatorConfigs": [
                    { "alias": "cfg-existing", "config": { "k":"v" } }
                  ],
                  "authenticationFlows": [
                    {
                      "alias": "root-flow",
                      "topLevel": true,
                      "providerId": "basic-flow",
                      "authenticationExecutions": [
                        {
                          "authenticatorFlow": true,
                          "flowAlias": "sub-flow",
                          "requirement": "ALTERNATIVE",
                          "priority": 10
                        },
                        {
                          "authenticator": "auth-cookie",
                          "authenticatorFlow": false,
                          "authenticatorConfig": "cfg-existing",
                          "requirement": "ALTERNATIVE",
                          "priority": 20
                        }
                      ]
                    },
                    {
                      "alias": "sub-flow",
                      "topLevel": false,
                      "providerId": "basic-flow",
                      "authenticationExecutions": []
                    }
                  ]
                }
                """;

        Response response = resource.uploadFlow(payload);

        assertEquals(200, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("root-flow"));
        verify(realm, atLeast(2)).addAuthenticatorExecution(any(AuthenticationExecutionModel.class));
        verify(realm, never()).addAuthenticatorConfig(any(AuthenticatorConfigModel.class));
    }

    @Test
    void uploadFlowReturnsServerErrorForMalformedJson() {
        Response response = resource.uploadFlow("{not-valid-json}");

        assertEquals(500, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("error"));
    }

    private AuthenticationFlowModel flow(String alias, String id, boolean topLevel, boolean builtIn,
                                         String providerId, String description) {
        AuthenticationFlowModel model = new AuthenticationFlowModel();
        model.setAlias(alias);
        model.setId(id);
        model.setTopLevel(topLevel);
        model.setBuiltIn(builtIn);
        model.setProviderId(providerId);
        model.setDescription(description);
        return model;
    }

    private AuthenticationExecutionModel execution(String parentFlow, boolean isSubflow, String flowId,
                                                   String authenticator, String requirement, int priority,
                                                   String configId) {
        AuthenticationExecutionModel model = new AuthenticationExecutionModel();
        model.setParentFlow(parentFlow);
        model.setAuthenticatorFlow(isSubflow);
        model.setFlowId(flowId);
        model.setAuthenticator(authenticator);
        model.setRequirement(AuthenticationExecutionModel.Requirement.valueOf(requirement));
        model.setPriority(priority);
        model.setAuthenticatorConfig(configId);
        return model;
    }

    private AuthenticatorConfigModel config(String id, String alias) {
        AuthenticatorConfigModel model = new AuthenticatorConfigModel();
        model.setId(id);
        model.setAlias(alias);
        model.setConfig(Map.of("k", "v"));
        assertNotNull(model.getConfig());
        assertFalse(model.getConfig().isEmpty());
        return model;
    }
}
