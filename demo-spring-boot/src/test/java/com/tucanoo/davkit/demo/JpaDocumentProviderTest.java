package com.tucanoo.davkit.demo;

import com.tucanoo.davkit.boot.TestLicenseConfiguration;
import com.tucanoo.davkit.spi.DavContent;
import com.tucanoo.davkit.spi.DavContext;
import com.tucanoo.davkit.spi.DavPath;
import com.tucanoo.davkit.spi.DavPreconditionFailedException;
import com.tucanoo.davkit.spi.DavPrincipal;
import com.tucanoo.davkit.spi.DavResource;
import com.tucanoo.davkit.spi.DavWriteRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real provider and separate database transactions across the resolve/write gap. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestLicenseConfiguration.class)
class JpaDocumentProviderTest {

    private static final DavContext CONTEXT = new DavContext(
            DavPrincipal.ANONYMOUS, "PUT", null, "http://localhost/webdav", Map.of());

    @Autowired JpaDocumentProvider provider;
    @Autowired DocumentRepository documents;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private long id;
    private DavResource resolved;

    @BeforeEach
    void createDocument() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String name = "concurrency-" + UUID.randomUUID() + ".docx";
        id = documents.saveAndFlush(new Document(name, bytes("original"), Instant.now())).getId();
        resolved = provider.resolve(DavPath.of("documents", name), CONTEXT).orElseThrow();
    }

    @AfterEach
    void deleteDocument() {
        documents.deleteById(id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnEditCommittedBetweenResolveAndWrite(boolean withIfMatch) {
        long updatedVersion = commitApplicationEdit();
        Optional<String> ifMatch = withIfMatch ? Optional.of(resolved.etag()) : Optional.empty();

        assertThatThrownBy(() -> provider.write(resolved,
                request(DavContent.of(bytes("DAV edit")), ifMatch), CONTEXT))
                .isInstanceOf(DavPreconditionFailedException.class);

        assertApplicationEditPreserved(updatedVersion);
    }

    @Test
    void rejectsAnEditCommittedAfterTheWriteTransactionReloadsTheRow() {
        long[] updatedVersion = new long[1];
        DavContent content = new DavContent() {
            @Override public InputStream open() {
                // write has loaded the row; commit another transaction before its flush.
                updatedVersion[0] = commitApplicationEdit();
                return new ByteArrayInputStream(bytes("DAV edit"));
            }
            @Override public OptionalLong length() { return OptionalLong.empty(); }
        };

        assertThatThrownBy(() -> provider.write(resolved, request(content, Optional.empty()), CONTEXT))
                .isInstanceOf(DavPreconditionFailedException.class);

        assertApplicationEditPreserved(updatedVersion[0]);
    }

    @Test
    void acceptsAnUnchangedResourceAndReturnsTheNewVersion() throws Exception {
        long versionBefore = documents.findById(id).orElseThrow().getVersion();
        DavResource saved = provider.write(resolved,
                request(DavContent.of(bytes("DAV edit")), Optional.empty()), CONTEXT);

        Document row = documents.findById(id).orElseThrow();
        assertThat(row.getBytes()).isEqualTo(bytes("DAV edit"));
        assertThat(row.getVersion()).isEqualTo(versionBefore + 1);
        assertThat(saved.etag()).isEqualTo(id + "-" + (versionBefore + 1));
    }

    @Test
    void rejectsADocumentDeletedSinceResolve() {
        documents.deleteById(id);

        assertThatThrownBy(() -> provider.write(resolved,
                request(DavContent.of(bytes("DAV edit")), Optional.empty()), CONTEXT))
                .isInstanceOf(DavPreconditionFailedException.class);

        assertThat(documents.findById(id)).isEmpty();
    }

    private long commitApplicationEdit() {
        return transaction.execute(status -> {
            Document row = documents.findById(id).orElseThrow();
            row.setBytes(bytes("application edit"));
            return documents.saveAndFlush(row).getVersion();
        });
    }

    private void assertApplicationEditPreserved(long expectedVersion) {
        Document row = documents.findById(id).orElseThrow();
        assertThat(row.getBytes()).isEqualTo(bytes("application edit"));
        assertThat(row.getVersion()).isEqualTo(expectedVersion);
    }

    private static DavWriteRequest request(DavContent content, Optional<String> ifMatch) {
        return new DavWriteRequest(content, ifMatch, Optional.empty(), Optional.empty());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
