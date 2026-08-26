package com.tucanoo.davkit.boot;

import com.tucanoo.davkit.auth.DavPrincipalResolver;
import com.tucanoo.davkit.auth.BasicAuthenticator;
import com.tucanoo.davkit.auth.DavAuthenticationFilter;
import com.tucanoo.davkit.auth.SignedUrlKeys;
import com.tucanoo.davkit.auth.SignedUrls;
import com.tucanoo.davkit.license.License;
import com.tucanoo.davkit.license.LicenseGate;
import com.tucanoo.davkit.office.OfficeDiscoveryFilter;
import com.tucanoo.davkit.protocol.DavServlet;
import com.tucanoo.davkit.protocol.DavServletConfig;
import com.tucanoo.davkit.protocol.ProviderRegistry;
import com.tucanoo.davkit.spi.DavContent;
import com.tucanoo.davkit.spi.DavContext;
import com.tucanoo.davkit.spi.DavPath;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DavKitAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DavKitAutoConfiguration.class));

    /**
     * The runner with a valid licence supplied as a bean rather than a key —
     * {@code davLicenseGate} is {@code @ConditionalOnMissingBean}, so this replaces startup
     * verification. Verification itself is covered by {@code LicenseVerifierTest} (its own key
     * pair) and, end to end through {@code buildGate}, by {@link #STALE_LICENCE}.
     */
    private WebApplicationContextRunner licensed() {
        return runner.withBean(LicenseGate.class, () -> LicenseGate.of(
                new License("Starter tests", License.Type.COMMERCIAL,
                        LocalDate.of(2026, 1, 1), null, null, 1, List.of()),
                null, Clock.systemUTC()));
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
            // Q7: one ahead of springSecurityFilterChain (-100).
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
        // Q8: the default StrictHttpFirewall rejects PROPFIND and LOCK outright.
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
     * licensing.md "Runtime behaviour": a licence problem never fails the host's boot — the
     * context starts, the gate carries the problem, and the servlet answers 503. Even
     * configured authenticators stay unregistered so the 503 explanation is reachable.
     */
    @Test
    void noLicenceKeyBootsNormallyWithEndpointsDisabled() {
        runner.withPropertyValues("davkit.signed-url.keys.k1=0123456789abcdef0123456789abcdef").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LicenseGate gate = ctx.getBean(LicenseGate.class);
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
            assertThat(ctx.getBean(LicenseGate.class).startupProblem())
                    .contains("signature");
        });
    }

    @Test
    void commercialKeyOutOfSupportForThisBuildDisablesEndpoints() {
        // updatesUntil 2026-01-31; the build-info resource stamps this build well after that.
        runner.withPropertyValues("davkit.license-key=" + STALE_LICENCE).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(LicenseGate.class).startupProblem())
                    .contains("2026-01-31").contains("renew");
        });
    }

    /**
     * Commercial, updatesUntil 2026-01-31 — the only product-signed key in this repository, and
     * deliberately a worthless one: every build is dated after it, so it can only ever produce
     * the refusal this test asserts. A usable key must never be committed (licensing.md) — keys
     * verify offline and cannot be withdrawn. Tests that need a *serving* gate call
     * {@link #licensed()} instead of carrying a key.
     */
    static final String STALE_LICENCE = "eyJsaWNlbnNlZSI6IlN0YWxlIEV4YW1wbGUgTHRkIiwidHlwZSI6ImNvbW1lcmNpYWwiLCJpc3N1ZWQiOiIyMDI1LTA2LTAxIiwidXBkYXRlc1VudGlsIjoiMjAyNi0wMS0zMSIsInNlcnZlcnMiOjF9.pANa-q9g_M2uJOtTWGWXRSuKeTk_0dm3BeVmWLfv61_qhEMoo2GvHUYySv0HQmn1pl9byW7kA_IP2la_e1vlBw";

    @Test
    void signedUrlsOffByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(SignedUrlKeys.class);
            assertThat(ctx).doesNotHaveBean(SignedUrls.class);
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<DavAuthenticationFilter> auth =
                    (FilterRegistrationBean<DavAuthenticationFilter>) ctx.getBean("davAuthenticationFilter");
            assertThat(auth.isEnabled()).as("no authenticators, filter disabled").isFalse();
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
                    com.tucanoo.davkit.license.LicenseGate gate =
                            ctx.getBean(LicenseGate.class);
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
    void signedUrlsOnWhenAKeyIsConfigured() {
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
