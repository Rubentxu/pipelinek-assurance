# SPEC — Artifact wire contracts

## Family 1 — Evidence

Media types propuestos:

```text
application/vnd.pipelinek.assurance.evidence+cbor;version=1
application/vnd.pipelinek.assurance.evidence+json;version=1
```

Envelope:

```text
EvidenceArtifact
  apiVersion
  kind = EvidenceSnapshot
  producer
  subject
  manifest
  payload
  digest
```

## Family 2 — Suite

```text
application/vnd.pipelinek.assurance.suite+cbor;version=1
```

## Family 3 — Report

```text
application/vnd.pipelinek.assurance.report+cbor;version=1
```

## Family 4 — Baseline

```text
application/vnd.pipelinek.assurance.baseline+cbor;version=1
```

## Forward compatibility

- unknown required field semantics -> refuse;
- unknown optional metadata -> preserve/ignore according to schema;
- unknown evidence kind -> Unsupported with gap;
- schema upgrade requires golden corpus old->new.

## Safety

- length bounds antes de allocation;
- nesting depth bounds;
- collection count bounds;
- no executable class names;
- no polymorphic JVM deserialization;
- no URLs auto-fetched during decode.

## Provenance

Producer version y input revision forman parte del manifest; no implican trust.
