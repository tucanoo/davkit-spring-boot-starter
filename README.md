# davkit-spring-boot

The Spring Boot starter for [DavKit](https://tucanoo.com/products/davkit/). It registers
DavKit's WebDAV servlet, authentication filters and `davkit.*` configuration properties.

The dependency coordinates for this checkout are:

```kotlin
dependencies {
    implementation("com.tucanoo.davkit:davkit-spring-boot:0.3.0-SNAPSHOT")
}
```

This is prerelease source. The starter and its proprietary dependency,
`com.tucanoo.davkit:davkit-server`, both use `0.3.0-SNAPSHOT`. The matching DavKit artifacts
are not yet available from Maven Central. Before using the dependency or building from
source, ask [dave@tucanoo.com](mailto:dave@tucanoo.com) about binary access and repository
setup. A licence key alone does not supply the dependencies.

Request a key through the [evaluation form](https://tucanoo.com/products/davkit/#evaluation-form).
The starter and demo source in this repository are licensed under [Apache 2.0](LICENSE).
The core is proprietary and requires a valid licence key at runtime; this repository's
licence does not grant rights to the core. No key is included.

## Compatibility and builds

| Component | Baseline in this checkout |
|---|---|
| Java | Java 17 bytecode and build toolchain |
| Gradle | 8.14.5, wrapper included |
| Spring Boot | 3.5.4 for the starter and demo |
| Spring Security | 6.5.2 for the starter |
| Spring Boot 4 check | Sources also compile against Boot 4.1.0, Security 7.1.0 and Servlet 6.1.0 |

The Boot 4 compilation check runs as part of `check`; it does not start a Boot 4 host.
Validate your application's framework and servlet-container combination before deployment.
DavKit repositories version together, and the starter depends on the core at exactly the
same version. Do not mix DavKit versions.

Once matching core binaries are available, run from this repository root:

```sh
./gradlew build
```

The build resolves DavKit binaries from Maven Local or Maven Central. Contributors do not
need proprietary core source. Maintainers who already have an authorised `../davkit-core`
checkout can use it as an optional composite build; Gradle detects that directory and
substitutes its projects for Maven dependencies. See [CONTRIBUTING.md](CONTRIBUTING.md)
for checks and the remaining public-release requirements.

The `server-spring-boot` directory is Gradle project `:davkit-spring-boot` and produces
the starter. `demo-spring-boot` is a host application and is not published.

## Wiring the starter into a host

Register your storage adapter as a `DavResourceProvider` bean. The
[demo provider](demo-spring-boot/src/main/java/com/tucanoo/davkit/demo/JpaDocumentProvider.java)
shows document resolution, reads and transactional writes. Its unrestricted document
permissions are for local demonstration; a host must enforce its own access rules.

Adding the starter enables DavKit by default. Supply the licence through configuration:

```yaml
davkit:
  license-key: ${DAVKIT_LICENSE_KEY}
```

Set `davkit.enabled=false` to disable DavKit's servlet, filters, firewall and supporting
beans without removing the dependency.

If the host uses Spring Security, put `davkit.path` in its own chain with CSRF disabled
and no redirect to form login. Office sends no CSRF token and cannot use a browser login
redirect to authenticate a WebDAV request. DavKit's authentication filter protects this
endpoint. The demo's [SecurityConfig](demo-spring-boot/src/main/java/com/tucanoo/davkit/demo/SecurityConfig.java)
shows the separate chains and an explicit matcher for the non-MVC servlet.

`OfficeDiscoveryFilter` runs at order -101, before Spring Security's chain at -100, so root
`OPTIONS` and `PROPFIND` probes reach it before a login redirect. When Spring Security is
present and the host has no firewall bean, the starter contributes a `StrictHttpFirewall`
that allows WebDAV methods. A custom firewall must allow those methods too.

`davkit.auth.required` defaults to `true`. Signed URLs use a key derived from the validated
licence and expire after eight hours by default. Configured OFBA and Basic authentication
follow signed URLs, then host-supplied `DavPrincipalResolver` implementations. When no
authentication challenge is configured, requests without an accepted principal receive 403.
Set `davkit.auth.required=false` only for intentional anonymous development access.

Signed URLs are bearer credentials: anyone who obtains one can use it until it expires.
Keep them out of logs and public pages. Installations sharing an OEM licence derive the
same signing key; configure distinct `davkit.signed-url.keys` maps when installations
must not trust one another's URLs.

A missing, invalid or expired licence key causes DavKit endpoints to return 503 with the reason.

Deploy at the container's root context. Office sends discovery requests to the origin's
`/`, which an application mounted under a context path cannot receive.

## Demo and reporting

The [demo instructions](demo-spring-boot/README.md) cover PostgreSQL, a trusted local HTTPS
certificate and the test login. Use the demo only on a development machine.

For bugs and changes, see [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities privately
using [SECURITY.md](SECURITY.md).

Copyright 2026 Tucanoo Solutions Ltd.
