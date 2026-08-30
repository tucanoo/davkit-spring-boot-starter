# davkit-spring-boot

The Spring Boot starter for [DavKit](https://tucanoo.com/products/davkit): auto-configuration,
`davkit.*` properties, and servlet/filter/firewall/auth registration.

Apache License 2.0. This wrapper is open source; the DavKit core it depends on
(`com.tucanoo.davkit:davkit-server`) is proprietary and requires a licence key. Publishing this wrapper
openly does not make the core open source.

Copyright 2026 Tucanoo Solutions Ltd.

## Modules

| Module | Purpose | Published |
|---|---|---|
| `server-spring-boot` | The starter itself | yes, `com.tucanoo.davkit:davkit-spring-boot` |
| `demo-spring-boot` | Reference host: click, edit in Word, save, database row updated | no |

## Build

```
./gradlew build
```

Java 17, Gradle 8.14 (wrapper included).

The build needs `com.tucanoo.davkit:davkit-server` at the same version. It is resolved in one of two
ways, automatically:

- **Sibling checkout.** If `../davkit-core` exists, Gradle includes it as a composite build and
  a core change is picked up with no publish step. This is the internal development layout:

  ```
  davkit/
    ├─ davkit-core/
    ├─ davkit-spring-boot/     <- you are here
    └─ davkit-grails-plugin/
  ```

- **Maven.** Otherwise the coordinate resolves normally. Until the core is published to Maven
  Central, that means `mavenLocal`, fed by `./gradlew publishToMavenLocal` in `davkit-core`.

## Wiring the starter into a host

Adding the starter enables DavKit by default. A valid licence is the only signing material needed:

```yaml
davkit:
  license-key: ${DAVKIT_LICENSE_KEY}
```

Set `davkit.enabled=false` to disable all DavKit servlet, filter, firewall and supporting-bean
registration without removing the dependency.

Auto-configuration registers everything except the one thing that cannot be decided without
seeing the host's security chain: **the servlet under `davkit.path` must sit outside that chain,
or in a chain of its own with CSRF disabled.** Office does not send a CSRF token and will not
follow a redirect to a login page, so a `davkit.path` left inside a form-login chain answers
Word with a redirect and the document never opens.

Two details the starter does handle, listed here because they look surprising in a filter dump:

- `OfficeDiscoveryFilter` registers at order -101, one ahead of Spring Security's chain at -100,
  so Office's root probes (`OPTIONS /`, `PROPFIND /`) are answered before any redirect can fire.
- A `StrictHttpFirewall` bean is contributed when Spring Security is present and the host has not
  defined its own. The default firewall allows seven HTTP methods and rejects `PROPFIND`, `LOCK`
  and the rest with a 400 before any chain runs, so even a permit-all chain needs this.

`demo-spring-boot`'s `SecurityConfig` shows the whole shape on a real host.

### Authentication defaults

`davkit.auth.required` defaults to `true`. Signed URLs are available automatically: their key is
derived from the validated licence and their default lifetime is eight hours. OFBA and Basic run
after signed URLs, followed by any host-supplied `DavPrincipalResolver`. If none establishes a
non-anonymous principal, the starter returns a bare 403. Set `davkit.auth.required=false` only for
deliberate anonymous development access.

An absent, invalid or already-expired licence remains a separate condition: authentication stays
out of the way so the DavKit servlet can return its explanatory licence 503.

Installations using the same OEM licence derive the same signing key. When those installations
are separate trust boundaries, configure a distinct `davkit.signed-url.keys` map in each one.

### Licence authority

The starter passes the raw `davkit.license-key` value to the proprietary core. The core verifies
the embedded Ed25519 product signature and creates the runtime gate; the open-source wrapper does
not verify keys or manufacture approved state. `LicenseGate` is therefore not a host extension
point. Supplying another gate, servlet configuration, or servlet bean does not replace DavKit's
verified gate. A custom `DavServletConfig` may still change its documented non-licensing settings.

### Deployment

Deploy the host application at the container's root context. Office sends its discovery probes to
the origin's `/`, which an application deployed under a context path never receives. Executable
jars already own the root; the case to watch is a WAR in a shared container. The deployment guide
supplied with your licence covers this and the rest of a production install.

## Running the demo

The demo serves HTTPS because Office trusts the OS certificate store, not the browser's. Generate
a local certificate with [mkcert](https://github.com/FiloSottile/mkcert) and install its root CA
on the machine that will run Word:

```
mkcert -pkcs12 -p12-file demo-spring-boot/src/main/resources/certs/localhost.p12 localhost 127.0.0.1 ::1
```

The demo loads it as `classpath:certs/localhost.p12`, so it resolves identically under
`bootRun`, an IDE run configuration and a built jar. Point `DEMO_KEYSTORE` at any Spring resource
location (`file:/path/to/your.p12`) to use your own. The demo also needs a DavKit licence key in
`DEMO_LICENSE_KEY`; without one the application starts normally and the DavKit endpoints answer
503 explaining why.

## Versioning

All three DavKit repositories ship in lockstep and bump together. The starter depends on the core
at that exact version, never a range.
