package com.tucanoo.davkit.boot;

import com.tucanoo.davkit.license.TestLicenseGates;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Selects unpublished test licensing without exposing a host-overridable production gate. */
@TestConfiguration(proxyBeanMethods = false)
public class TestLicenseConfiguration {

    @Bean
    @Primary
    DavKitLicenseState testLicenseState() {
        return new DavKitLicenseState(TestLicenseGates.commercial("DavKit demo tests"));
    }
}
