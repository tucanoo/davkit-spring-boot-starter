package com.tucanoo.davkit.demo;

import com.tucanoo.davkit.auth.SignedUrls;
import com.tucanoo.davkit.protocol.PercentCodec;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Locale;

/**
 * One page: the documents table with server-rendered Office URI scheme links. Every
 * signed link uses the request's HTTP(S) origin and an anonymous demo subject unless the
 * optional OFBA flow has signed the user in.
 */
@Controller
public class IndexController {

    private final DocumentRepository documents;
    private final SignedUrls signedUrls;

    public IndexController(DocumentRepository documents, SignedUrls signedUrls) {
        this.documents = documents;
        this.signedUrls = signedUrls;
    }

    @GetMapping("/")
    public String index(HttpServletRequest request, Model model) {
        String user = request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName();
        String origin = UriComponentsBuilder.fromUriString(request.getRequestURL().toString())
                .replacePath(null).replaceQuery(null).build().toUriString();
        List<Row> rows = documents.findAll().stream().map(d -> {
            String doc = JpaDocumentProvider.MOUNT + "/" + d.getName();
            String signedUrl = origin + signedUrls.path(user, doc);
            String plainUrl = origin + "/webdav/" + PercentCodec.encodePath(doc);
            return new Row(d, signedUrl,
                    officeScheme(d.getName()) + ":ofe|u|" + signedUrl,
                    officeScheme(d.getName()) + ":ofe|u|" + plainUrl,
                    editorName(d.getName()));
        }).toList();
        model.addAttribute("rows", rows);
        model.addAttribute("ttl", signedUrls.ttl());
        return "index";
    }

    /** What the template renders: the entity plus its links, computed here (Thymeleaf forbids static calls). */
    public record Row(Document doc, String url, String editLink, String ofbaLink, String editor) {
    }

    /**
     * {@code ms-word:ofe|u|<http-or-https-url>} — "open for edit" (Microsoft Office URI Schemes). Picking
     * the scheme by extension is a client-side concern, so it lives here, not in the core.
     */
    static String officeScheme(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "xlsx", "xlsm", "xls", "csv" -> "ms-excel";
            case "pptx", "pptm", "ppt" -> "ms-powerpoint";
            default -> "ms-word";
        };
    }

    /** The application name behind the scheme, for the link text ("Edit in Excel"). */
    static String editorName(String name) {
        return switch (officeScheme(name)) {
            case "ms-excel" -> "Excel";
            case "ms-powerpoint" -> "PowerPoint";
            default -> "Word";
        };
    }
}
