package com.tucanoo.davkit.boot;

import com.tucanoo.davkit.auth.BasicAuthenticator;
import com.tucanoo.davkit.auth.DavAuthenticationFilter;
import com.tucanoo.davkit.auth.DavAuthenticator;
import com.tucanoo.davkit.auth.DavPrincipalResolver;
import com.tucanoo.davkit.auth.OfbaAuthenticator;
import com.tucanoo.davkit.auth.OfbaSessionResolver;
import com.tucanoo.davkit.auth.SignedUrlAuthenticator;
import com.tucanoo.davkit.auth.SignedUrlKeys;
import com.tucanoo.davkit.auth.SignedUrls;
import com.tucanoo.davkit.lock.DavLockStore;
import com.tucanoo.davkit.lock.InMemoryDavLockStore;
import com.tucanoo.davkit.license.LicenseGate;
import com.tucanoo.davkit.lock.JdbcDavLockStore;
import com.tucanoo.davkit.lock.LockService;
import com.tucanoo.davkit.office.OfficeDiscoveryFilter;
import com.tucanoo.davkit.protocol.DavServlet;
import com.tucanoo.davkit.protocol.DavServletConfig;
import com.tucanoo.davkit.protocol.ProviderRegistry;
import com.tucanoo.davkit.spi.DavEventListener;
import com.tucanoo.davkit.spi.DavPrincipal;
import com.tucanoo.davkit.spi.DavResourceProvider;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Wires the framework-free core into a Spring Boot application. Everything here is plain
 * constructor plumbing — the core takes its configuration as arguments precisely so that this
 * class stays mechanical. Uses only the Boot classes that are stable across Boot 3 and 4.
 *
 * <p>Security-chain split: the discovery filter is registered at order -101, one ahead of
 * Spring Security's filter (-100), so it answers Office's root probes before any redirect-to-login
 * can fire. The servlet under {@code davkit.path} must additionally sit outside the host's
 * security chain, or in a chain of its own without CSRF; that part cannot be done for the host
 * without knowing its chain, so it is documented, not assumed. See "Wiring the starter into a
 * host" in this repository's README.</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "davkit", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DavKitProperties.class)
public class DavKitAutoConfiguration {

    private static final Log LOG = LogFactory.getLog(DavKitAutoConfiguration.class);

    /** One ahead of Spring Security's {@code springSecurityFilterChain} (-100). */
    static final int DISCOVERY_FILTER_ORDER = -101;
    /** After the discovery filter, on {@code davkit.path} only. */
    static final int AUTH_FILTER_ORDER = -99;

    /**
     * Verified once at startup. A licence problem — no key,
     * bad signature, expired evaluation, or out-of-support build — NEVER fails the context: the
     * host boots, the log gets one ERROR, and the DavKit endpoints answer 503 with the
     * explanation. A build or a developer machine without a key file must not fail, and
     * neither may a production host over licensing. Every valid key grants the whole library —
     * there is no per-feature gating.
    */
    @Bean
    DavKitLicenseState davKitLicenseState(DavKitProperties properties) {
        LicenseGate gate = buildGate(properties);
        if (gate.startupProblem() != null) {
            LOG.error(gate.summary());
        } else if (gate.refusal().isPresent()) {
            LOG.error("DavKit is not licensed: " + gate.refusal().orElseThrow()
                    + ". The host application is unaffected; DavKit endpoints return 503.");
        } else if (gate.license().evaluation()) {
            LOG.warn(gate.summary()); // the evaluation banner
        } else {
            LOG.info(gate.summary());
        }
        return new DavKitLicenseState(gate);
    }

    private static LicenseGate buildGate(DavKitProperties properties) {
        return LicenseGate.fromKey(properties.getLicenseKey());
    }

    @Bean
    @ConditionalOnMissingBean
    DavLockStore davLockStore(DavKitProperties properties, ObjectProvider<DataSource> dataSource) {
        DavKitProperties.Lock lock = properties.getLock();
        if ("jdbc".equalsIgnoreCase(lock.getStore())) {
            DataSource ds = dataSource.getIfAvailable();
            if (ds == null) {
                throw new IllegalStateException("davkit.lock.store=jdbc requires a DataSource bean");
            }
            JdbcDavLockStore store = new JdbcDavLockStore(ds, lock.getTable(), Clock.systemUTC());
            return lock.isCreateTable() ? store.ensureSchema() : store;
        }
        if (ClassUtils.isPresent("org.springframework.session.Session", null)) {
            LOG.warn("DavKit is using the in-memory lock store but Spring Session is on the classpath. "
                    + "If this application runs on more than one node, locks will not be shared and "
                    + "Office will see stale or missing locks; set davkit.lock.store=jdbc or provide a shared DavLockStore bean.");
        }
        return new InMemoryDavLockStore();
    }

    @Bean
    @ConditionalOnMissingBean
    LockService davLockService(DavLockStore store, DavKitProperties properties) {
        DavKitProperties.Lock lock = properties.getLock();
        return new LockService(store, lock.getDefaultTimeout(), lock.getMaxTimeout(), Clock.systemUTC());
    }

    /** Collects every {@link DavResourceProvider} bean; ordering follows bean definition order. */
    @Bean
    @ConditionalOnMissingBean
    ProviderRegistry davProviderRegistry(ObjectProvider<DavResourceProvider> providers) {
        List<DavResourceProvider> list = providers.orderedStream().toList();
        if (list.isEmpty()) {
            LOG.warn("DavKit: no DavResourceProvider beans found; the WebDAV endpoint will serve an empty root.");
        }
        return new ProviderRegistry(list);
    }

    /**
     * Principals established by DavKit's own authenticators win; unmatched requests are anonymous
     * only when authentication is not {@code required}.
     */
    @Bean
    @ConditionalOnMissingBean
    DavPrincipalResolver davPrincipalResolver(DavKitProperties properties, DavKitLicenseState licenseState) {
        LicenseGate licenseGate = licenseState.gate();
        if (licenseGate.refusal().isEmpty()) {
            if (!properties.getAuth().isRequired()) {
                LOG.warn("DavKit will treat requests without a DavKit-authenticated principal as anonymous: "
                        + "every document is reachable by anyone who can reach this server. Suitable for local "
                        + "development only; set davkit.auth.required=true "
                        + "or provide a DavPrincipalResolver bean before exposing this host.");
            }
        }
        return DavPrincipalResolver.fromRequestAttribute(DavPrincipalResolver.anonymous());
    }

    /**
     * DavKit's authentication chain: signed URLs,
     * then OFBA, then Basic, then the host's principal resolver; first success wins, then
     * challenges in the same order. When the gate is refused at startup the registration is
     * disabled, so requests fall through to the servlet's 503 explanation. (An evaluation key
     * that expires while the process runs keeps its authenticators; an authenticated request then
     * still gets the servlet's 503, which explains the expiry rather than failing opaquely.)
     * Missing collaborator beans for an enabled feature remain hard startup failures — that is a
     * wiring bug in the host, not a licensing state.
     */
    @Bean
    FilterRegistrationBean<DavAuthenticationFilter> davAuthenticationFilter(
            DavKitProperties properties,
            DavKitLicenseState licenseState,
            ObjectProvider<SignedUrlKeys> signedUrlKeys,
            ObjectProvider<OfbaSessionResolver> ofbaSessionResolver,
            ObjectProvider<BasicAuthenticator.CredentialsVerifier> credentialsVerifier,
            DavPrincipalResolver principalResolver) {
        LicenseGate licenseGate = licenseState.gate();
        List<DavAuthenticator> authenticators = new ArrayList<>();
        DavKitProperties.Auth auth = properties.getAuth();
        if (licenseGate.refusal().isEmpty()) {
            authenticators.add(new SignedUrlAuthenticator(davPath(properties),
                    signedUrlKeys.getObject(), Clock.systemUTC()));
            if (auth.getOfba().isEnabled()) {
                OfbaSessionResolver session = ofbaSessionResolver.getIfAvailable();
                if (session == null) {
                    throw new IllegalStateException("davkit.auth.ofba.enabled requires an OfbaSessionResolver bean "
                            + "(provided automatically when Spring Security is on the classpath)");
                }
                authenticators.add(new OfbaAuthenticator(session::resolve,
                        auth.getOfba().getLoginUrl(), auth.getOfba().getReturnUrl(), auth.getOfba().getDialogSize()));
            }
            if (auth.getBasic().isEnabled()) {
                BasicAuthenticator.CredentialsVerifier verifier = credentialsVerifier.getIfAvailable();
                if (verifier == null) {
                    throw new IllegalStateException("davkit.auth.basic.enabled requires a "
                            + "BasicAuthenticator.CredentialsVerifier bean to check credentials against");
                }
                authenticators.add(new BasicAuthenticator(auth.getBasic().getRealm(), verifier,
                        auth.getBasic().isAllowInsecure()));
            }
        }
        boolean hasConfiguredAuthenticator = !authenticators.isEmpty();
        if (auth.isRequired()) {
            // A host resolver is a supported authentication boundary. Run it last so DavKit's
            // explicit credentials win, while anonymous/null resolver outcomes still fail closed.
            authenticators.add(request -> {
                DavPrincipal principal = principalResolver.resolve(request);
                return principal == null || principal.isAnonymous()
                        ? DavAuthenticator.Outcome.notApplicable()
                        : DavAuthenticator.Outcome.success(principal, request);
            });
        }
        DavAuthenticationFilter filter =
                new DavAuthenticationFilter(davPath(properties), authenticators, auth.isRequired());
        FilterRegistrationBean<DavAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("davkitAuth");
        registration.addUrlPatterns(davPath(properties) + "/*");
        registration.setOrder(AUTH_FILTER_ORDER);
        registration.setEnabled(licenseGate.refusal().isEmpty()
                && (auth.isRequired() || hasConfiguredAuthenticator));
        return registration;
    }

    /** Spring Security session adapter for OFBA (read directly, the chain never ran here). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(org.springframework.security.web.context.HttpSessionSecurityContextRepository.class)
    @ConditionalOnProperty(prefix = "davkit.auth.ofba", name = "enabled", havingValue = "true")
    static class OfbaSessionConfiguration {
        @Bean
        @ConditionalOnMissingBean(OfbaSessionResolver.class)
        OfbaSessionResolver davOfbaSessionResolver() {
            return new SpringSecuritySessionResolver();
        }
    }

    /**
     * The OFBA start page: protected by the host's security chain, so an unauthenticated dialog
     * is sent through the host login flow and comes back here (saved request), and only then is
     * forwarded to the return URL. Office closes its dialog on the first navigation to the
     * return URL, so the return URL must never be reachable before authentication — which is why
     * the handshake points here and not at the login page or the return page.
     */
    @Bean
    @ConditionalOnProperty(prefix = "davkit.auth.ofba", name = "enabled", havingValue = "true")
    ServletRegistrationBean<jakarta.servlet.http.HttpServlet> davOfbaStartPage(DavKitProperties properties) {
        String returnUrl = properties.getAuth().getOfba().getReturnUrl();
        jakarta.servlet.http.HttpServlet servlet = new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest request,
                                 jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
                response.sendRedirect(returnUrl);
            }
        };
        ServletRegistrationBean<jakarta.servlet.http.HttpServlet> registration =
                new ServletRegistrationBean<>(servlet, "/davkit/ofba/start");
        registration.setName("davkitOfbaStart");
        return registration;
    }

    /** The page Office's login dialog lands on; reaching it means the host's login succeeded. */
    @Bean
    @ConditionalOnProperty(prefix = "davkit.auth.ofba", name = "enabled", havingValue = "true")
    ServletRegistrationBean<jakarta.servlet.http.HttpServlet> davOfbaReturnPage(DavKitProperties properties) {
        String returnUrl = properties.getAuth().getOfba().getReturnUrl();
        jakarta.servlet.http.HttpServlet servlet = new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest request,
                                 jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
                response.setStatus(200);
                response.setContentType("text/html;charset=UTF-8");
                response.getWriter().write("<!doctype html><title>Signed in</title>"
                        + "<p>Signed in - you can return to Office.</p>");
            }
        };
        ServletRegistrationBean<jakarta.servlet.http.HttpServlet> registration =
                new ServletRegistrationBean<>(servlet, returnUrl);
        registration.setName("davkitOfbaReturn");
        registration.setEnabled(returnUrl.startsWith("/") && !returnUrl.contains("://"));
        return registration;
    }

    /** Signed URLs: licence-derived by default, with optional configured keys. */
    @Configuration(proxyBeanMethods = false)
    static class SignedUrlConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SignedUrlKeys davSignedUrlKeys(DavKitProperties properties) {
            Map<String, String> configured = properties.getSignedUrl().getKeys();
            if (configured != null && !configured.isEmpty()) {
                return new SignedUrlKeys(configured, properties.getSignedUrl().getActiveKey());
            }
            return derivedSigningKeys(properties.getLicenseKey());
        }

        /** Inject this where you render "Edit in Word" links. */
        @Bean
        @ConditionalOnMissingBean
        SignedUrls davSignedUrls(SignedUrlKeys keys, DavKitProperties properties) {
            return new SignedUrls(davPath(properties), keys, properties.getSignedUrl().getTtl(), Clock.systemUTC());
        }

    }

    /**
     * Derive a purpose-specific signing secret without exposing the licence as HMAC material.
     * Licence verification trims surrounding whitespace, so derivation does the same to keep
     * equivalent configuration stable across nodes. This also returns a deterministic value for
     * a missing licence so unlicensed applications can finish booting; the refused licence gate
     * disables authentication and the servlet answers 503, so that value grants no access.
     */
    private static SignedUrlKeys derivedSigningKeys(String rawLicenceKey) {
        String material = rawLicenceKey == null ? "" : rawLicenceKey.trim();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("com.tucanoo.davkit/signed-url/v1\0".getBytes(StandardCharsets.UTF_8));
            byte[] secret = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return SignedUrlKeys.single("licence",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    DavServletConfig davServletConfig(DavKitProperties properties, DavKitLicenseState licenseState) {
        DavKitProperties.Lock lock = properties.getLock();
        // rangeSupport and maxControlBodyBytes keep the record's defaults; no property until needed.
        return new DavServletConfig(davPath(properties), lock.getDefaultTimeout(), lock.getMaxTimeout(), true, 0,
                licenseState.gate());
    }

    @Bean("davKitServlet")
    DavServlet davServlet(ProviderRegistry registry,
                          LockService lockService,
                          DavPrincipalResolver principalResolver,
                          ObjectProvider<DavEventListener> listeners,
                          DavServletConfig config,
                          DavKitLicenseState licenseState) {
        DavServletConfig verifiedConfig = new DavServletConfig(
                config.path(),
                config.defaultLockTimeout(),
                config.maxLockTimeout(),
                config.rangeSupport(),
                config.maxControlBodyBytes(),
                licenseState.gate());
        return new DavServlet(registry, lockService,
                DavPrincipalResolver.fromRequestAttribute(principalResolver),
                listeners.orderedStream().toList(), verifiedConfig);
    }

    @Bean
    ServletRegistrationBean<DavServlet> davServletRegistration(
            @Qualifier("davKitServlet") DavServlet servlet,
            DavKitProperties properties) {
        ServletRegistrationBean<DavServlet> registration =
                new ServletRegistrationBean<>(servlet, davPath(properties) + "/*");
        registration.setName("davkit");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "davkit.office", name = "discovery-filter", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<OfficeDiscoveryFilter> davOfficeDiscoveryFilter(DavKitProperties properties) {
        OfficeDiscoveryFilter filter =
                new OfficeDiscoveryFilter(davPath(properties), properties.getOffice().isSharePointStubs());
        FilterRegistrationBean<OfficeDiscoveryFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setName("davkitOfficeDiscovery");
        registration.addUrlPatterns("/*");
        registration.setOrder(DISCOVERY_FILTER_ORDER);
        return registration;
    }

    /**
     * {@code StrictHttpFirewall} allows only seven HTTP methods by default and rejects
     * PROPFIND/LOCK/... with a 400 before any filter chain is consulted, so even a host that keeps
     * {@code davkit.path} in its own permit-all chain needs this. Only when Spring Security is
     * present and the host has not defined its own firewall.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StrictHttpFirewall.class)
    static class FirewallConfiguration {

        @Bean
        @ConditionalOnMissingBean(HttpFirewall.class)
        HttpFirewall davHttpFirewall() {
            StrictHttpFirewall firewall = new StrictHttpFirewall();
            firewall.setAllowedHttpMethods(List.of(
                    "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS",
                    "PROPFIND", "PROPPATCH", "LOCK", "UNLOCK"));
            return firewall;
        }
    }

    /**
     * One INFO line stating what is actually live — the first thing to ask for in a support
     * ticket, because it answers "what configuration is this server really running?" without
     * anyone reading YAML.
     */
    @Bean
    ApplicationRunner davKitStartupSummary(DavKitProperties properties, ProviderRegistry registry,
                                           DavLockStore lockStore, DavKitLicenseState licenseState) {
        return args -> {
            LicenseGate licenseGate = licenseState.gate();
            DavKitProperties.Auth auth = properties.getAuth();
            List<String> authenticators = new ArrayList<>();
            authenticators.add("signed-url(ttl=" + properties.getSignedUrl().getTtl() + ")");
            if (auth.getOfba().isEnabled()) {
                authenticators.add("ofba");
            }
            if (auth.getBasic().isEnabled()) {
                authenticators.add("basic");
            }
            String authSummary = licenseGate.refusal().isPresent()
                    ? "inactive (licence refused)"
                    : authenticators.isEmpty()
                            ? (auth.isRequired()
                                    ? "principal-resolver fallback (anonymous refused)"
                                    : "none (anonymous)")
                            : authenticators.toString();
            LOG.info("DavKit ready: path=" + davPath(properties)
                    + " mounts=" + registry.mountPointNames()
                    + " auth=" + authSummary
                    + " required=" + auth.isRequired()
                    + " lockStore=" + lockStore.getClass().getSimpleName()
                    + " licence=" + (licenseGate.refusal().isPresent()
                            ? "NONE (endpoints disabled, see the startup error)"
                            : licenseGate.license().licensee()
                                    + (licenseGate.license().evaluation() ? " (evaluation)" : ""))
                    + ". Set logging.level.com.tucanoo.davkit=DEBUG (or TRACE) for support logs");
        };
    }

    /** {@code davkit.path} with any trailing slash removed, so {@code path + "/*"} is a valid mapping. */
    static String davPath(DavKitProperties properties) {
        String path = properties.getPath() == null ? "" : properties.getPath().trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.equals("/") ? "/webdav" : path;
    }
}
