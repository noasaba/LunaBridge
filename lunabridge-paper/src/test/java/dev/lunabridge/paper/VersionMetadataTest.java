package dev.lunabridge.paper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionMetadataTest {
    @Test void paperMetadataUsesTheGradleProductAndApiVersions() throws Exception {
        try (InputStream resource = VersionMetadataTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(resource);
            String metadata = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("version: " + System.getProperty("lunabridge.projectVersion")));
            assertTrue(metadata.contains("api-version: '26.2'"));
            assertTrue(metadata.contains("lunabridge:"));
            assertTrue(metadata.contains("permission: lunabridge.admin"));
        }
    }
}
