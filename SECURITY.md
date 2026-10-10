# Security Policy

This document is the entry point for **vulnerability disclosure** against
`pipelinek-assurance`. It exists alongside `09-operations/SECURITY_AND_TRUST.md`,
which describes the design-level threat model and trust boundaries. The
`SECURITY.md` is the operational contact surface: who to contact, what to
expect, and what the policy of supported versions is.

## Supported versions

| Version | Status | Security fixes until |
|---|---|---|
| `v0.8.x` | Current | Until `v0.10.0` is released (~3 months) |
| `v0.7.x` | Maintenance | Until `v0.9.0` is released (~1 month) |
| `v0.6.x` and earlier | End of life | No backports |

The project follows **semver**. Patch releases on supported minor versions
include security fixes; minor and major releases do not. Releases are tagged
in git (`v0.8.0`, `v0.7.0`, …) and published as GitHub releases with
SHA-256 checksums. The source of truth is the `origin` git remote at
`https://github.com/Rubentxu/pipelinek-assurance`.

## How to report a vulnerability

**Email:** open a private issue via GitHub Security Advisories at
`https://github.com/Rubentxu/pipelinek-assurance/security/advisories/new`.
**Do not** open a public issue for a vulnerability before the disclosure
window closes.

When you report, please include:

- A clear description of the issue and its impact.
- Reproduction steps (commit SHA, input sample, observed output).
- Whether the issue is **fail-open** (the tool accepts a malformed input that
  should be rejected) or **fail-closed** (the tool rejects a valid input
  that should be accepted). The two have different severity.
- Your environment (OS, JDK 21 distribution, gradle version).
- A contact channel if you want a follow-up.

PGP is not currently in use; if you need it for sensitive disclosures,
request it in the GitHub Security Advisory and a key will be generated.

## Response timeline

| Phase | SLA |
|---|---|
| Acknowledgement | 5 working days |
| Triage (severity assigned) | 10 working days |
| Fix for CRITICAL / HIGH | 30 working days |
| Fix for MEDIUM | 60 working days |
| Fix for LOW | 90 working days |
| Public advisory | After the fix is released, or 90 days after report, whichever comes first |

A **CRITICAL** vulnerability is one that allows remote code execution,
bypass of a `Mandatory` gate, or extraction of private keys. A **HIGH**
vulnerability is one that allows arbitrary file writes, bypass of the
`Forbidden` layer policy, or silent data corruption in the report. A
**MEDIUM** is one that leaks data without compromising integrity.
Everything else is **LOW**.

## Scope

In scope for `pipelinek-assurance`:

- The `assure-cli` binary and its tarballs.
- The `pipelinek-assurance-plugin` JAR and its `META-INF/services` declaration.
- The CBOR/JSON wire contracts documented in `03-specifications/ARTIFACT_WIRE_CONTRACTS.md`.
- The provider adapters in `assurance-providers/` (Chronos, OTel, CogniCode,
  Detekt SARIF, JUnit XML, JaCoCo, Pitest).
- The mutation harness `tools/certify_mutants.py`.
- The CI workflow `.github/workflows/ci.yml` and the SBOM generator
  `tools/generate-sbom.sh`.

Out of scope (handled by their respective projects):

- Vulnerabilities in upstream `kotlinx-serialization`, Kotest, JUnit, or
  any other Gradle dependency. Report those to the upstream project.
- Vulnerabilities in the PipelineK SDK. Report those to the PipelineK
  project.
- Vulnerabilities in the actual OTel collector, Chronos backend, or
  CogniCode analyzer. Report those to the upstream projects.

## Threat boundaries (summary)

The full threat model lives in `09-operations/SECURITY_AND_TRUST.md`. In
short:

- **Evidence inputs are untrusted.** Provider inputs (SARIF, JUnit XML,
  Chronos exports, OTel exports, CogniCode exports) are decoded with
  bounded limits (`MAX_STRING_LENGTH`, `MAX_COLLECTION_SIZE`,
  `MAX_NESTING_DEPTH`, `MAX_INPUT_BYTES`) and fail-closed on any violation.
  XML parsing uses `FEATURE_SECURE_PROCESSING` and disables external
  entities.
- **No JVM deserialization.** All artifacts use `kotlinx.serialization`
  with explicit `@Serializable` DTOs. The CBOR/JSON wire format is the
  only deserialization path.
- **Canonical encoding.** Digests are computed on a canonical form
  (sorted keys, sorted maps) and verified at decode time. An envelope
  with an altered payload cannot pass the digest check.
- **Source paths are normalized and workspace-scoped.** Path traversal
  vectors are bounded.
- **No automatic code execution from evidence payloads.** Evidence items
  do not contain code; they contain `objectValue: String` and structured
  maps only.

## Trust model

- **Provenance describes origin, not trust.** An evidence item declared
  as `HeuristicAnalyzer` does not become trustworthy because it is in a
  snapshot.
- **Provider manifests are declarations, not capabilities.** A provider
  declaring `evidenceCapabilities = [...]` does not mean it has those
  capabilities until its output is verified against the spec.
- **Plugin manifests with `Unverified` are not elevated by assurance.**
  A pipeline author must explicitly grant a plugin authority.

## Security-relevant build settings

- All Gradle dependency versions are pinned in `gradle/libs.versions.toml`
  (no SNAPSHOT, no dynamic versions).
- `settings-gradle.lockfile` is committed to provide reproducible
  transitive resolution.
- SBOM is generated at `build/sbom.json` (CycloneDX 1.5).
- CI runs `./gradlew --no-daemon clean check` on every push and PR.
- OSV-Scanner is run on the lockfile in CI (target: M11 close).

## Disclaimers

This is a **security policy**, not a warranty. The project is provided
"as is" under its license. No claim is made that the tool is bug-free or
that all vulnerabilities will be caught before production. The audit
catalog (`08-testing/MUTATION_CATALOG.md`) and the fitness functions
enforce a baseline of correctness, but they are not a substitute for
production-grade observability and human review.

## See also

- `09-operations/SECURITY_AND_TRUST.md` — design-level threat model.
- `08-testing/MUTATION_CATALOG.md` — mutations the harness kills.
- `00-overview/OWNERSHIP_MATRIX.md` — who owns which module.
- `MUTATION_CATALOG_CERTIFICATION_2026-10-10.md` — last full sweep.
