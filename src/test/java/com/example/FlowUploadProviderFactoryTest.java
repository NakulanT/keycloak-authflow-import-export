package com.example;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class FlowUploadProviderFactoryTest {

    @Test
    void createReturnsFlowUploadProvider() {
        FlowUploadProviderFactory factory = new FlowUploadProviderFactory();
        KeycloakSession session = mock(KeycloakSession.class);

        RealmResourceProvider provider = factory.create(session);

        assertNotNull(provider);
        assertInstanceOf(FlowUploadProvider.class, provider);
    }

    @Test
    void lifecycleMethodsAndIdBehaveAsExpected() {
        FlowUploadProviderFactory factory = new FlowUploadProviderFactory();

        factory.init(null);
        factory.postInit(null);
        factory.close();

        assertEquals(FlowUploadProviderFactory.ID, factory.getId());
    }
}
