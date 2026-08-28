package com.tucanoo.davkit.license;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Test-source licensing for the demo's real HTTP tests. */
public final class TestLicenseGates {

    private TestLicenseGates() {
    }

    public static LicenseGate commercial(String licensee) {
        return LicenseGate.of(new License(licensee, License.Type.COMMERCIAL,
                LocalDate.of(2026, 1, 1), null, null, 1, List.of()),
                null, Clock.systemUTC());
    }
}
