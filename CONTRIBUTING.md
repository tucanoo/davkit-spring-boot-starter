# Contributing

Report reproducible bugs in [GitHub issues](https://github.com/tucanoo/davkit-spring-boot/issues), or
send a pull request with the reason for the change and the checks you ran. Include the DavKit,
Java and framework versions, expected behaviour, actual behaviour and a small reproducer.
Use [SECURITY.md](SECURITY.md) for vulnerabilities; do not disclose them in public issues.

## Build and check

Use Java 17 and the included Gradle wrapper. Read the [README](README.md) before building:
this prerelease checkout needs matching core binaries, which are not yet
available from Maven Central. Ask [dave@tucanoo.com](mailto:dave@tucanoo.com) about binary access
and repository setup. Proprietary core source is not required for contributions to this
wrapper, and requesting an evaluation key does not install its dependencies.

From this repository root, run:

```sh
./gradlew build
```

Automated tests need no runtime licence key or local HTTPS certificate. Keep changes focused,
and include a regression test when changing behaviour. Describe any checks you could not run.
Do not commit licence keys, signed document URLs, certificates, credentials or customer files.

The source in this repository is Apache 2.0. The separately distributed DavKit core has its own
proprietary licence; do not copy its source into a contribution to this repository.

## Before a public release

All DavKit components must use the same exact version. Before announcing a binary release,
maintainers still need to publish matching artifacts and verify both wrapper builds from
isolated checkouts using only those binaries, without sibling source builds or preinstalled
DavKit artifacts in Maven Local. Record the versions and verification results in the release
notes. There is currently no checked-in CI workflow; these checks must also be automated.
