package com.example;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class FlowUploadProviderTest {

    @Test
    void getResourceReturnsFlowUploadResource() {
        FlowUploadProvider provider = new FlowUploadProvider(mock(KeycloakSession.class));

        Object resource = provider.getResource();

        assertNotNull(resource);
        assertInstanceOf(FlowUploadProvider.FlowUploadResource.class, resource);
    }

    @Test
    void closeDoesNotThrow() {
        FlowUploadProvider provider = new FlowUploadProvider(mock(KeycloakSession.class));
        provider.close();
    }
}
