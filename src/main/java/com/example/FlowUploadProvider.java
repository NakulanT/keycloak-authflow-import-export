package com.example;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.AuthenticationFlowModel;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.services.resource.RealmResourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class FlowUploadProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    public FlowUploadProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return new FlowUploadResource(session);
    }

    @Override
    public void close() {}

    @Path("/")
    public static class FlowUploadResource {

        private final KeycloakSession session;
        private final ObjectMapper mapper = new ObjectMapper();

        public FlowUploadResource(KeycloakSession session) {
            this.session = session;
        }

        // ──────────────────────────────────────────────
        //  IMPORT — POST /realms/{realm}/flow-uploader
        // ──────────────────────────────────────────────
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public Response uploadFlow(String flowJson) {
            try {
                RealmModel realm = session.getContext().getRealm();
                JsonNode root = mapper.readTree(flowJson);

                // Shared map: config alias → saved config ID (built up as we process each wrapper)
                Map<String, String> configAliasToId = new HashMap<>();
                List<String> importedFlows = new ArrayList<>();

                // ── Detect root format ─────────────────────────────────────────────
                // Format A – export-all / export single:
                //   Array or Object where each element has "authenticationFlows" key
                //     [ { "alias":..., "authenticationFlows":[...], "authenticatorConfigs":[...] } ]
                // Format B – bare flows:
                //   Array or Object where elements ARE the flow (have "authenticationExecutions")
                //     [ { "alias":..., "authenticationExecutions":[...] } ]

                if (root.isArray()) {
                    for (JsonNode item : root) {
                        importedFlows.addAll(importItem(realm, item, configAliasToId));
                    }
                } else {
                    importedFlows.addAll(importItem(realm, root, configAliasToId));
                }

                ObjectNode result = mapper.createObjectNode();
                result.put("status", "imported successfully");
                result.set("flows", mapper.valueToTree(importedFlows));
                return Response.ok(result.toString()).build();

            } catch (Exception e) {
                ObjectNode error = mapper.createObjectNode();
                error.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                return Response.serverError().entity(error.toString()).build();
            }
        }

        /**
         * Handles one item which is either:
         *   - A wrapper object: { "alias":..., "authenticationFlows":[...], "authenticatorConfigs":[...] }
         *   - A bare flow object: { "alias":..., "authenticationExecutions":[...] }
         */
        private List<String> importItem(RealmModel realm, JsonNode item,
                                        Map<String, String> configAliasToId) throws Exception {
            List<String> imported = new ArrayList<>();

            if (item.has("authenticationFlows")) {
                // ── Wrapper format (our own export-all / export-single output) ───────

                // Step 1: Import authenticatorConfigs from this wrapper first
                if (item.has("authenticatorConfigs")) {
                    for (JsonNode cfgNode : item.get("authenticatorConfigs")) {
                        String alias = cfgNode.get("alias").asText();
                        AuthenticatorConfigModel existing = findConfigByAlias(realm, alias);
                        if (existing != null) {
                            configAliasToId.put(alias, existing.getId());
                        } else {
                            AuthenticatorConfigModel m = new AuthenticatorConfigModel();
                            m.setAlias(alias);
                            Map<String, String> cfgMap = new HashMap<>();
                            if (cfgNode.has("config")) {
                                cfgNode.get("config").fields()
                                       .forEachRemaining(e -> cfgMap.put(e.getKey(), e.getValue().asText()));
                            }
                            m.setConfig(cfgMap);
                            AuthenticatorConfigModel saved = realm.addAuthenticatorConfig(m);
                            configAliasToId.put(alias, saved.getId());
                        }
                    }
                }

                // Step 2: Collect all flow nodes and build an alias→node lookup
                List<JsonNode> flowNodes = new ArrayList<>();
                Map<String, JsonNode> flowNodeByAlias = new HashMap<>();
                for (JsonNode fn : item.get("authenticationFlows")) {
                    flowNodes.add(fn);
                    flowNodeByAlias.put(fn.get("alias").asText(), fn);
                }

                // Step 3: Import top-level flows first (they drive recursive sub-flow creation)
                for (JsonNode fn : flowNodes) {
                    if (fn.has("topLevel") && fn.get("topLevel").asBoolean()) {
                        AuthenticationFlowModel m =
                                importFlowRecursive(realm, fn, true, configAliasToId, flowNodeByAlias);
                        imported.add(m.getAlias());
                    }
                }

                // Step 4: Import any remaining non-top-level flows not yet created
                for (JsonNode fn : flowNodes) {
                    if (!fn.has("topLevel") || !fn.get("topLevel").asBoolean()) {
                        String alias = fn.get("alias").asText();
                        if (realm.getFlowByAlias(alias) == null) {
                            importFlowRecursive(realm, fn, false, configAliasToId, flowNodeByAlias);
                        }
                    }
                }

            } else {
                // ── Bare flow format (authenticationExecutions directly in item) ────
                boolean isTop = !item.has("topLevel") || item.get("topLevel").asBoolean();
                AuthenticationFlowModel m =
                        importFlowRecursive(realm, item, isTop, configAliasToId, Collections.emptyMap());
                imported.add(m.getAlias());
            }

            return imported;
        }

        // ──────────────────────────────────────────────
        //  EXPORT single flow — GET /realms/{realm}/flow-uploader/export/{alias}
        // ──────────────────────────────────────────────
        @GET
        @Path("/export/{alias}")
        @Produces(MediaType.APPLICATION_JSON)
        public Response exportFlow(@PathParam("alias") String alias) {
            try {
                RealmModel realm = session.getContext().getRealm();
                AuthenticationFlowModel flow = realm.getFlowByAlias(alias);

                if (flow == null) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity("{\"error\":\"Flow not found: " + alias + "\"}")
                            .build();
                }

                Set<String> configIds = new HashSet<>();
                ObjectNode result = buildFlowExportWrapper(realm, flow, configIds);

                return Response.ok(mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result)).build();

            } catch (Exception e) {
                return Response.serverError()
                        .entity("{\"error\":\"" + e.getMessage() + "\"}")
                        .build();
            }
        }

        // ──────────────────────────────────────────────
        //  LIST all custom flows — GET /realms/{realm}/flow-uploader/list
        // ──────────────────────────────────────────────
        @GET
        @Path("/list")
        @Produces(MediaType.APPLICATION_JSON)
        public Response listFlows() {
            try {
                RealmModel realm = session.getContext().getRealm();
                ArrayNode flowsList = mapper.createArrayNode();

                realm.getAuthenticationFlowsStream()
                        .filter(f -> f.isTopLevel())
                        .forEach(flow -> {
                            ObjectNode node = mapper.createObjectNode();
                            node.put("alias", flow.getAlias());
                            node.put("description", flow.getDescription() != null ? flow.getDescription() : "");
                            node.put("providerId", flow.getProviderId());
                            node.put("builtIn", flow.isBuiltIn());
                            flowsList.add(node);
                        });

                return Response.ok(flowsList.toString()).build();

            } catch (Exception e) {
                return Response.serverError()
                        .entity("{\"error\":\"" + e.getMessage() + "\"}")
                        .build();
            }
        }

        // ──────────────────────────────────────────────
        //  EXPORT ALL custom flows — GET /realms/{realm}/flow-uploader/export-all
        // ──────────────────────────────────────────────
        @GET
        @Path("/export-all")
        @Produces(MediaType.APPLICATION_JSON)
        public Response exportAllFlows() {
            try {
                RealmModel realm = session.getContext().getRealm();
                ArrayNode allFlows = mapper.createArrayNode();

                realm.getAuthenticationFlowsStream()
                        .filter(f -> f.isTopLevel())
                        .forEach(flow -> {
                            try {
                                Set<String> configIds = new HashSet<>();
                                allFlows.add(buildFlowExportWrapper(realm, flow, configIds));
                            } catch (Exception ignored) {}
                        });

                return Response.ok(mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(allFlows)).build();

            } catch (Exception e) {
                return Response.serverError()
                        .entity("{\"error\":\"" + e.getMessage() + "\"}")
                        .build();
            }
        }

        // ──────────────────────────────────────────────
        //  DELETE flow — DELETE /realms/{realm}/flow-uploader/delete/{alias}
        // ──────────────────────────────────────────────
        @DELETE
        @Path("/delete/{alias}")
        @Produces(MediaType.APPLICATION_JSON)
        public Response deleteFlow(@PathParam("alias") String alias) {
            try {
                RealmModel realm = session.getContext().getRealm();
                AuthenticationFlowModel flow = realm.getFlowByAlias(alias);

                if (flow == null) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity("{\"error\":\"Flow not found: " + alias + "\"}")
                            .build();
                }

                if (flow.isBuiltIn()) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity("{\"error\":\"Cannot delete built-in flow\"}")
                            .build();
                }

                realm.removeAuthenticationFlow(flow);
                return Response.ok("{\"status\":\"deleted\",\"alias\":\"" + alias + "\"}").build();

            } catch (Exception e) {
                return Response.serverError()
                        .entity("{\"error\":\"" + e.getMessage() + "\"}")
                        .build();
            }
        }

        // ──────────────────────────────────────────────
        //  PRIVATE HELPERS
        // ──────────────────────────────────────────────

        private AuthenticatorConfigModel findConfigByAlias(RealmModel realm, String alias) {
            return realm.getAuthenticatorConfigsStream()
                    .filter(c -> alias.equals(c.getAlias()))
                    .findFirst()
                    .orElse(null);
        }

        /**
         * Recursively imports a flow from a flow JSON node.
         *
         * @param flowNodeByAlias lookup map of alias→flowNode from the same export wrapper;
         *                        used to resolve sub-flow definitions when the execution only
         *                        has a "flowAlias" reference (no inline authenticationExecutions).
         */
        private AuthenticationFlowModel importFlowRecursive(
                RealmModel realm,
                JsonNode flowNode,
                boolean topLevel,
                Map<String, String> configAliasToId,
                Map<String, JsonNode> flowNodeByAlias) throws Exception {

            String alias = flowNode.get("alias").asText();

            // Already exists — return it so execution can link to it
            AuthenticationFlowModel existing = realm.getFlowByAlias(alias);
            if (existing != null) {
                return existing;
            }

            AuthenticationFlowModel flowModel = new AuthenticationFlowModel();
            flowModel.setAlias(alias);
            flowModel.setDescription(flowNode.has("description") ? flowNode.get("description").asText() : "");
            flowModel.setProviderId(flowNode.has("providerId") ? flowNode.get("providerId").asText() : "basic-flow");
            flowModel.setBuiltIn(false);
            flowModel.setTopLevel(topLevel);

            AuthenticationFlowModel savedFlow = realm.addAuthenticationFlow(flowModel);

            if (flowNode.has("authenticationExecutions")) {
                int autoPriority = 10;
                for (JsonNode execNode : flowNode.get("authenticationExecutions")) {

                    boolean isSubFlow = execNode.has("authenticatorFlow")
                            && execNode.get("authenticatorFlow").asBoolean();

                    AuthenticationExecutionModel exec = new AuthenticationExecutionModel();
                    exec.setParentFlow(savedFlow.getId());
                    exec.setPriority(execNode.has("priority")
                            ? execNode.get("priority").asInt()
                            : autoPriority);
                    exec.setRequirement(AuthenticationExecutionModel.Requirement.valueOf(
                            execNode.get("requirement").asText()));
                    exec.setAuthenticatorFlow(isSubFlow);

                    if (isSubFlow) {
                        // Resolve sub-flow: prefer flowAlias ref into the sibling map,
                        // otherwise fall back to any inline authenticationExecutions.
                        String subAlias = execNode.has("flowAlias")
                                ? execNode.get("flowAlias").asText()
                                : alias + "-subflow-" + autoPriority;

                        // Look up full definition in the same wrapper's flow list
                        JsonNode subFlowDef = flowNodeByAlias.getOrDefault(subAlias, null);

                        JsonNode subFlowNode;
                        if (subFlowDef != null) {
                            subFlowNode = subFlowDef;
                        } else {
                            // Build a minimal node from what the execution has
                            ObjectNode built = mapper.createObjectNode()
                                    .put("alias", subAlias)
                                    .put("description", "")
                                    .put("providerId", "basic-flow")
                                    .put("topLevel", false)
                                    .put("builtIn", false);
                            if (execNode.has("authenticationExecutions")) {
                                built.set("authenticationExecutions",
                                        execNode.get("authenticationExecutions"));
                            }
                            subFlowNode = built;
                        }

                        AuthenticationFlowModel subFlow =
                                importFlowRecursive(realm, subFlowNode, false, configAliasToId, flowNodeByAlias);
                        exec.setFlowId(subFlow.getId());

                    } else {
                        if (execNode.has("authenticator")) {
                            exec.setAuthenticator(execNode.get("authenticator").asText());
                        }
                    }

                    // Link authenticator config by alias
                    if (execNode.has("authenticatorConfig")) {
                        String cfgAlias = execNode.get("authenticatorConfig").asText();
                        String cfgId = configAliasToId.get(cfgAlias);
                        if (cfgId != null) {
                            exec.setAuthenticatorConfig(cfgId);
                        }
                    }

                    realm.addAuthenticatorExecution(exec);
                    autoPriority += 10;
                }
            }

            return savedFlow;
        }

        /** Builds the full export wrapper object for a single top-level flow. */
        private ObjectNode buildFlowExportWrapper(RealmModel realm, AuthenticationFlowModel flow,
                                                   Set<String> configIds) {
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("alias", flow.getAlias());
            wrapper.put("description", flow.getDescription() != null ? flow.getDescription() : "");
            wrapper.put("providerId", flow.getProviderId());
            wrapper.put("topFlowOldAlias", flow.getAlias());

            ArrayNode flowsArray = mapper.createArrayNode();
            flowsArray.add(exportFlowRecursive(realm, flow, configIds));
            collectSubFlows(realm, flow, flowsArray, new HashSet<>(), configIds);
            wrapper.set("authenticationFlows", flowsArray);

            ArrayNode configsArray = mapper.createArrayNode();
            for (String configId : configIds) {
                AuthenticatorConfigModel config = realm.getAuthenticatorConfigById(configId);
                if (config != null) {
                    ObjectNode cfgNode = mapper.createObjectNode();
                    cfgNode.put("id", config.getId());
                    cfgNode.put("alias", config.getAlias());
                    ObjectNode cfgData = mapper.createObjectNode();
                    if (config.getConfig() != null) {
                        config.getConfig().forEach(cfgData::put);
                    }
                    cfgNode.set("config", cfgData);
                    configsArray.add(cfgNode);
                }
            }
            wrapper.set("authenticatorConfigs", configsArray);

            return wrapper;
        }

        private ObjectNode exportFlowRecursive(RealmModel realm, AuthenticationFlowModel flow,
                                               Set<String> configIds) {
            ObjectNode flowNode = mapper.createObjectNode();
            flowNode.put("id", flow.getId());
            flowNode.put("alias", flow.getAlias());
            flowNode.put("description", flow.getDescription() != null ? flow.getDescription() : "");
            flowNode.put("providerId", flow.getProviderId());
            flowNode.put("topLevel", flow.isTopLevel());
            flowNode.put("builtIn", flow.isBuiltIn());

            ArrayNode executionsArray = mapper.createArrayNode();

            realm.getAuthenticationExecutionsStream(flow.getId())
                    .sorted(Comparator.comparingInt(AuthenticationExecutionModel::getPriority))
                    .forEach(exec -> {
                        ObjectNode execNode = mapper.createObjectNode();

                        if (exec.getAuthenticatorConfig() != null) {
                            AuthenticatorConfigModel config =
                                    realm.getAuthenticatorConfigById(exec.getAuthenticatorConfig());
                            if (config != null) {
                                execNode.put("authenticatorConfig", config.getAlias());
                                configIds.add(config.getId());
                            }
                        }

                        if (exec.isAuthenticatorFlow()) {
                            execNode.put("authenticatorFlow", true);
                            AuthenticationFlowModel subFlow =
                                    realm.getAuthenticationFlowById(exec.getFlowId());
                            if (subFlow != null) {
                                execNode.put("flowAlias", subFlow.getAlias());
                            }
                        } else {
                            execNode.put("authenticator",
                                    exec.getAuthenticator() != null ? exec.getAuthenticator() : "");
                            execNode.put("authenticatorFlow", false);
                        }

                        execNode.put("requirement", exec.getRequirement().name());
                        execNode.put("priority", exec.getPriority());
                        execNode.put("autheticatorFlow", exec.isAuthenticatorFlow());
                        execNode.put("userSetupAllowed", false);

                        executionsArray.add(execNode);
                    });

            flowNode.set("authenticationExecutions", executionsArray);
            return flowNode;
        }

        private void collectSubFlows(RealmModel realm, AuthenticationFlowModel parentFlow,
                                     ArrayNode flowsArray, Set<String> visited,
                                     Set<String> configIds) {
            if (visited.contains(parentFlow.getId())) return;
            visited.add(parentFlow.getId());

            realm.getAuthenticationExecutionsStream(parentFlow.getId())
                    .filter(AuthenticationExecutionModel::isAuthenticatorFlow)
                    .forEach(exec -> {
                        AuthenticationFlowModel subFlow =
                                realm.getAuthenticationFlowById(exec.getFlowId());
                        if (subFlow != null && !visited.contains(subFlow.getId())) {
                            flowsArray.add(exportFlowRecursive(realm, subFlow, configIds));
                            collectSubFlows(realm, subFlow, flowsArray, visited, configIds);
                        }
                    });
        }
    }
}
