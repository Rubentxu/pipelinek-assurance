# Catálogo inicial de mutaciones

| ID | Mutación | Debe matar |
|---|---|---|
| M-A01 | domain depende de adapter | domain-purity |
| M-A02 | eliminar una arista antes de SCC | cycle UAT |
| M-E01 | Partial se trata como Complete | completeness law |
| M-E02 | Hypothesis se acepta como Fact | epistemic law |
| M-H01 | heuristic signal marcado deterministic | authority law |
| M-B01 | NEW baseline finding clasificado EXISTING | ratchet |
| M-P01 | assurance failure sobrescribe body failure | verify outcome law |
| M-P02 | cancellation capturada como Failure | cancellation UAT |
| M-P03 | handler itera children fuera BodyContinuation | architecture fitness |
| M-C01 | Chronos gap ignorado | runtime incomplete |
| M-O01 | OTel missing span se trata como success | observability |
| M-I01 | TraceId e InvocationId comparten wrapper String sin tipo | identity fitness |
| M-R01 | report serializer no canonicaliza maps | digest reproducibility |
| M-R02 | digest ordena por clave sin desempate por contenido | digest reproducibility |
| M-R03 | report CBOR no canonicaliza results ni artifacts | forma canónica |
| M-R04 | report CBOR no canonicaliza correlations | forma canónica |
| M-J01 | envelope JSON vuelve al orden de declaración de kotlinx | orden canónico JSON |
| M-S01 | decoder no aplica las cotas al construir el dominio | bounded decoding |
| M-S02 | cota de longitud de cadena ausente | bounded decoding |
