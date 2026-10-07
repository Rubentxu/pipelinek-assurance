# Rendimiento y escala

## No budgets inventados

Primero medir:

- snapshot size;
- normalization throughput;
- lens projection CPU/allocation;
- assertion count scaling;
- graph edge scaling;
- report size;
- CLI explain latency;
- provider artifact decode.

## Fixtures

- 10k / 100k / 1M entities;
- 50k / 500k / 5M relations;
- 100 / 1k / 10k assertions synthetic;
- Chronos sessions con 1M events;
- OTel traces grandes.

## Reglas

- O(window) para paged evidence APIs;
- no graph duplication per lens si shareable immutable indexes existen;
- indexes derivan de snapshot y son cacheables por digest;
- slow CLI renderer no cambia verdict;
- report payload grande via artifact, no event.

## Optimización emergente

No introducir graph DB hasta que benchmarks demuestren que immutable in-memory/indexed representation es insuficiente para casos objetivo.
