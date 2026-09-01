package com.tucanoo.davkit.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code davkit.*} configuration.
 */
@ConfigurationProperties(prefix = "davkit")
public class DavKitProperties {

    /** Enable DavKit's servlet, filters and supporting infrastructure. */
    private boolean enabled = true;

    /** Servlet mapping for the WebDAV endpoint. Office discovery requests are intercepted at the context root regardless. */
    private String path = "/webdav";

    /**
     * Licence key — required for all operation; free evaluation keys are available.
     * Absent or invalid: the host application boots normally, DavKit endpoints answer 503.
     */
    private String licenseKey;

    private final Lock lock = new Lock();
    private final Office office = new Office();
    private final SignedUrl signedUrl = new SignedUrl();
    private final Auth auth = new Auth();

    public static class Lock {
        /** Applied when the client sends no Timeout header. Office sends Second-3600 on Windows. */
        private Duration defaultTimeout = Duration.ofMinutes(5);
        private Duration maxTimeout = Duration.ofHours(1);
        /** {@code memory} (one node) or {@code jdbc} (shared table via the application's DataSource). */
        private String store = "memory";
        /** Table for the jdbc store. */
        private String table = "davkit_locks";
        /** Run {@code CREATE TABLE IF NOT EXISTS} for the jdbc store at startup. */
        private boolean createTable = true;

        public String getStore() { return store; }
        public void setStore(String store) { this.store = store; }
        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public boolean isCreateTable() { return createTable; }
        public void setCreateTable(boolean createTable) { this.createTable = createTable; }
        public Duration getDefaultTimeout() { return defaultTimeout; }
        public void setDefaultTimeout(Duration defaultTimeout) { this.defaultTimeout = defaultTimeout; }
        public Duration getMaxTimeout() { return maxTimeout; }
        public void setMaxTimeout(Duration maxTimeout) { this.maxTimeout = maxTimeout; }
    }

    public static class Office {
        /** Register the root-level Office discovery filter ahead of Spring Security. */
        private boolean discoveryFilter = true;
        /** Answer the SharePoint {@code /_api/} stubs (GetSharingInformation etc.). */
        private boolean sharePointStubs = true;

        public boolean isDiscoveryFilter() { return discoveryFilter; }
        public void setDiscoveryFilter(boolean discoveryFilter) { this.discoveryFilter = discoveryFilter; }
        public boolean isSharePointStubs() { return sharePointStubs; }
        public void setSharePointStubs(boolean sharePointStubs) { this.sharePointStubs = sharePointStubs; }
    }

    /** Signed expiring URLs. */
    public static class SignedUrl {
        /** Optional key id → secret override. By default DavKit derives a key from the licence. */
        private Map<String, String> keys = new LinkedHashMap<>();
        /** Key id used to mint new tokens; defaults to the first configured key. */
        private String activeKey;
        /** Validity of minted tokens. Must cover an editing session, not just the open. */
        private Duration ttl = Duration.ofHours(8);
        public Map<String, String> getKeys() { return keys; }
        public void setKeys(Map<String, String> keys) { this.keys = keys; }
        public String getActiveKey() { return activeKey; }
        public void setActiveKey(String activeKey) { this.activeKey = activeKey; }
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
    }

    /** DavKit's authentication chain on {@code davkit.path}. */
    public static class Auth {
        /**
         * Refuse requests no authenticator vouches for. On by default; turning it off makes every
         * unmatched request anonymous — development only.
         */
        private boolean required = true;
        private final Basic basic = new Basic();
        private final Ofba ofba = new Ofba();

        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public Basic getBasic() { return basic; }
        public Ofba getOfba() { return ofba; }

        /** HTTP Basic over HTTPS. Licensed feature; needs a {@code BasicAuthenticator.CredentialsVerifier} bean. */
        public static class Basic {
            private boolean enabled = false;
            private String realm = "DavKit";
            /** Accept/request credentials on non-TLS requests - only behind a TLS-terminating proxy. */
            private boolean allowInsecure = false;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getRealm() { return realm; }
            public void setRealm(String realm) { this.realm = realm; }
            public boolean isAllowInsecure() { return allowInsecure; }
            public void setAllowInsecure(boolean allowInsecure) { this.allowInsecure = allowInsecure; }
        }

        /** MS-OFBA (Windows Office only). Licensed feature. */
        public static class Ofba {
            private boolean enabled = false;
            /**
             * Where the OFBA dialog is sent ({@code X-FORMS_BASED_AUTH_REQUIRED}). Must be a page
             * that requires authentication and, once authenticated, ends on {@code return-url} —
             * and must NEVER be the return URL itself: Office treats any navigation to the return
             * URL as "login done" and closes the dialog instantly (observed 2026-08-24). The
             * default is a starter-registered redirect page that does exactly this; keep it
             * inside the host's authenticated security chain.
             */
            private String loginUrl = "/davkit/ofba/start";
            /** Where the login flow ends; a path here is served by a tiny built-in "signed in" page. */
            private String returnUrl = "/davkit/ofba/done";
            /** {@code WxH} of Office's embedded browser dialog. */
            private String dialogSize = "660x495";

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public String getLoginUrl() { return loginUrl; }
            public void setLoginUrl(String loginUrl) { this.loginUrl = loginUrl; }
            public String getReturnUrl() { return returnUrl; }
            public void setReturnUrl(String returnUrl) { this.returnUrl = returnUrl; }
            public String getDialogSize() { return dialogSize; }
            public void setDialogSize(String dialogSize) { this.dialogSize = dialogSize; }
        }
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLicenseKey() { return licenseKey; }
    public void setLicenseKey(String licenseKey) { this.licenseKey = licenseKey; }
    public Lock getLock() { return lock; }
    public Office getOffice() { return office; }
    public SignedUrl getSignedUrl() { return signedUrl; }
    public Auth getAuth() { return auth; }
}
