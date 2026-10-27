package com.tucanoo.davkit.demo;

import com.tucanoo.davkit.boot.TestLicenseConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
import java.net.CookieManager;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RouterFunctions.route;

/** Exercises the discovery filter before the real Spring Security chain and embedded Tomcat. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestLicenseConfiguration.class, OfficeDiscoveryApplicationTest.ImageConfiguration.class})
class OfficeDiscoveryApplicationTest {

    private static final String OUTLOOK_UA =
            "Microsoft Office/16.0 (Windows NT 10.0; Microsoft Outlook 16.0.17928; Pro)";
    private static final byte[] IMAGE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aOZkAAAAASUVORK5CYII=");

    @LocalServerPort int port;

    // Keep the login session, but do not follow redirects: security outcomes remain observable.
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    private HttpResponse<byte[]> send(String method, String path, String userAgent) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("User-Agent", userAgent)
                .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {OUTLOOK_UA, "Microsoft Office Word 2014 (16.0.18324)", "Mozilla/4.0 (ms-office)"})
    void officeCanFetchPublicImagesAndApplicationPages(String userAgent) throws Exception {
        HttpResponse<byte[]> image = send("GET", "/test-images/mail.png", userAgent);
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(image.body()).isEqualTo(IMAGE);

        HttpResponse<byte[]> page = send("GET", "/", userAgent);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(new String(page.body(), StandardCharsets.UTF_8)).contains("ms-word:ofe|u|");

        HttpResponse<byte[]> login = send("GET", "/login", userAgent);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(new String(login.body(), StandardCharsets.UTF_8)).contains("name=\"_csrf\"");
    }

    @Test
    void officeApplicationTrafficStillUsesHostAuthenticationAndCsrfProtection() throws Exception {
        HttpResponse<byte[]> protectedPage = send("GET", "/davkit/ofba/start", OUTLOOK_UA);
        assertThat(protectedPage.statusCode()).isEqualTo(302);
        assertThat(protectedPage.headers().firstValue("Location").orElseThrow()).endsWith("/login");

        // Authenticate before checking CSRF: anonymous error dispatches can redirect to login.
        String loginPage = new String(send("GET", "/login", OUTLOOK_UA).body(), StandardCharsets.UTF_8);
        var csrf = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
                .matcher(loginPage);
        assertThat(csrf.find()).isTrue();
        String form = "username=dave&password=password&_csrf="
                + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
        HttpResponse<byte[]> authenticated = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/login"))
                .header("User-Agent", OUTLOOK_UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(authenticated.statusCode()).isEqualTo(302);
        assertThat(send("GET", "/davkit/ofba/start", OUTLOOK_UA)
                .headers().firstValue("Location").orElseThrow()).endsWith("/davkit/ofba/done");

        HttpResponse<byte[]> loginWithoutCsrf = send("POST", "/login", OUTLOOK_UA);
        assertThat(loginWithoutCsrf.statusCode()).isEqualTo(403);
    }

    @Test
    void discoveryStillTerminatesBeforeHostSecurity() throws Exception {
        for (String method : new String[]{"OPTIONS", "HEAD", "PROPFIND"}) {
            HttpResponse<byte[]> root = send(method, "/", OUTLOOK_UA);
            assertThat(root.statusCode()).as(method).isEqualTo(method.equals("PROPFIND") ? 207 : 200);
            assertThat(root.headers().firstValue("DAV")).as(method).contains("1, 2");

            HttpResponse<byte[]> nonRoot = send(method, "/davkit/ofba/start", OUTLOOK_UA);
            assertThat(nonRoot.statusCode()).as(method).isEqualTo(404);
            assertThat(nonRoot.body()).as(method).isEmpty();
            assertThat(nonRoot.headers().firstValue("Location")).as(method).isEmpty();
        }

        assertThat(send("GET", "/_vti_inf.html", OUTLOOK_UA).statusCode()).isEqualTo(404);
        HttpResponse<byte[]> sharing = send("POST", "/_api/web/GetSharingInformation", OUTLOOK_UA);
        assertThat(sharing.statusCode()).isEqualTo(200);
        assertThat(new String(sharing.body(), StandardCharsets.UTF_8)).contains("\"HasEditPermission\":true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ImageConfiguration {
        @Bean
        @Order(-1)
        SecurityFilterChain imageSecurityChain(HttpSecurity http) throws Exception {
            http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher("/test-images/**"))
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        @Bean
        RouterFunction<ServerResponse> imageRoute() {
            return route(GET("/test-images/mail.png"),
                    request -> ServerResponse.ok().contentType(MediaType.IMAGE_PNG).body(IMAGE));
        }
    }
}
