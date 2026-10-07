# Guía de implementación

## Disciplina

- SDDK primero.
- `ROADMAP.md` única autoridad.
- work units verticales, no infraestructura especulativa.
- surgical tests durante desarrollo; full gate sólo integración/release.
- Conventional Commits.
- cada hito termina con UAT ejecutado sobre distribución/CLI real cuando aplique.
- cada nuevo detector/assertion requiere mutante o fixture negativo que lo tumbe.

## Bootstrap sugerido

Módulos lógicos iniciales:

```text
assurance-domain
assurance-engine
assurance-dsl
assurance-artifact
assurance-testkit
pipelinek-assurance-plugin
assure-cli
```

No crear más módulos físicos hasta que una frontera lo necesite.

## Regla de arquitectura emergente

No anticipar un generalized query language, graph DB o daemon. Extraer nuevas abstracciones sólo después de dos consumidores reales.
