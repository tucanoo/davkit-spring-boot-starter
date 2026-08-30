# demo-spring-boot

The reference host for the DavKit Spring Boot starter: a Postgres `documents` table, a JPA
`DavResourceProvider`, and one Thymeleaf page with server-rendered `ms-word:` / `ms-excel:` /
`ms-powerpoint:` `ofe|u|…` links. Click **Edit in Word/Excel/PowerPoint** → the matching Office
application opens the row → Enable Editing → type → Ctrl+S → the row's `bytes`, `version` and
`updated_at` change → reload the page.

```
documents(id bigserial, name text unique, bytes bytea, updated_at timestamptz, version bigint)
```

## What to read

| File | Why |
|---|---|
| `Document.java` | the entity — plain `byte[]` → `bytea`; `@Version` for optimistic locking |
| `JpaDocumentProvider.java` | **the whole DavKit integration**: ~90 lines, mount `documents`, ETag `<id>-<version>`, optimistic-lock failure → 412 |
| `IndexController.java` | builds the `ms-word:` / `ms-excel:` / `ms-powerpoint:` link per extension |
| `application.yml` | `davkit.path`, HTTPS keystore, datasource — all env-overridable |

There is no Office-specific code anywhere except the link scheme.

v0.2 additions: form login (dave / password), **signed URLs** for every rendered link (the
subject is the logged-in user), and **MS-OFBA** on plain `/webdav/...` URLs (the *via OFBA* link)
using Spring Security's `/login` as the OFBA dialog page. `SecurityConfig` shows the chain split
a real host needs, including the `MvcRequestMatcher` trap; the repository README's "Wiring
the starter into a host" covers the shape. **No licence key ships with this demo**: supply your
own via `DEMO_LICENSE_KEY` (see Run).

## Run

Needs Postgres and an HTTPS certificate Office trusts, because Office reads the OS store rather
than the browser's. The `mkcert` command is in the repository README; it writes the keystore to
`src/main/resources/certs/localhost.p12`, which this demo loads as `classpath:certs/localhost.p12`.
`certs/` is gitignored, so a fresh clone has none until you generate one. `./gradlew build` does
not need a certificate; only running the demo over TLS does.

```
docker compose up -d          # or point DEMO_DB_URL / DEMO_DB_USER / DEMO_DB_PASSWORD at your own
DEMO_LICENSE_KEY=<your key> ./gradlew :demo-spring-boot:bootRun
```

DavKit needs a licence key for all operation and this repository ships none, deliberately — a key
committed to a demo is a key anyone can use, and verification is offline, so there would be no way
to withdraw it. Free evaluation keys: <https://tucanoo.com/products/davkit>. Without a key the demo still
boots and every page works; only `/webdav/**` answers 503 with the reason, and the startup log
says the same. The tests need no key at all (`TestLicenseConfiguration`).

Open `https://<host>:8443/`. Startup seeds one document per Office application —
`Welcome letter.docx`, `Quarterly numbers.xlsx`, `Kickoff deck.pptx` — all generated in
`SeedData` as minimal OOXML packages (no binary fixtures in the repo). Seeding is a per-name
top-up, so a database created by an older build gains any missing documents on restart. The schema is created by
`ddl-auto: update` (demo only).

Env: `DEMO_DB_URL` (default `jdbc:postgresql://localhost:5432/davkit_demo`), `DEMO_DB_USER`,
`DEMO_DB_PASSWORD` (both default `postgres`), `DEMO_KEYSTORE` (default
`classpath:certs/localhost.p12`), `DEMO_KEYSTORE_PASSWORD` (default `changeit`),
and `DEMO_LICENSE_KEY` (no default). DavKit derives its signed-URL key from the validated licence;
the demo needs no separate signing secret.

The keystore is a classpath location rather than a file path on purpose: `gradlew bootRun`
starts in the module directory and an IDE run configuration usually starts in the repository
root, and a relative file path cannot satisfy both. `DEMO_KEYSTORE` takes a full Spring resource
location, so `file:/absolute/path.p12` works for a certificate kept outside the project.

## Tests

`DemoApplicationTest` boots the real stack on H2 + embedded Tomcat and walks the sequence Word
sends: OPTIONS → HEAD → LOCK → GET → PUT(423 without token) → PUT(204 with token) → UNLOCK,
then checks the row and the ETag, and repeats the LOCK/PUT/UNLOCK cycle against the `.xlsx` and
`.pptx` rows (same verbs, different Content-Type — the server has no type-specific code path).
MockMvc is not used — it cannot reach a registered servlet. `SeedDataTest` unzips the three
generated packages and checks parts, relationship targets and XML well-formedness — it cannot
prove Office opens them without a repair prompt; that stays a manual check.

## Not here (on purpose)

User management, revocation, per-document permissions — the provider grants READ_WRITE to any
authenticated principal. Still not for the open internet; it is a demo.
