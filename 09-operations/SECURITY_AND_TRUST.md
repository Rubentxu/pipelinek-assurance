# Seguridad, confianza y supply chain

## Threat boundaries

- external plugin JAR;
- provider artifacts no confiables/malformed;
- source paths;
- SARIF/CBOR/JSON bombs;
- agent-provided baselines;
- LLM hypotheses;
- untrusted repository content.

## Reglas

- bounded decoding;
- schema/version fail-closed;
- no deserialización JVM arbitraria;
- canonical CBOR/JSON, no Java serialization;
- digests verificados;
- plugins declarative capabilities;
- report rendering escapes terminal/control sequences donde aplique;
- source paths normalized and workspace-scoped;
- no automatic code execution from evidence payload.

## Trust

Evidence provenance describe origen, no implica trust. Plugin manifest `Unverified` no se eleva por assurance.

## Supply chain

Release debe incluir SBOM, checksums, provenance y compat matrix con PipelineK SDK.
