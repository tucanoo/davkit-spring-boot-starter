package com.tucanoo.davkit.demo;

import com.tucanoo.davkit.license.License;
import com.tucanoo.davkit.license.LicenseGate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * The licence for tests, built directly rather than verified from a key: no key material — real
 * or otherwise — lives in this repository, so a fresh clone builds green without one.
 *
 * <p>Works because {@code DavKitAutoConfiguration.davLicenseGate} is
 * {@code @ConditionalOnMissingBean}: a host (or a test) that supplies its own {@link LicenseGate}
 * replaces startup verification entirely. Passing a {@code null} build date skips the
 * update-entitlement rule, which is what a test wants — the gate's own behaviour is covered by
 * the licence tests in {@code server}, not here.</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestLicenseConfiguration {

    @Bean
    LicenseGate davLicenseGate() {
        License license = new License("DavKit demo tests", License.Type.COMMERCIAL,
                LocalDate.of(2026, 1, 1), null, null, 1, List.of());
        return LicenseGate.of(license, null, Clock.systemUTC());
    }
}
