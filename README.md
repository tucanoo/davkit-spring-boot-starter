# davkit-spring-boot

The Spring Boot starter for [DavKit](https://tucanoo.com/products/davkit): auto-configuration,
`davkit.*` properties, and servlet/filter/firewall/auth registration.

Apache License 2.0. This wrapper is open source; the DavKit core it depends on
(`com.tucanoo.davkit:server`) is proprietary and requires a licence key. Publishing this wrapper
openly does not make the core open source.

Copyright 2026 Tucanoo Solutions Ltd.

## Modules

| Module | Purpose | Published |
|---|---|---|
| `server-spring-boot` | The starter itself | yes, `com.tucanoo.davkit:server-spring-boot` |
| `demo-spring-boot` | Reference host: click, edit in Word, save, database row updated | no |

## Build

```
./gradlew build
```

Java 21, Gradle 8.14 (wrapper included).

The build needs `com.tucanoo.davkit:server` at the same version. It is resolved in one of two
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

## Running the demo

The demo serves HTTPS because Office trusts the OS certificate store, not the browser's. Generate
a local certificate with [mkcert](https://github.com/FiloSottile/mkcert) and install its root CA
on the machine that will run Word:

```
mkcert -pkcs12 -p12-file demo-spring-boot/certs/localhost.p12 localhost 127.0.0.1 ::1
```

Point `DEMO_KEYSTORE` elsewhere to use your own. The demo also needs a DavKit licence key in
`DEMO_LICENSE_KEY`; without one the application starts normally and the DavKit endpoints answer
503 explaining why.

## Versioning

All three DavKit repositories ship in lockstep and bump together. The starter depends on the core
at that exact version, never a range.
