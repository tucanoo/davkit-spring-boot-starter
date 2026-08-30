package com.tucanoo.davkit.demo;

import org.junit.jupiter.api.Test;
import com.tucanoo.davkit.boot.TestLicenseConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.tucanoo.davkit.auth.SignedUrls;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole host wired together on H2 and a real embedded Tomcat (MockMvc cannot reach a
 * registered servlet): starter → DavServlet → JPA provider → row. Walks the sequence Word
 * actually sends (LOCK → GET → PUT with the token → UNLOCK) and checks the row changed and
 * the ETag moved on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestLicenseConfiguration.class)   // no licence key ships with the demo; see that class
class DemoApplicationTest {

    private static final String LOCK_BODY = """
            <?xml version="1.0" encoding="utf-8" ?><D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner><D:href>DAVE-PC\\dave</D:href></D:owner></D:lockinfo>""";

    @LocalServerPort int port;
    @Autowired DocumentRepository documents;
    @Autowired SignedUrls signedUrls;

    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<byte[]> send(String method, String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return http.send(b.build(), BodyHandlers.ofByteArray());
    }

    /** Form login through Spring Security, keeping cookies in the client's jar. */
    private void login() throws Exception {
        String loginPage = new String(send("GET", "/login", null).body(), StandardCharsets.UTF_8);
        java.util.regex.Matcher csrf = java.util.regex.Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPage);
        assertThat(csrf.find()).as("csrf field on login page").isTrue();
        String form = "username=dave&password=password&_csrf=" + java.net.URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
        HttpResponse<byte[]> done = send("POST", "/login", form.getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/x-www-form-urlencoded");
        assertThat(done.statusCode()).isEqualTo(200); // redirect followed
    }

    @Test
    void indexRendersServerSideOfficeLink() throws Exception {
        assertThat(send("GET", "/", null).uri().getPath()).as("app itself requires login").isEqualTo("/login");
        login();
        HttpResponse<byte[]> page = send("GET", "/", null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(new String(page.body(), StandardCharsets.UTF_8))
                .contains("Signed in as <b>dave</b>")
                .contains("ms-word:ofe|u|http://localhost:" + port + "/webdav/t/")
                .contains("/documents/Welcome%20letter.docx\"")
                // The Excel and PowerPoint rows get their own scheme and label from the same table.
                .contains("ms-excel:ofe|u|http://localhost:" + port + "/webdav/t/")
                .contains("/documents/Quarterly%20numbers.xlsx\"")
                .contains(">Edit in Excel</a>")
                .contains("ms-powerpoint:ofe|u|http://localhost:" + port + "/webdav/t/")
                .contains("/documents/Kickoff%20deck.pptx\"")
                .contains(">Edit in PowerPoint</a>");
    }

    /**
     * The server has no type-specific code path: the same verb sequence Word sends
     * must work unchanged for the Excel and PowerPoint rows, with only Content-Type differing.
     */
    @Test
    void excelAndPowerPointRowsServeTheSameVerbSequence() throws Exception {
        record Case(String name, String contentType) {}
        for (Case c : java.util.List.of(
                new Case("Quarterly numbers.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                new Case("Kickoff deck.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"))) {
            String doc = signedUrls.path("dave", "documents/" + c.name());
            long versionBefore = documents.findByName(c.name()).orElseThrow().getVersion();

            HttpResponse<byte[]> get = send("GET", doc, null);
            assertThat(get.statusCode()).as(c.name()).isEqualTo(200);
            assertThat(get.headers().firstValue("Content-Type")).as(c.name()).contains(c.contentType());

            HttpResponse<byte[]> lock = send("LOCK", doc, LOCK_BODY.getBytes(StandardCharsets.UTF_8),
                    "Content-Type", "text/xml", "Timeout", "Second-600");
            assertThat(lock.statusCode()).as(c.name()).isEqualTo(200);
            String token = lock.headers().firstValue("Lock-Token").orElseThrow();

            byte[] edited = ("edited " + c.name()).getBytes(StandardCharsets.UTF_8);
            assertThat(send("PUT", doc, edited, "Content-Type", "text/xml", "If", "(" + token + ")")
                    .statusCode()).as(c.name()).isEqualTo(204);
            assertThat(send("UNLOCK", doc, null, "Lock-Token", token).statusCode()).as(c.name()).isEqualTo(204);

            Document row = documents.findByName(c.name()).orElseThrow();
            assertThat(row.getBytes()).as(c.name()).isEqualTo(edited);
            assertThat(row.getVersion()).as(c.name()).isEqualTo(versionBefore + 1);
        }
    }

    @Test
    void ofbaHandshakeAndSessionReplay() throws Exception {
        // Unauthenticated Office client on a plain URL: the MS-OFBA 403 handshake, not a redirect.
        HttpResponse<byte[]> handshake = send("OPTIONS", "/webdav/documents/", null,
                "X-FORMS_BASED_AUTH_ACCEPTED", "t", "User-Agent", "Microsoft Office Word 2014");
        assertThat(handshake.statusCode()).isEqualTo(403);
        // The handshake points at the protected START page, never the return page: Office closes
        // the dialog on the FIRST navigation to the return URL (observed 2026-08-24), so the
        // return URL must be unreachable until after login.
        assertThat(handshake.headers().firstValue("X-FORMS_BASED_AUTH_REQUIRED").orElseThrow())
                .isEqualTo("http://localhost:" + port + "/davkit/ofba/start");
        assertThat(handshake.headers().firstValue("X-FORMS_BASED_AUTH_RETURN_URL").orElseThrow())
                .isEqualTo("http://localhost:" + port + "/davkit/ofba/done");

        // The dialog's journey: /start redirects into the login flow while unauthenticated…
        HttpResponse<byte[]> beforeLogin = send("GET", "/davkit/ofba/start", null);
        assertThat(beforeLogin.uri().getPath()).as("unauthenticated start ends at the login form").isEqualTo("/login");
        // …then, once logged in (saved request brings the dialog back to /start), it lands on /done.
        login();
        HttpResponse<byte[]> afterLogin = send("GET", "/davkit/ofba/start", null);
        assertThat(afterLogin.uri().getPath()).as("authenticated start forwards to the return page").isEqualTo("/davkit/ofba/done");
        assertThat(afterLogin.statusCode()).isEqualTo(200);
        assertThat(new String(afterLogin.body(), StandardCharsets.UTF_8)).contains("return to Office");

        // …then replays the plain URL with the session cookie: authenticated via the OFBA session resolver.
        HttpResponse<byte[]> replay = send("OPTIONS", "/webdav/documents/", null,
                "User-Agent", "Microsoft Office Word 2014");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("DAV")).contains("1, 2");
        assertThat(send("GET", "/webdav/documents/Welcome%20letter.docx", null).statusCode()).isEqualTo(200);
    }

    @Test
    void plainUrlsWithoutTokenOrSessionAreRefused() throws Exception {
        // Non-Office client, no session, no token: 403, no login redirect, no Basic challenge.
        HttpResponse<byte[]> refused = send("GET", "/webdav/documents/Welcome%20letter.docx", null);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void wordSequenceUpdatesTheRow() throws Exception {
        // Same tokenised prefix for the document and its parent collection, as Word uses them.
        String doc = signedUrls.path("dave", "documents/Welcome letter.docx");
        String parent = doc.substring(0, doc.lastIndexOf('/') + 1);
        long versionBefore = documents.findByName("Welcome letter.docx").orElseThrow().getVersion();

        HttpResponse<byte[]> options = send("OPTIONS", parent, null);
        assertThat(options.statusCode()).isEqualTo(200);
        assertThat(options.headers().firstValue("DAV")).contains("1, 2");

        HttpResponse<byte[]> head = send("HEAD", doc, null);
        assertThat(head.statusCode()).isEqualTo(200);
        String etagBefore = head.headers().firstValue("ETag").orElseThrow();

        HttpResponse<byte[]> lock = send("LOCK", doc, LOCK_BODY.getBytes(StandardCharsets.UTF_8),
                "Content-Type", "text/xml", "Timeout", "Second-600");
        assertThat(lock.statusCode()).isEqualTo(200);
        String token = lock.headers().firstValue("Lock-Token").orElseThrow();
        assertThat(token).startsWith("<opaquelocktoken:");

        HttpResponse<byte[]> get = send("GET", doc, null);
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.headers().firstValue("Content-Type"))
                .contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        // PUT without the token on a locked document is refused.
        assertThat(send("PUT", doc, "intruder".getBytes(StandardCharsets.UTF_8), "Content-Type", "text/xml")
                .statusCode()).isEqualTo(423);

        byte[] edited = "edited in Word".getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> put = send("PUT", doc, edited, "Content-Type", "text/xml", "If", "(" + token + ")");
        assertThat(put.statusCode()).isEqualTo(204);
        String etagAfter = put.headers().firstValue("ETag").orElseThrow();
        assertThat(etagAfter).isNotEqualTo(etagBefore);

        assertThat(send("UNLOCK", doc, null, "Lock-Token", token).statusCode()).isEqualTo(204);

        Document row = documents.findByName("Welcome letter.docx").orElseThrow();
        assertThat(row.getBytes()).isEqualTo(edited);
        assertThat(row.getVersion()).isEqualTo(versionBefore + 1);
        assertThat(etagAfter).isEqualTo("\"" + row.getId() + "-" + row.getVersion() + "\"");
    }
}
