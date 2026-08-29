package com.tucanoo.davkit.boot;

import com.tucanoo.davkit.license.TestLicenseGates;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the registered filter through a real servlet container, not a mock filter chain. */
@SpringBootTest(
        classes = DavKitFailClosedAuthenticationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DavKitFailClosedAuthenticationTest {

    @LocalServerPort
    int port;

    @Test
    void requiredAuthenticationWithoutAuthenticatorsReturnsForbidden() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/webdav/documents/example.docx"))
                .GET()
                .build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.headers().firstValue("X-FORMS_BASED_AUTH_REQUIRED")).isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        @Primary
        DavKitLicenseState testLicenseState() {
            return new DavKitLicenseState(TestLicenseGates.commercial("Fail-closed integration test"));
        }
    }
}
