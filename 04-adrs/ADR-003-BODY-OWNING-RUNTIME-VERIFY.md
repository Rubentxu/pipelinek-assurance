# ADR-003 — Runtime verification uses a body-owning external Step

**Status:** ACCEPTED

`assurance.verify` baja a `RegistryBlockSpec`, declara `HANDLER_CONTINUATION` y ejecuta el body únicamente por `BodyContinuation`.

Cancelación se propaga. Un body failure nunca se reetiqueta como assurance failure. El report se conserva como evidencia secundaria.
