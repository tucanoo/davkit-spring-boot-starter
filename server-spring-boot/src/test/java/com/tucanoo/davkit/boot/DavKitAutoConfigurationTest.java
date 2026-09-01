package com.tucanoo.davkit.boot;

import com.tucanoo.davkit.auth.DavPrincipalResolver;
import com.tucanoo.davkit.auth.BasicAuthenticator;
import com.tucanoo.davkit.auth.DavAuthenticationFilter;
import com.tucanoo.davkit.auth.SignedUrlAuthenticator;
import com.tucanoo.davkit.auth.SignedUrlKeys;
import com.tucanoo.davkit.auth.SignedUrls;
import com.tucanoo.davkit.lock.InMemoryDavLockStore;
import com.tucanoo.davkit.lock.LockService;
import com.tucanoo.davkit.license.LicenseGate;
import com.tucanoo.davkit.license.TestLicenseGates;
import com.tucanoo.davkit.office.OfficeDiscoveryFilter;
import com.tucanoo.davkit.protocol.DavServlet;
import com.tucanoo.davkit.protocol.DavServletConfig;
import com.tucanoo.davkit.protocol.ProviderRegistry;
import com.tucanoo.davkit.spi.DavContent;
import com.tucanoo.davkit.spi.DavContext;
import com.tucanoo.davkit.spi.DavEventListener;
import com.tucanoo.davkit.spi.DavPath;
import com.tucanoo.davkit.spi.DavPermissions;
import com.tucanoo.davkit.spi.DavPrincipal;
import com.tucanoo.davkit.spi.DavResource;
import com.tucanoo.davkit.spi.DavResourceProvider;
import com.tucanoo.davkit.spi.DavWriteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import jakarta.servlet.Servlet;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DavKitAutoConfigurationTest {

    private final WebApplicationContextRunner baseRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DavKitAutoConfiguration.class));
    private final WebApplicationContextRunner runner = baseRunner;
    private final WebApplicationContextRunner disabledRunner = baseRunner
            .withPropertyValues("davkit.enabled=false");

    @Test
    void enabledByDefaultRegistersDavKitInfrastructure() {
        baseRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(DavServlet.class);
            assertThat(ctx).hasSingleBean(DavKitLicenseState.class);
        });
    }

    @Test
    void explicitlyDisabledDoesNotRegisterDavKitInfrastructure() {
        disabledRunner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(DavServlet.class);
            assertThat(ctx).doesNotHaveBean(DavKitLicenseState.class);
            assertThat(ctx).doesNotHaveBean("davOfficeDiscoveryFilter");
            assertThat(ctx).doesNotHaveBean(HttpFirewall.class);
        });
    }

    @Test
    void primaryHostLicenseGateDoesNotReplaceTheVerifiedDavKitGate() {
        runner.withBean("hostLicenseGate", LicenseGate.class,
                        () -> LicenseGate.unlicensed("host-supplied gate"), bean -> bean.setPrimary(true))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(DavServletConfig.class).licenseGate().startupProblem())
                            .contains("no licence key")
                            .doesNotContain("host-supplied");
                });
    }

    @Test
    void hostServletConfigCannotReplaceTheVerifiedGate() {
        runner.withBean("hostDavServletConfig", DavServletConfig.class,
                        DavKitAutoConfigurationTest::hostServletConfig, bean -> bean.setPrimary(true))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertUsesCoreGate(ctx.getBean(DavServlet.class));
                });
    }

    @Test
    void primaryHostServletCannotReplaceTheRegisteredDavKitServlet() {
        runner.withBean("hostDavServlet", DavServlet.class,
                        DavKitAutoConfigurationTest::hostServlet, bean -> bean.setPrimary(true))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    @SuppressWarnings("unchecked")
                    ServletRegistrationBean<DavServlet> registration =
                            (ServletRegistrationBean<DavServlet>) ctx.getBean("davServletRegistration");
                    assertUsesCoreGate(registration.getServlet());
                });
    }

    private static DavServletConfig hostServletConfig() {
        return new DavServletConfig("/webdav", Duration.ofMinutes(5), Duration.ofHours(1), true, 256 * 1024,
                LicenseGate.unlicensed("host-supplied gate"));
    }

    private static DavServlet hostServlet() {
        return new DavServlet(new ProviderRegistry(List.of()), new LockService(new InMemoryDavLockStore()),
                DavPrincipalResolver.anonymous(), List.of(), hostServletConfig());
    }

    private static void assertUsesCoreGate(DavServlet servlet) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/webdav/documents/example.docx");
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            ((Servlet) servlet).service(request, response);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("no licence key").doesNotContain("host-supplied");
    }

    /**
     * Adds a primary internal state built by test-source-only support. Tests select this
     * controlled state only for downstream wiring assertions; core tests cover signature
     * verification, expiry and update entitlement.
     */
    private WebApplicationContextRunner licensed() {
        return licensed("test-only-licence-material-for-derived-signing");
    }

    private WebApplicationContextRunner licensed(String rawKey) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("raw-test-licence", Map.of("davkit.license-key", rawKey))))
                .withBean("testLicenseState", DavKitLicenseState.class,
                () -> new DavKitLicenseState(TestLicenseGates.commercial("Starter tests")),
                bean -> bean.setPrimary(true));
    }

    private WebApplicationContextRunner licensedWithoutRawKey() {
        return runner.withBean("testLicenseState", DavKitLicenseState.class,
                () -> new DavKitLicenseState(TestLicenseGates.commercial("Starter tests")),
                bean -> bean.setPrimary(true));
    }

    @Test
    void registersServletAtDavPathAndDiscoveryFilterAheadOfSecurity() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DavServlet.class);

            @SuppressWarnings("unchecked")
            ServletRegistrationBean<DavServlet> servlet = ctx.getBean(ServletRegistrationBean.class);
            assertThat(servlet.getUrlMappings()).containsExactly("/webdav/*");

            @SuppressWarnings("unchecked")
            FilterRegistrationBean<OfficeDiscoveryFilter> filter =
                    (FilterRegistrationBean<OfficeDiscoveryFilter>) ctx.getBean("davOfficeDiscoveryFilter");
            assertThat(filter.getUrlPatterns()).containsExactly("/*");
            // One ahead of springSecurityFilterChain (-100).
            assertThat(filter.getOrder()).isEqualTo(-101);
        });
    }

    @Test
    void davPathPropertyDrivesServletMappingFilterAndServletConfig() {
        runner.withPropertyValues("davkit.path=/docs/").run(ctx -> {
            @SuppressWarnings("unchecked")
            ServletRegistrationBean<DavServlet> servlet = ctx.getBean(ServletRegistrationBean.class);
            assertThat(servlet.getUrlMappings()).containsExactly("/docs/*");
            assertThat(ctx.getBean(DavServletConfig.class).path()).isEqualTo("/docs");
        });
    }

    @Test
    void lockTimeoutPropertiesReachServletConfig() {
        runner.withPropertyValues("davkit.lock.default-timeout=2m", "davkit.lock.max-timeout=30m").run(ctx -> {
            DavServletConfig config = ctx.getBean(DavServletConfig.class);
            assertThat(config.defaultLockTimeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(config.maxLockTimeout()).isEqualTo(Duration.ofMinutes(30));
        });
    }

    @Test
    void discoveryFilterCanBeDisabled() {
        runner.withPropertyValues("davkit.office.discovery-filter=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("davOfficeDiscoveryFilter"));
    }

    @Test
    void collectsProviderBeansIntoRegistry() {
        runner.withUserConfiguration(TwoProviders.class).run(ctx -> {
            ProviderRegistry registry = ctx.getBean(ProviderRegistry.class);
            assertThat(registry.mountPointNames()).containsExactly("a", "b");
        });
    }

    @Test
    void hostSuppliedPrincipalResolverWins() {
        runner.withUserConfiguration(HostResolver.class).run(ctx -> {
            DavPrincipalResolver resolver = ctx.getBean(DavPrincipalResolver.class);
            assertThat(resolver.resolve(new MockHttpServletRequest()).id()).isEqualTo("host-user");
        });
    }

    @Test
    void firewallAllowsDavVerbs() {
        // The default StrictHttpFirewall rejects PROPFIND and LOCK outright.
        runner.run(ctx -> {
            HttpFirewall firewall = ctx.getBean(HttpFirewall.class);
            for (String method : new String[] {"PROPFIND", "PROPPATCH", "LOCK", "UNLOCK", "OPTIONS", "PUT"}) {
                MockHttpServletRequest request = new MockHttpServletRequest(method, "/webdav/docs/1.docx");
                firewall.getFirewalledRequest(request);
            }
            MockHttpServletRequest mkcol = new MockHttpServletRequest("MKCOL", "/webdav/docs/x");
            assertThatThrownBy(() -> firewall.getFirewalledRequest(mkcol))
                    .isInstanceOf(RequestRejectedException.class);
        });
    }

    @Test
    void hostSuppliedFirewallWins() {
        runner.withUserConfiguration(HostFirewall.class)
                .run(ctx -> assertThat(ctx.getBean(HttpFirewall.class)).isSameAs(HostFirewall.INSTANCE));
    }

    @Test
    void jdbcLockStoreWhenRequestedAndDataSourcePresent() {
        runner.withUserConfiguration(H2.class).withPropertyValues("davkit.lock.store=jdbc")
                .run(ctx -> assertThat(ctx.getBean(com.tucanoo.davkit.lock.DavLockStore.class))
                        .isInstanceOf(com.tucanoo.davkit.lock.JdbcDavLockStore.class));
        runner.withPropertyValues("davkit.lock.store=jdbc")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withUserConfiguration(H2.class)
                .run(ctx -> assertThat(ctx.getBean(com.tucanoo.davkit.lock.DavLockStore.class))
                        .isInstanceOf(com.tucanoo.davkit.lock.InMemoryDavLockStore.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class H2 {
        @Bean javax.sql.DataSource dataSource() {
            org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
            ds.setURL("jdbc:h2:mem:auto;DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            return ds;
        }
    }

    /**
     * A licence problem never fails the host's boot — the
     * context starts, the gate carries the problem, and the servlet answers 503. Even
     * configured authenticators stay unregistered so the 503 explanation is reachable.
     */
    @Test
    void noLicenceKeyBootsNormallyWithEndpointsDisabled() {
        runner.withPropertyValues("davkit.signed-url.keys.k1=0123456789abcdef0123456789abcdef").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LicenseGate gate = ctx.getBean(DavKitLicenseState.class).gate();
            assertThat(gate.startupProblem()).contains("no licence key");
            assertThat(ctx.getBean(DavServletConfig.class).licenseGate()).isSameAs(gate);
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<DavAuthenticationFilter> auth =
                    (FilterRegistrationBean<DavAuthenticationFilter>) ctx.getBean("davAuthenticationFilter");
            assertThat(auth.isEnabled()).as("no auth challenge in front of the 503 explanation").isFalse();
        });
    }

    @Test
    void invalidLicenceKeyBootsNormallyWithEndpointsDisabled() {
        runner.withPropertyValues("davkit.license-key=eyJ4IjoxfQ.AAAA").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(DavKitLicenseState.class).gate().startupProblem())
                    .contains("signature");
        });
    }

    @Test
    void commercialKeyOutOfSupportForThisBuildDisablesEndpoints() {
        // updatesUntil 2026-01-31; the build-info resource stamps this build well after that.
        runner.withPropertyValues("davkit.license-key=" + STALE_LICENCE).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(DavKitLicenseState.class).gate().startupProblem())
                    .contains("2026-01-31").contains("renew");
        });
    }

    /**
     * Commercial, updatesUntil 2026-01-31 — the only product-signed key in this repository, and
     * deliberately a worthless one: every build is dated after it, so it can only ever produce
     * the refusal this test asserts. A usable key must never be committed — keys
     * verify offline and cannot be withdrawn. Tests that need a *serving* gate call
     * {@link #licensed()} instead of carrying a key.
     */
    static final String STALE_LICENCE = "eyJsaWNlbnNlZSI6IlN0YWxlIEV4YW1wbGUgTHRkIiwidHlwZSI6ImNvbW1lcmNpYWwiLCJpc3N1ZWQiOiIyMDI1LTA2LTAxIiwidXBkYXRlc1VudGlsIjoiMjAyNi0wMS0zMSIsInNlcnZlcnMiOjF9.pANa-q9g_M2uJOtTWGWXRSuKeTk_0dm3BeVmWLfv61_qhEMoo2GvHUYySv0HQmn1pl9byW7kA_IP2la_e1vlBw";

    @Test
    void unlicensedHostKeepsAuthenticationFilterDisabled() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(SignedUrlKeys.class);
            assertThat(ctx).hasSingleBean(SignedUrls.class);
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<DavAuthenticationFilter> auth =
                    (FilterRegistrationBean<DavAuthenticationFilter>) ctx.getBean("davAuthenticationFilter");
            assertThat(auth.isEnabled()).as("no auth challenge in front of the licence 503").isFalse();
        });
    }

    @Test
    void requiredAuthenticationKeepsFilterEnabledForUnsignedRequests() {
        licensedWithoutRawKey().run(ctx -> {
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<DavAuthenticationFilter> auth =
                    (FilterRegistrationBean<DavAuthenticationFilter>) ctx.getBean("davAuthenticationFilter");
            assertThat(auth.isEnabled())
                    .as("required authentication must fail closed when no authenticator is configured")
                    .isTrue();
        });
    }

    @Test
    void equivalentWhitespacePaddedLicenceDerivesSameSigningKey() {
        AtomicReference<String> signedPath = new AtomicReference<>();
        licensed().run(ctx -> signedPath.set(
                ctx.getBean(SignedUrls.class).path("alice", "documents/report.docx")));

        licensed("  test-only-licence-material-for-derived-signing  ").run(ctx -> {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", signedPath.get());
            var outcome = new SignedUrlAuthenticator("/webdav", ctx.getBean(SignedUrlKeys.class), Clock.systemUTC())
                    .authenticate(request);

            assertThat(outcome.succeeded()).isTrue();
            assertThat(outcome.principal().orElseThrow().id()).isEqualTo("alice");
            assertThat(ctx.getBean(SignedUrls.class).ttl()).isEqualTo(Duration.ofHours(8));
        });
    }

    @Test
    void differentLicenceCannotVerifyDerivedSignedUrl() {
        AtomicReference<String> signedPath = new AtomicReference<>();
        licensed("first-test-licence-material").run(ctx -> signedPath.set(
                ctx.getBean(SignedUrls.class).path("alice", "documents/report.docx")));

        licensed("second-test-licence-material").run(ctx -> {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", signedPath.get());
            var outcome = new SignedUrlAuthenticator("/webdav", ctx.getBean(SignedUrlKeys.class), Clock.systemUTC())
                    .authenticate(request);

            assertThat(outcome.rejected()).isTrue();
        });
    }

    @Test
    void hostSuppliedPrincipalResolverAuthenticatesRequiredRequest() {
        licensed().withUserConfiguration(StatefulHostAuthentication.class).run(ctx -> {
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<DavAuthenticationFilter> registration =
                    (FilterRegistrationBean<DavAuthenticationFilter>) ctx.getBean("davAuthenticationFilter");
            DavServlet servlet = ctx.getBean(DavServlet.class);
            StatefulHostIdentity identity = ctx.getBean(StatefulHostIdentity.class);
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "GET", "/webdav/documents/example.docx");
            MockHttpServletResponse response = new MockHttpServletResponse();

            registration.getFilter().doFilter(request, response, servlet::service);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("content");
            assertThat(identity.resolveCalls).hasValue(1);
            assertThat(identity.principalSeen.get()).isNotNull();
            assertThat(identity.principalSeen.get().id()).isEqualTo("host-user");
        });
    }

    @Test
    void expiredEvaluationKeepsAuthenticationFilterDisabledForLicenceRefusal() {
        LicenseGate expired = TestLicenseGates.evaluation("Expired evaluation", LocalDate.of(2026, 8, 27),
                Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC));

        runner.withBean("testLicenseState", DavKitLicenseState.class,
                        () -> new DavKitLicenseState(expired), bean -> bean.setPrimary(true))
                .withPropertyValues("davkit.auth.basic.enabled=true")
                .run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(expired.refusal()).isPresent();
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<DavAuthenticationFilter> auth =
                    (FilterRegistrationBean<DavAuthenticationFilter>) ctx.getBean("davAuthenticationFilter");
            assertThat(auth.isEnabled()).as("the servlet must expose the licence 503").isFalse();
        });
    }

    @Test
    void optionalAuthenticationKeepsSignedUrlFilterEnabled() {
        licensed().withPropertyValues("davkit.auth.required=false").run(ctx -> {
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<DavAuthenticationFilter> auth =
                    (FilterRegistrationBean<DavAuthenticationFilter>) ctx.getBean("davAuthenticationFilter");
            assertThat(auth.isEnabled())
                    .as("signed URLs must still establish their embedded principal")
                    .isTrue();
        });
    }

    @Test
    void missingCollaboratorBeanIsStillAHardStartupFailure() {
        // Licensed but no CredentialsVerifier bean: a wiring bug in the host, not a licensing state.
        licensed().withPropertyValues("davkit.auth.basic.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void basicEnabledWithLicenceAndVerifier() {
        licensed().withUserConfiguration(Verifier.class)
                .withPropertyValues("davkit.auth.basic.enabled=true")
                .run(ctx -> {
                    LicenseGate gate = ctx.getBean(DavKitLicenseState.class).gate();
                    assertThat(gate.startupProblem()).isNull();
                    assertThat(gate.license().licensee()).isEqualTo("Starter tests");
                    @SuppressWarnings("unchecked")
                    FilterRegistrationBean<DavAuthenticationFilter> auth =
                            (FilterRegistrationBean<DavAuthenticationFilter>) ctx.getBean("davAuthenticationFilter");
                    assertThat(auth.isEnabled()).isTrue();
                    assertThat(auth.getUrlPatterns()).containsExactly("/webdav/*");
                    assertThat(auth.getOrder()).isEqualTo(-99);
                });
    }

    @Test
    void ofbaEnabledGetsSessionResolverAndReturnPage() {
        licensed().withPropertyValues("davkit.auth.ofba.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(com.tucanoo.davkit.auth.OfbaSessionResolver.class);
                    ServletRegistrationBean<?> ret = (ServletRegistrationBean<?>) ctx.getBean("davOfbaReturnPage");
                    assertThat(ret.getUrlMappings()).containsExactly("/davkit/ofba/done");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Verifier {
        @Bean BasicAuthenticator.CredentialsVerifier credentialsVerifier() {
            return (user, password) -> java.util.Optional.empty();
        }
    }

    @Test
    void configuredSigningKeysOverrideLicenceDerivedKey() {
        licensed().withPropertyValues(
                        "davkit.signed-url.keys.k1=0123456789abcdef0123456789abcdef",
                        "davkit.signed-url.keys.k2=fedcba9876543210fedcba9876543210",
                        "davkit.signed-url.active-key=k2",
                        "davkit.signed-url.ttl=2h")
                .run(ctx -> {
                    assertThat(ctx.getBean(SignedUrlKeys.class).activeKeyId()).isEqualTo("k2");
                    SignedUrls urls = ctx.getBean(SignedUrls.class);
                    assertThat(urls.ttl()).isEqualTo(java.time.Duration.ofHours(2));
                    assertThat(urls.path("dave", "documents/a b.docx")).startsWith("/webdav/t/").endsWith("/documents/a%20b.docx");

                    @SuppressWarnings("unchecked")
                    FilterRegistrationBean<DavAuthenticationFilter> filter =
                            (FilterRegistrationBean<DavAuthenticationFilter>) ctx.getBean("davAuthenticationFilter");
                    assertThat(filter.isEnabled()).isTrue();
                    assertThat(filter.getUrlPatterns()).containsExactly("/webdav/*");
                    assertThat(filter.getOrder()).isEqualTo(-99);
                });
    }

    @Test
    void noFirewallBeanWithoutSpringSecurity() {
        runner.withClassLoader(new FilteredClassLoader(StrictHttpFirewall.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DavServlet.class);
                    assertThat(ctx).doesNotHaveBean("davHttpFirewall");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoProviders {
        @Bean DavResourceProvider a() { return new StubProvider("a"); }
        @Bean DavResourceProvider b() { return new StubProvider("b"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostResolver {
        @Bean DavPrincipalResolver hostResolver() {
            return request -> new DavPrincipal("host-user", "Host User", java.util.Map.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StatefulHostAuthentication {
        @Bean StatefulHostIdentity hostIdentity() { return new StatefulHostIdentity(); }
        @Bean DavResourceProvider documentProvider() { return new SingleDocumentProvider(); }
    }

    static final class StatefulHostIdentity implements DavPrincipalResolver, DavEventListener {
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final AtomicReference<DavPrincipal> principalSeen = new AtomicReference<>();

        @Override
        public DavPrincipal resolve(jakarta.servlet.http.HttpServletRequest request) {
            return resolveCalls.incrementAndGet() == 1
                    ? new DavPrincipal("host-user", "Host User", java.util.Map.of())
                    : DavPrincipal.ANONYMOUS;
        }

        @Override
        public void afterRead(DavResource resource, DavContext ctx) {
            principalSeen.set(ctx.principal());
        }
    }

    private record SingleDocumentProvider() implements DavResourceProvider {
        @Override public String mountPoint() { return "documents"; }
        @Override public Optional<DavResource> resolve(DavPath path, DavContext ctx) {
            return Optional.of(new DavResource("example", path, "example.docx", false,
                    "application/octet-stream", 7, "v1", Instant.parse("2026-08-28T12:00:00Z"),
                    Optional.empty(), DavPermissions.READ_ONLY));
        }
        @Override public DavContent read(DavResource resource, DavContext ctx) {
            return DavContent.of("content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        @Override public DavResource write(DavResource resource, DavWriteRequest request, DavContext ctx) {
            throw new UnsupportedOperationException();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HostFirewall {
        static final HttpFirewall INSTANCE = new StrictHttpFirewall();
        @Bean HttpFirewall hostFirewall() { return INSTANCE; }
    }

    /** Smallest possible provider: enough to be registered, never asked for anything. */
    private record StubProvider(String mountPoint) implements DavResourceProvider {
        @Override public Optional<DavResource> resolve(DavPath path, DavContext ctx) { return Optional.empty(); }
        @Override public DavContent read(DavResource resource, DavContext ctx) { throw new UnsupportedOperationException(); }
        @Override public DavResource write(DavResource resource, DavWriteRequest request, DavContext ctx) { throw new UnsupportedOperationException(); }
    }
}
