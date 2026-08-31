# Third-party notices

The Maven build generates a CycloneDX runtime inventory at `target/classes/META-INF/sbom/application.cdx.json`. It records packaged Java components and their declared license metadata.

Primary runtime components are:

- Spring Boot and Reactor, Apache License 2.0
- Envoy Java control-plane API and gRPC Java, Apache License 2.0
- Protocol Buffers, BSD 3-Clause License
- Envoy Proxy 1.39.0 container image, Apache License 2.0
- Eclipse Temurin Java 21 runtime container image, GPL-2.0 with Classpath Exception and related notices

Test-only components such as Selenium are not packaged in the application image. The dashboard uses repository-owned HTML, CSS, and JavaScript and loads no third-party code from a CDN.

No OpenAI Codex source, patched App Server binary, Anthropic SDK, or source/assets from the removed reference chat application are included in this replacement.
