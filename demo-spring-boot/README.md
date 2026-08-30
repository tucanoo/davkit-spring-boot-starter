# demo-spring-boot

A Spring Boot host with PostgreSQL document storage, form login and edit links for desktop
Word, Excel and PowerPoint. Saves update the document's bytes, version and timestamp.

This is a local development demo. The login is `dave` / `password`, the database credentials
are `postgres` / `postgres`, and the provider grants read/write access to every authenticated
principal. There are no per-document permissions or user-management features. Do not expose
it to the internet or use it for sensitive documents.

## Run

Start in the `davkit-spring-boot` repository root, not this module directory. You need Java 17,
Docker with Compose (or your own PostgreSQL server), desktop Office for a manual edit test,
and the matching DavKit binary dependencies described in the [repository README](../README.md).
The current `0.3.0-SNAPSHOT` DavKit binaries are not yet available from Maven Central.

Request a licence key through the [evaluation form](https://tucanoo.com/products/davkit/#evaluation-form).
No key is included. Set `DEMO_LICENSE_KEY` in your local environment before starting the demo.
Without a valid key, DavKit endpoints return 503 with the reason.

Install [mkcert](https://github.com/FiloSottile/mkcert), then create the ignored certificate
directory and a certificate for this machine:

```sh
mkdir -p demo-spring-boot/src/main/resources/certs
mkcert -install
mkcert -pkcs12 -p12-file demo-spring-boot/src/main/resources/certs/localhost.p12 localhost 127.0.0.1 ::1
```

`mkcert -install` adds its local CA to your trust store. The machine running Office must trust
that CA too; accepting a browser certificate warning is not enough. Never share the CA's
private key. Use a certificate with the correct hostname if Office runs on another machine.

With `DEMO_LICENSE_KEY` set, run:

```sh
docker compose -f demo-spring-boot/docker-compose.yml up -d
./gradlew :demo-spring-boot:bootRun
```

Compose exposes PostgreSQL on `127.0.0.1:5432`. Open [https://localhost:8443/](https://localhost:8443/)
and sign in with the demo login. Startup seeds `Welcome letter.docx`, `Quarterly numbers.xlsx`
and `Kickoff deck.pptx`. Click an edit link, enable editing if Office asks, make a change and
save. Reload the page to check the version and timestamp. The *via OFBA* links exercise the
Office forms-based login flow instead of signed URLs.

The database schema uses `ddl-auto: update` for convenience. To stop PostgreSQL, run from the
same repository root:

```sh
docker compose -f demo-spring-boot/docker-compose.yml down
```

## Configuration

| Environment variable | Default |
|---|---|
| `DEMO_LICENSE_KEY` | None |
| `DEMO_DB_URL` | `jdbc:postgresql://localhost:5432/davkit_demo` |
| `DEMO_DB_USER` | `postgres` |
| `DEMO_DB_PASSWORD` | `postgres` |
| `DEMO_KEYSTORE` | `classpath:certs/localhost.p12` |
| `DEMO_KEYSTORE_PASSWORD` | `changeit` |

Set the database variables to use an existing PostgreSQL server. `DEMO_KEYSTORE` accepts a
Spring resource location such as `file:/absolute/path/localhost.p12`. The default classpath
location works with `bootRun` and a packaged jar. Keep generated certificates and keys out of
commits and distributed jars. Signed-URL keys are derived from the validated licence by default.

## Code and checks

[JpaDocumentProvider](src/main/java/com/tucanoo/davkit/demo/JpaDocumentProvider.java) implements
storage access and optimistic concurrency checks. [SecurityConfig](src/main/java/com/tucanoo/davkit/demo/SecurityConfig.java)
separates WebDAV authentication from the browser's form-login chain.
[IndexController](src/main/java/com/tucanoo/davkit/demo/IndexController.java) renders the Office links.

Run automated tests from the repository root:

```sh
./gradlew :demo-spring-boot:test
```

Tests use H2 and embedded Tomcat; they need no PostgreSQL server, TLS certificate or licence
key. They cover HTTP lock/read/write sequences and generated OOXML packages. They do not
replace a manual edit-and-save check in Office.
