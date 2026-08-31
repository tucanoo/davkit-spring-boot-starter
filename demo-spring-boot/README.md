# demo-spring-boot

A Spring Boot host with an in-memory H2 database and signed edit links for desktop
Word, Excel and PowerPoint. Saves update the document's bytes, version and timestamp.
All documents and edits disappear when the application stops; startup seeds fresh copies.

This is a local development demo, bound to `127.0.0.1`. No sign-in is needed to view or edit
the sample documents, just like the Grails demo. Anyone who can load the page can obtain
its signed edit links. H2 uses `sa` with an empty password. There are no per-document
permissions or user-management features.
HTTP does not encrypt credentials, session cookies or signed document URLs. Keep it on your
development machine; do not expose it to the internet or use it for sensitive documents.

## Run

Start in the `davkit-spring-boot` repository root, not this module directory. You need Java 17,
desktop Office for a manual edit test, and the matching DavKit binary dependencies described
in the [repository README](../README.md). The current `0.3.0-SNAPSHOT` DavKit binaries are not
yet available from Maven Central. No Docker, separate database, mkcert or `.p12` file is needed.

Request a licence key through the [evaluation form](https://tucanoo.com/products/davkit/#evaluation-form).
No key is included. Set `DEMO_LICENSE_KEY` in your local environment before starting the demo.
Without a valid key, DavKit endpoints return 503 with the reason.

With `DEMO_LICENSE_KEY` set, run:

```sh
./gradlew :demo-spring-boot:bootRun
```

Open [http://localhost:8080/](http://localhost:8080/); no sign-in is required.
Startup seeds `Welcome letter.docx`, `Quarterly numbers.xlsx`
and `Kickoff deck.pptx`. Click an edit link, enable editing if Office asks, make a change and
save. Click **Refresh** to reload the page and check the version and timestamp.

The optional **via OFBA (sign-in)** links demonstrate Office forms-based authentication
on Windows. Only that flow needs the demo login, `dave` / `password`. Normal **Edit in…**
links use signed URLs and do not need a login. Unsigned WebDAV requests still require an
authenticated session; making the document page public does not disable DavKit authentication.

The database schema uses `ddl-auto: create-drop`. Stop the application with Ctrl+C; there is
no database service to stop. Restarting discards edits and recreates the three sample documents.

This HTTP setup is intended for trying the demo locally. The automated checks exercise
HTTP requests, but they do not establish that your desktop Office version and security
settings will accept HTTP edit links or OFBA. Try an edit and save in your client; if it
refuses, retry with the optional HTTPS profile below. Do not weaken Office security settings
just to run the demo.

## Optional HTTPS

For a comparison using trusted HTTPS, install [mkcert](https://github.com/FiloSottile/mkcert)
and create a local certificate from the repository root:

```sh
mkdir -p demo-spring-boot/src/main/resources/certs
mkcert -install
mkcert -pkcs12 -p12-file demo-spring-boot/src/main/resources/certs/localhost.p12 localhost 127.0.0.1 ::1
./gradlew :demo-spring-boot:bootRun --args='--spring.profiles.active=https'
```

With `DEMO_LICENSE_KEY` still set, open [https://localhost:8443/](https://localhost:8443/).
The profile changes only the port and TLS settings; storage remains in-memory H2.

`mkcert -install` adds its local CA to your trust store. Office must trust that CA in the OS
store too; accepting a browser certificate warning is not enough. Never share the CA's
private key. Keep generated certificates and keys out of commits and distributed jars.

## Configuration

| Environment variable | Default |
|---|---|
| `DEMO_LICENSE_KEY` | None |
| `SERVER_PORT` | `8080` (`8443` with the `https` profile) |
| `SPRING_PROFILES_ACTIVE` | None; set to `https` for TLS |
| `DEMO_KEYSTORE` | `classpath:certs/localhost.p12` (HTTPS profile only) |
| `DEMO_KEYSTORE_PASSWORD` | `changeit` (HTTPS profile only) |

`DEMO_KEYSTORE` accepts a Spring resource location such as `file:/absolute/path/localhost.p12`.
The default classpath location works with `bootRun` and a packaged jar. None of the keystore
settings are read by the default HTTP setup. Signed-URL keys are derived from the validated
licence by default.

## Code and checks

[JpaDocumentProvider](src/main/java/com/tucanoo/davkit/demo/JpaDocumentProvider.java) implements
storage access and optimistic concurrency checks. [SecurityConfig](src/main/java/com/tucanoo/davkit/demo/SecurityConfig.java)
separates WebDAV authentication from the optional OFBA form-login flow and permits public
access only to the demo document page.
[IndexController](src/main/java/com/tucanoo/davkit/demo/IndexController.java) renders the Office links.

Run automated tests from the repository root:

```sh
./gradlew :demo-spring-boot:test
```

Tests use the demo's default H2/HTTP configuration with embedded Tomcat on a random port;
they need no database server, TLS certificate or licence key. They cover access without login,
optional OFBA login, HTTP Office
links, lock/read/write sequences, concurrent edits and generated OOXML packages. They do
not replace a manual edit-and-save check in Office.
