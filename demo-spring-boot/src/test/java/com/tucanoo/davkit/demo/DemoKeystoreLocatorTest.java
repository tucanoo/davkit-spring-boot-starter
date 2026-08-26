package com.tucanoo.davkit.demo;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests run with the module as working directory. The demo cert is generated locally with mkcert
 * (see README) and never committed, so the rewrite test only asserts when one is actually present.
 */
class DemoKeystoreLocatorTest {

    private final DemoKeystoreLocator locator = new DemoKeystoreLocator();

    @Test
    void rewritesAMissingRelativePathToTheDemoCert() {
        assumeTrue(Files.exists(Path.of("certs", "localhost.p12")),
                "no local mkcert keystore; see README");
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("test",
                Map.of(DemoKeystoreLocator.KEY_STORE, "file:../does-not-exist/localhost.p12")));
        locator.postProcessEnvironment(env, null);
        String rewritten = env.getProperty(DemoKeystoreLocator.KEY_STORE);
        assertThat(rewritten).startsWith("file:").endsWith("localhost.p12").contains("certs");
        assertThat(Files.exists(Path.of(rewritten.substring("file:".length())))).isTrue();
    }

    @Test
    void leavesAnExistingPathAlone() throws Exception {
        Path existing = Files.createTempFile("keystore", ".p12");
        try {
            MockEnvironment env = new MockEnvironment();
            env.getPropertySources().addLast(new MapPropertySource("test",
                    Map.of(DemoKeystoreLocator.KEY_STORE, "file:" + existing)));
            locator.postProcessEnvironment(env, null);
            assertThat(env.getProperty(DemoKeystoreLocator.KEY_STORE)).isEqualTo("file:" + existing);
        } finally {
            Files.delete(existing);
        }
    }

    @Test
    void doesNothingWhenNoKeystoreIsConfigured() {
        MockEnvironment env = new MockEnvironment();
        locator.postProcessEnvironment(env, null);
        assertThat(env.getProperty(DemoKeystoreLocator.KEY_STORE)).isNull();
    }
}
