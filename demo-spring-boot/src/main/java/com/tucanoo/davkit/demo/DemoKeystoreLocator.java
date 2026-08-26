package com.tucanoo.davkit.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The default keystore path ({@code demo-spring-boot/certs/localhost.p12}) is relative to the
 * working directory, which is the repo root under an IDE run configuration but the module dir
 * under {@code gradlew :demo-spring-boot:bootRun}. When the configured file doesn't exist, walk
 * up from the working directory looking for the cert under either spelling so the demo starts
 * from either. An explicit {@code DEMO_KEYSTORE} that resolves is left alone.
 */
public class DemoKeystoreLocator implements EnvironmentPostProcessor {

    static final String KEY_STORE = "server.ssl.key-store";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String configured = env.getProperty(KEY_STORE, "");
        String path = configured.startsWith("file:") ? configured.substring("file:".length()) : configured;
        if (path.isEmpty() || Files.exists(Path.of(path))) {
            return;
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            for (Path candidate : List.of(dir.resolve("certs").resolve("localhost.p12"),
                    dir.resolve("demo-spring-boot").resolve("certs").resolve("localhost.p12"))) {
                if (Files.exists(candidate)) {
                    env.getPropertySources().addFirst(new MapPropertySource("demoKeystoreLocator",
                            Map.of(KEY_STORE, "file:" + candidate)));
                    return;
                }
            }
        }
        // Found nowhere: leave the configured value so the original FileNotFoundException surfaces.
    }
}
