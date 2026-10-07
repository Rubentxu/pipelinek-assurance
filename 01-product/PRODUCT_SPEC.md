# Product Specification — pipelinek-assurance

## Problema

El software moderno acumula mecanismos de verificación que no comparten una semántica común: unit tests, integration tests, linters, reglas arquitectónicas, mutation testing, coverage, traces, logs, runtime profilers, security scanners y análisis heurísticos. Cada herramienta publica un formato y suele decidir por sí misma cuándo algo es error.

El resultado es una colección de señales difíciles de componer y todavía más difíciles de explicar a humanos o agentes.

## Propuesta

`pipelinek-assurance` convierte estas fuentes en un argumento verificable:

```text
Evidence Sources -> EvidenceSnapshot -> Lenses -> Assertions -> Counterexamples -> Verdict
```

PipelineK aporta el execution graph y el lifecycle durable del argumento.

## Usuarios

### Desarrollador

Quiere ejecutar localmente la misma suite que bloquea el pipeline y recibir un contraejemplo concreto.

### Arquitecto

Quiere expresar constraints de arquitectura, seams, acoplamiento, connascence y observabilidad como tests versionados.

### Agente LLM

Quiere una API CLI autodescubrible que indique qué falló, por qué, qué evidencia lo respalda y qué comando sigue, sin interpretar logs arbitrariamente.

### Release operator

Quiere saber qué propiedades necesarias para release están probadas, cuáles son inconclusas y cuáles han regresado.

## Jobs to be done

- probar arquitectura hexagonal/onion;
- congelar deuda y bloquear sólo regresiones nuevas;
- verificar ausencia/presencia de ciclos y dependency paths;
- gobernar smells deterministas y señales heurísticas sin mezclarlos;
- demostrar causalidad runtime con Chronos;
- validar cobertura de observabilidad con OTel;
- relacionar tests, mutantes y production symbols;
- construir `RequiredAssurancePlan` para cambios;
- comparar base/current;
- reproducir contraejemplos.

## Non-goals V1

- UI gráfica;
- SaaS remoto;
- LLM como juez;
- refactor automático;
- nuevo graph database;
- replacement de CogniCode/Chronos/Detekt;
- reimplementación de JUnit/Kotest;
- scoring mágico de calidad.
