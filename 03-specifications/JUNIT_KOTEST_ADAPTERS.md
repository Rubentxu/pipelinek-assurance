# SPEC — Adapters JUnit Platform y Kotest

## Autoridad

`AssuranceSuiteIR` y `AssuranceEngine` son autoridades. JUnit/Kotest sólo adaptan discovery/reporting/assertions.

## JUnit Platform

Un futuro `AssuranceTestEngine` puede descubrir suites y exponer cada assertion como test descriptor:

```text
Architecture
  domain-purity
  no-context-cycles
Runtime
  trace-propagation
```

Debe mapear:

- Passed -> success;
- Failed -> failure con counterexample;
- Inconclusive -> aborted/skipped con reason explícito (no success);
- Unsupported -> aborted;
- Error -> failed engine error.

## Kotest

Ofrecer matchers/extensions para testkit:

```kotlin
architectureSuite.evaluate(snapshot).shouldPass()
```

Y property-based tests del propio engine.

## Misma suite

Un digest de suite debe ser idéntico independientemente del adapter runner.
