package com.tucanoo.davkit.demo;

import com.tucanoo.davkit.spi.DavContent;
import com.tucanoo.davkit.spi.DavContext;
import com.tucanoo.davkit.spi.DavPath;
import com.tucanoo.davkit.spi.DavPermissions;
import com.tucanoo.davkit.spi.DavPreconditionFailedException;
import com.tucanoo.davkit.spi.DavResource;
import com.tucanoo.davkit.spi.DavResourceProvider;
import com.tucanoo.davkit.spi.DavWriteRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The reference JPA-backed provider: one row per document under {@code /webdav/documents/<name>}.
 * <ul>
 *   <li>{@code resolve} is a cheap read; the entity is stashed in {@code attributes} so
 *       {@code read} in the same request does not hit the database again.</li>
 *   <li>The ETag is {@code <id>-<version>}: it changes on every save and is derived from
 *       the same row state Office will see on the next PROPFIND.</li>
 *   <li>{@code write} runs in its own transaction; an optimistic-lock failure becomes a 412
 *       rather than a 500, so Word shows "changed by another user" instead of retrying.</li>
 *   <li>Everyone may read and write; this demo applies no per-document authorisation.</li>
 * </ul>
 */
@Component
public class JpaDocumentProvider implements DavResourceProvider {

    static final String MOUNT = "documents";

    private final DocumentRepository documents;

    public JpaDocumentProvider(DocumentRepository documents) {
        this.documents = documents;
    }

    @Override
    public String mountPoint() {
        return MOUNT;
    }

    @Override
    public Optional<DavResource> resolve(DavPath path, DavContext ctx) {
        List<String> rest = path.remainderFor(MOUNT);
        if (rest.size() != 1) {
            return Optional.empty();
        }
        return documents.findByName(rest.get(0)).map(doc -> describe(doc, path));
    }

    @Override
    public DavContent read(DavResource resource, DavContext ctx) {
        Document doc = resource.attribute("document", Document.class)
                .orElseGet(() -> documents.findById(idOf(resource)).orElseThrow());
        return DavContent.of(doc.getBytes());
    }

    @Override
    @Transactional
    public DavResource write(DavResource resource, DavWriteRequest request, DavContext ctx) throws IOException {
        Document doc = documents.findById(idOf(resource))
                .orElseThrow(() -> new DavPreconditionFailedException("document deleted"));
        byte[] bytes;
        try (InputStream in = request.content().open()) {
            bytes = in.readAllBytes();
        }
        doc.setBytes(bytes);
        doc.setUpdatedAt(Instant.now());
        try {
            doc = documents.saveAndFlush(doc);
        } catch (OptimisticLockingFailureException e) {
            throw new DavPreconditionFailedException("document changed concurrently");
        }
        return describe(doc, resource.path());
    }

    private static DavResource describe(Document doc, DavPath path) {
        return new DavResource(
                String.valueOf(doc.getId()), path, doc.getName(), false, null,
                doc.getBytes().length, doc.getId() + "-" + doc.getVersion(), doc.getUpdatedAt(),
                Optional.empty(), DavPermissions.READ_WRITE,
                Map.of("document", doc));
    }

    private static long idOf(DavResource resource) {
        return Long.parseLong(resource.key());
    }
}
