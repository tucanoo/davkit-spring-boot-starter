package com.tucanoo.davkit.boot;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves that the explicit opt-out leaves an existing host route untouched. */
@SpringBootTest(
        classes = DavKitExplicitlyDisabledIntegrationTest.TestApplication.class,
        properties = "davkit.enabled=false",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DavKitExplicitlyDisabledIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void existingHostRouteIsUntouchedWhenDavKitIsDisabled() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/hosting/status"))
                .GET()
                .build();

        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("host-ok");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        ServletRegistrationBean<HttpServlet> hostServlet() {
            HttpServlet servlet = new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response)
                        throws IOException {
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType("text/plain");
                    response.getWriter().write("host-ok");
                }
            };
            ServletRegistrationBean<HttpServlet> registration =
                    new ServletRegistrationBean<>(servlet, "/hosting/*");
            registration.setName("hostServlet");
            return registration;
        }
    }
}
