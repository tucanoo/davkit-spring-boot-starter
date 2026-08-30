package com.tucanoo.davkit.license;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Test-source gates for starter wiring tests; never included in published artifacts. */
public final class TestLicenseGates {

    private TestLicenseGates() {
    }

    public static LicenseGate commercial(String licensee) {
        return LicenseGate.of(new License(licensee, License.Type.COMMERCIAL,
                LocalDate.of(2026, 1, 1), null, null, 1, List.of()),
                null, Clock.systemUTC());
    }

    public static LicenseGate evaluation(String licensee, LocalDate expires, Clock clock) {
        return LicenseGate.of(new License(licensee, License.Type.EVALUATION,
                LocalDate.of(2026, 8, 1), expires, null, 1, List.of()), null, clock);
    }
}
