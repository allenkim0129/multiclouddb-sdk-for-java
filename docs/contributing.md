# Contributing

Thank you for your interest in contributing to the Multicloud DB SDK!

---

## Code of Conduct

This project has adopted the
[Microsoft Open Source Code of Conduct](https://opensource.microsoft.com/codeofconduct/).
See the [FAQ](https://opensource.microsoft.com/codeofconduct/faq/) or contact
[opencode@microsoft.com](mailto:opencode@microsoft.com) with questions.

---

## Contributor License Agreement (CLA)

This project requires that contributors sign a **Contributor License Agreement**
before any pull request can be merged. When you submit a PR, the Microsoft CLA
bot will guide you through the process automatically.

Sign at: [https://cla.opensource.microsoft.com](https://cla.opensource.microsoft.com)

---

## Setting Up the Development Environment

### Prerequisites

| Tool | Minimum Version |
|------|----------------|
| JDK  | 17 (LTS)       |
| Maven | 3.9+          |
| Docker | 20+ (optional - Spanner emulator) |

### Clone and Build

```bash
git clone https://github.com/<your-username>/multiclouddb-sdk-for-java.git
cd multiclouddb-sdk-for-java
mvn clean install -DskipTests
```

### Local Provider Emulators

| Provider | Emulator |
|----------|----------|
| Azure Cosmos DB | [Azure Cosmos DB Emulator](https://aka.ms/cosmosdb-emulator) |
| Amazon DynamoDB | [DynamoDB Local](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html) |
| Google Cloud Spanner | `docker run -p 9010:9010 gcr.io/cloud-spanner-emulator/emulator` |

---

## Building and Testing

```bash
# Build all modules (skip tests)
mvn clean install -DskipTests

# Run unit tests
mvn test

# Run unit tests for a specific module
mvn test -pl multiclouddb-provider-cosmos

# Run a specific test class
mvn test -pl multiclouddb-api -Dtest=CapabilityTest
```

### Integration / Conformance Tests

Requires local emulators to be running:

```bash
# Run conformance tests against all running emulators
mvn test -pl multiclouddb-conformance

# Run for a single provider
mvn test -pl multiclouddb-conformance -Dtest=CosmosConformanceTest
mvn test -pl multiclouddb-conformance -Dtest=DynamoConformanceTest
mvn test -pl multiclouddb-conformance -Dtest=SpannerConformanceTest
```

### Cosmos v4 packaging checks

The Cosmos source graph includes the native SDKs. The published JAR bundles their
relocated runtime closure; its consumer POM retains API, SLF4J, JNA 5.13.0 and
jna-platform 5.17.0. JNA is shared, not isolated from application versions.
Never test only `target/classes` when validating publication.

Native Cosmos mock tests live in the provider module and run before shading;
mocking original Azure classes from a downstream module would not intercept
the private packaged classes. For focused mapping and lifecycle coverage:

```powershell
mvn -Punit -pl multiclouddb-conformance -am "-Dtest=CosmosMapContractTest,CustomerObjectMappingTest,NativeWriteMappingTest,SpannerJsonTest,DocumentJsonSizeTest,QueryPageTest" "-Dsurefire.failIfNoSpecifiedTests=false" clean package
```

Use a fresh local file publication and a separate consumer Maven cache. For
example, from the repository root with Java 17:

```powershell
$work = Join-Path $env:TEMP ("mcdb-v4-" + [guid]::NewGuid())
New-Item -ItemType Directory $work | Out-Null
$publication = ([uri](Join-Path $work "published")).AbsoluteUri
mvn -pl multiclouddb-provider-cosmos -am -DskipTests "-Dmaven.repo.local=$work\build-repository" "-DaltDeploymentRepository=validation::$publication" deploy
$fixture = "multiclouddb-provider-cosmos\src\it\packaging"
mvn -f "$fixture\pom.xml" "-Dvalidation.repository=$publication" "-Dmaven.repo.local=$work\consumer-repository" "-Dmdep.outputFile=$work\clean-classpath.txt" package org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath
$cp = (Get-Content "$work\clean-classpath.txt" -Raw).Trim()
java -cp "$fixture\target\classes;$cp" probe.Consumer
```

Repeat consumer resolution with `-Pcollisions`, a distinct classpath output file
and `probe.Consumer collisions`. The application fixture deliberately uses
different Azure/Jackson/Netty/Reactor versions. Compare built, installed,
file-deployed and freshly consumed JAR/POM hashes; inspect the consumed graph,
not only source declarations. No cloud credentials are needed.

Compile `src\it\packaging\module-src\module-info.java` with `--patch-module
probe.consumer=<fixture classes>` and the consumed module path, package it with
those classes, then run `probe.Consumer public` (or `public collisions`) using
`--add-modules ALL-MODULE-PATH`. These checks require explicit JPMS, provider
discovery and no public exports, without opening private packages.

Run the native consumer on both Windows and Linux. On Linux,
`probe.NativeTransportProbe [collisions]` executes and closes actual epoll event
loops. `src\it\http2-fixture` is a separate, loopback-only HTTP/2 server; supply a
generated test PKCS12 certificate with password `probe-only`, then run
`probe.KeyModeProbe <test-keystore> <loopback-endpoint>` from the consumed
classpath/module path. Require `NEGOTIATED_PROTOCOL h2` and an h2 request in the
server log, and stop the fixture afterward. It uses a synthetic account and
dummy key, not real Cosmos authentication or CRUD. Keep these tests bounded and
network-isolated; do not substitute cloud/emulator credentials.

---

## Submitting a Pull Request

1. **Fork** the repository and create a feature branch from `main`.
2. Make your changes, ensuring:
    - All existing tests pass (`mvn test`).
    - New behaviour is covered by unit tests.
    - Public API changes include Javadoc.
3. **Sign the CLA** (the bot will prompt you automatically).
4. Submit the PR against `main` with a clear description of what changed and why.

### PR Checklist

- [ ] `mvn clean install` passes
- [ ] New or changed public API methods have Javadoc
- [ ] Tests added or updated to cover the change
- [ ] No provider-specific types on the `com.multiclouddb.api.*` surface
- [ ] `docs/compatibility.md` updated if capabilities changed
- [ ] CLA signed

---

## Coding Guidelines

- **Java 17** language level across all modules.
- Follow standard Java naming conventions.
- Follow each surface's ownership contract: point-read/feed containers are
  detached and mutable; QueryPage is shallowly read-only. Do not impose a
  universal recursive snapshot or read-only policy on document Maps.
- Avoid Jackson or provider-specific types on the public API surface.
- Keep the public API surface minimal.
- Provider adapters must not bleed provider-specific details into the portable
  API layer.

---

## Reporting Issues

!!! warning "Security vulnerabilities"

    Do NOT report security issues via GitHub Issues. See the
    [SECURITY.md](https://github.com/microsoft/multiclouddb-sdk-for-java/blob/main/SECURITY.md)
    file for the responsible disclosure process.

Before opening a new issue:

1. Search existing issues to avoid duplicates.
2. Include: SDK version, provider, Java version, OS, minimal reproducible code,
   full stack trace (sanitised), and expected vs. actual behaviour.

---

## Requesting Features

Open a GitHub issue with the `enhancement` label describing:

- The use case and problem you are solving
- The proposed API shape
- Which providers should support the feature
- Whether the feature is portable or provider-specific
