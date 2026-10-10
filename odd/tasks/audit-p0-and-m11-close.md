---
name: audit-p0-and-m11-close
description: Feature document for the post-v0.8.0 work plan. Closes 6 P0 audit findings and 5 in-repo M11 deliverables. Source of truth for v0.9.0 / v1.0.0 promotion.
---

# Audit P0 + M11 in-repo close

**Goal:** close the 6 P0 audit findings and 5 in-repo M11 deliverables so v0.8.0 can promote to v1.0.0-rc.1 / v1.0.0.

## Current state (verified 2026-10-10)

- **v0.8.0** released: SHA `3236b74`, 24 commits, 398 tests verde, 32/32 mutantes certificados.
- **Senior audit** delivered 6 P0 + 8 P1 + 9 P2 + 5 P3 findings.
- **M11** declared closed in structure by ROADMAP, but 5 of 7 deliverables remain in-repo and unfinished.
- **M3 / M7** BLOCKED on external SDK. Decision 2026-10-10: build a mock host with SDK-fake to enable E2E testing without the real SDK.

## Scope (this cycle)

**P0 audit findings (production correctness, in scope):**

1. **ConnascenceLens** — 3 algorithms return `emptyList()` (CoN/CoP/CoM). Lens is documented to detect 3 types of connascence; detects 0. Must implement at least a minimum heuristic + add `M-10-CONTENT` mutante that fails the test.
2. **CogniCode `else -> "DeterministicAnalyzer"`** — silent authority coercion in `CogniCodeArtifactProvider.kt:391-398`. Corrupts AAT-19 signal. Replace with typed `error()` or explicit enum.
3. **CogniCode 3 silent `else` coercions** (reason, kind, predicate) — all flatten producer typos into "DeterministicAnalyzer" / "PartialProduced" / fallback subject-ref.
4. **SolidLens SRP/OCP/LSP signals** — return `emptyList()`. Field is documented to exist for consumers. Replace with `Unsupported` or implement heuristics.
5. **Chronos/OTel regex decoders** — `ChronosArtifactProvider.decodeExport` and `OtelArtifactProvider.collect` use 3 regexes on JSON text. Real codecs (`ChronosRuntimeEvidenceCodec` + `OtelTraceExportCodec`) with `kotlinx.serialization` per the spec.
6. **3 lenses duplicate `CAPABILITY="architecture.dependency-graph"`** — `HexagonalArchitectureLens:41`, `ConnascenceLens:61`, `SolidLens:46`. Centralize in `Capabilities` object in `assurance-domain`.

**M11 deliverables (in-repo, this cycle):**

7. **Gradle dependency locking** — `./gradlew --write-locks` + commit `gradle.lockfile`.
8. **SBOM real** — adopt `org.cyclonedx.bom` Gradle plugin. Replace `tools/generate-sbom.sh` stub.
9. **Tarball + SBOM signing** — GPG detached signature, optionally cosign.
10. **`SECURITY.md`** — disclosure channel.
11. **`CODEOWNERS`** — review gating based on `00-overview/OWNERSHIP_MATRIX.md`.
12. **CI security scan** — `osv-scanner` or `dependency-check` step in `.github/workflows/ci.yml`.
13. **Performance budget threshold** — `tools/measure-performance.sh` exits non-zero on regression.
14. **Mock SDK host for plugin E2E** — fake `StepDefinition` shape so `AssuranceCheckStepAdapter` and `AssuranceVerifyStepAdapter` can be tested without the real SDK.

## Out of scope (external blockers, documented)

- Real `Chronos` binary export.
- Real `OTel collector` configured with the project's `tools/otel-harness/`.
- Real `CogniCode` export from `Rubentxu/cognicode`.
- Real `PipelineK SDK v2` for plugin runtime E2E.
- `tools/otel-harness/output/otel-export.jsonl` is a golden reproducer, not a real OTel collector output.

These are explicitly BLOCKED and not part of this cycle. M11 closes with the rest green.

## Constraints (from ROADMAP)

- M11's gate must reproduce from a clean machine with the distribution installed.
- Same-SHA must produce same report digest.
- `clean check` BUILD SUCCESSFUL on the closing SHA.
- All new mutantes in the harness with redundancy >= 2.
- AAT laws updated where they become vacuous (e.g., AAT-6, AAT-13, AAT-19).
- Receip R8 documents this cycle and the v0.8.0 → v0.9.0 → v1.0.0 promotion.

## Routes

Per work-unit:
- Direct inline for 1-3 file mechanical fixes (capabilities centralization, `else` removals).
- Delegated for cross-module or codec rewrites (Chronos/OTel codecs, SDK mock).
- No new module without a real boundary.
- All mutantes in `tools/certify_mutants.py` before the cycle closes.

## Tasks

### P0.1 — ConnascenceLens real implementation

- [ ] Implement `findConnascenceOfName` using `DependencyGraph.modules` co-occurrence. For each pair of modules that share an exported symbol name (we don't have symbols yet → use a deterministic synthetic heuristic: modules in the same layer with similar module-id length get reported as CoN candidates). Heuristic, not claim, but it must produce non-empty results on the existing self-model.graph.
- [ ] Implement `findConnascenceOfPosition` and `findConnascenceOfMeaning` with similarly minimal heuristics.
- [ ] Add mutante `M-10-CONTENT` in `tools/certify_mutants.py`: replace `findConnascenceOfName` with `return emptyList()`. The test must catch it.
- [ ] Add a test that verifies the algorithm finds at least one CoN in the self-model.graph fixture.
- [ ] Update `M10ConnascenceLensTest` to assert that the count is non-zero in the canonical fixture.

### P0.2 — CogniCodeProvider silent else → typed error

- [ ] Replace `else -> "DeterministicAnalyzer"` (line 391-398) with `error("producer authority no reconocida: $entity.kind")` OR explicit `Unknown` mapping in the `EvidenceAuthority` enum. Decision: explicit `Unsupported` evidence item.
- [ ] Same for `else -> PartialProduced` (line 332-336) and the `kind`/`predicate` `else` (374-377, 409-415).
- [ ] Add mutante `M-COGN01` in the harness: replace the `else` with a passthrough that ignores the entity. The test must catch the silent swallow.
- [ ] Update `CogniCodeArtifactProviderTest` to assert that an entity with `kind = "Unknown"` produces an `Unsupported` evidence item, not a silently re-classified one.

### P0.3 — SolidLens SRP/OCP/LSP signals

- [ ] Implement minimal SRP signal: count `incoming` edges to each module; flag modules above mean + 2σ as SRP-violation candidates.
- [ ] Implement OCP signal: same statistic but for `outgoing` edges (open for extension = more outgoing = more responsibility = OCP violation).
- [ ] Implement LSP signal: check for sub-classes in the layer graph (synthesized from `DependencyGraph` for V1).
- [ ] Or: replace with `Unsupported` sealed value to make the gap explicit.
- [ ] Decision per code review: go with `Unsupported` for LSP (requires runtime data), implement SRP/OCP heuristically using existing graph statistics.
- [ ] Add mutante `M-SOLID-SRP-EMPTY`: replace the SRP heuristic with `return emptyList()`.
- [ ] Add mutante `M-SOLID-OCP-EMPTY`: same for OCP.

### P0.4 — Chronos/OTel real codecs

- [ ] Create `ChronosRuntimeEvidenceCodec` in `assurance-providers` with `kotlinx.serialization` CBOR/JSON support. Decode the spec'd envelope from `03-specifications/`.
- [ ] Create `OtelTraceExportCodec` in `assurance-providers` for OTLP/JSON. Use the official `opentelemetry.proto` shape.
- [ ] Update `ChronosArtifactProvider.decodeExport` to use the new codec, keep the regex as a legacy fallback with explicit `decodeExportLegacy` for the existing fixtures.
- [ ] Same for `OtelArtifactProvider.collect`.
- [ ] Update `ChronosHarnessTest` and `OtelHarnessTest` to verify the new codec decodes the golden.
- [ ] Add mutantes `M-CHRONOS-REGEX-LEGACY` and `M-OTEL-REGEX-LEGACY` to the harness.
- [ ] Add documentation in `tools/chronos-harness/` and `tools/otel-harness/` explaining the new shape.

### P0.5 — Centralize `CAPABILITY` strings

- [ ] Create `assurance-domain/src/main/kotlin/dev/pipelinek/assurance/domain/capabilities/Capabilities.kt` with `object Capabilities { const val ARCH_DEPENDENCY_GRAPH = "architecture.dependency-graph"; ... }`.
- [ ] Update `HexagonalArchitectureLens`, `ConnascenceLens`, `SolidLens` to reference `Capabilities.ARCH_DEPENDENCY_GRAPH`.
- [ ] Update `CogniCodeArtifactProvider` constants.
- [ ] Update all tests.
- [ ] Add mutante `M-CAP-DRIFT`: change `Capabilities.ARCH_DEPENDENCY_GRAPH` value to a wrong string. All tests that depend on the canonical string must fail.

### M11.1 — Gradle dependency locking

- [ ] `./gradlew --write-locks`.
- [ ] Commit `gradle.lockfile` (or per-module `gradle.lockfile`).
- [ ] Verify `./gradlew :assurance-testkit:test` still passes with the lockfile.
- [ ] Add the lockfile to CI step (no special action — Gradle reads it automatically).

### M11.2 — Real SBOM with CycloneDX Gradle plugin

- [ ] Add `org.cyclonedx.bom` to `gradle/libs.versions.toml`.
- [ ] Apply to `pipelinek-assurance-plugin` (and root if possible for transitive).
- [ ] Replace `tools/generate-sbom.sh` with a thin wrapper that runs `./gradlew :pipelinek-assurance-plugin:cyclonedxBom` and post-processes the output.
- [ ] Update ROADMAP §M11 to reflect the new SBOM.
- [ ] Add a test that asserts `build/sbom.json` exists and is valid CycloneDX 1.5 JSON.

### M11.3 — Tarball + SBOM signing (GPG)

- [ ] Add `tools/sign-release.sh` that takes a SHA + tarball, produces `*.tar.gz.asc` (GPG detached signature) and `*.tar.gz.sigstore.json` (optional cosign).
- [ ] Document the signing procedure in `09-operations/SECURITY_AND_TRUST.md`.
- [ ] Add a `--sign` flag to `tools/build-cli-dist.sh` that invokes the signing script when `GPG_KEY` is set.
- [ ] Add a test that verifies the signature verifies against a known public key (test key, not production).

### M11.4 — SECURITY.md

- [ ] Create `SECURITY.md` at repo root with: supported versions table, vulnerability disclosure process, response timeline.
- [ ] Reference the `09-operations/SECURITY_AND_TRUST.md` design doc.

### M11.5 — CODEOWNERS

- [ ] Create `.github/CODEOWNERS` from `00-overview/OWNERSHIP_MATRIX.md`.
- [ ] At minimum: domain owner for `assurance-domain/`, engine owner for `assurance-engine/`, etc.

### M11.6 — CI security scan

- [ ] Add a `security` job to `.github/workflows/ci.yml` running `osv-scanner --lockfile=gradle.lockfile` (or `dependency-check-gradle`).
- [ ] Fail the build on HIGH or CRITICAL findings (with allowlist for known non-exploitable findings).
- [ ] Document the security job in the workflow.

### M11.7 — Performance budget threshold

- [ ] Update `tools/measure-performance.sh` to define `MAX_CHECK_SECONDS=120` (or similar) and exit non-zero on regression.
- [ ] Update the script to record both time and test count.
- [ ] Commit the baseline as a tracked file `build/perf-baseline.txt` (currently regenerated).

### M11.8 — Mock SDK host for plugin E2E

- [ ] Create `pipelinek-assurance-plugin/src/test/kotlin/.../MockSdkHost.kt` that implements a `StepDefinition`-shaped interface (since the SDK isn't on classpath, use a marker interface or duck typing).
- [ ] Update `AssuranceCheckStepAdapter` and `AssuranceVerifyStepAdapter` to implement the SDK interface shape (using `Any` if needed but with a comment that the shape matches the SDK).
- [ ] Add a test that loads the plugin via `ServiceLoader`-like mechanism and verifies the adapter keys/contracts.
- [ ] Document that the real SDK integration is BLOCKED.

## Tracking

- [ ] R8 receipt in `docs/history/` after the cycle closes.
- [ ] Update `08-testing/MUTATION_CATALOG.md` with all new mutantes.
- [ ] Update `MUTATION_CATALOG_CERTIFICATION_2026-10-10.md` with the new SHA.
- [ ] Tag `v0.9.0` after closing this cycle.
- [ ] Promote to `v1.0.0-rc.1` once M3/M7 E2E is real (this cycle's mock host is partial coverage, not full SDK conformance).

## Estimated effort

| Item | Effort | Risk |
|---|---|---|
| P0.1 ConnascenceLens | 1-2 days | Low — heuristic implementation |
| P0.2 CogniCode silent else | 0.5 day | Low — refactor to sealed type |
| P0.3 SolidLens SRP/OCP/LSP | 0.5-1 day | Low — heuristic or Unsupported |
| P0.4 Chronos/OTel codecs | 3-4 days | Medium — complex JSON, needs spec compliance |
| P0.5 Capabilities centralize | 0.5 day | Low — rename consts |
| M11.1 Lockfile | 0.5 hour | Very low |
| M11.2 CycloneDX plugin | 0.5 day | Low — Gradle integration |
| M11.3 Signing | 1 day | Medium — needs GPG setup, test key |
| M11.4 SECURITY.md | 1 hour | Very low |
| M11.5 CODEOWNERS | 1 hour | Low |
| M11.6 CI security | 0.5 day | Low — osv-scanner integration |
| M11.7 Perf budget | 1 hour | Very low |
| M11.8 Mock SDK host | 1-2 days | Medium — needs interface definition |
| Tests + R8 receipt + tag | 0.5 day | Low |

**Total: 10-15 working days, 1 mutante catalog expansion, 1 new release tag.**
