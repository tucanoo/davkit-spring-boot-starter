# Changelog

DavKit components use the same exact version and release together.

## 1.0.12 — 2026-10-27

- Updated the core dependency to 1.0.12, fixing empty 404 responses for ordinary Office
  application requests outside `davkit.path`, including Outlook email images.
- Added HTTP regression coverage for Office image/page delivery, host login and CSRF
  enforcement, and preserved root and SharePoint discovery responses.
- Documented application-wide discovery filter registration and unchanged HEAD handling.

## 1.0.11 — 2026-10-10

- Added a startup WARN when the actual `ServletContext` has a non-root context path,
  including container-assigned WAR context paths. Office sends discovery requests to the
  origin's `/`, so deploy at the root context for Office editing.
- Updated the core dependency to 1.0.11, which adds
  `SignedUrls.path(subject, documentPath, Duration)` for per-link validity. The configured
  default TTL and percent-encoding behaviour remain unchanged.
- Documented per-link validity, startup logging configuration and the requirement to
  configure `davkit.enabled` before auto-configuration runs.

## 1.0.10 — 2026-09-12

- Exited beta.
