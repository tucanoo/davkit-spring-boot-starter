package com.tucanoo.davkit.boot;

import com.tucanoo.davkit.license.LicenseGate;

/** Internal holder that keeps Spring bean precedence outside the licensing decision. */
record DavKitLicenseState(LicenseGate gate) {
}
