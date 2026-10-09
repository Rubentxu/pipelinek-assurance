package dev.pipelinek.assurance.plugin

import dev.pipelinek.assurance.domain.evidence.Digest
import dev.pipelinek.assurance.engine.AssertionResult
import dev.pipelinek.assurance.engine.AssuranceSuiteIR
import dev.pipelinek.assurance.engine.AssuranceReport
import dev.pipelinek.assurance.engine.SuiteId

/**
 * M3 — Step `assurance.check`.
 *
 * Ref autoridad: `03-specifications/PIPELINEK_PLUGIN_CONTRACT.md` §`assurance.check`,
 * `07-integrations/PIPELINEK_WORKSTREAM.md` (P0..P3), `04-adrs/ADR-001-PIPELINEK-OWNS-EXECUTION.md`.
 *
 * **Estado actual:** la integración con el SDK de PipelineK está bloqueada
 * (M3 depende de un artefacto externo). Este archivo define el CONTRATO
 * del Step como tipos puros, que el SDK cableará cuando la dependencia
 * esté disponible. La diferencia entre el contrato y la integración es
 * deliberada: el contrato se testea sin SDK (lo que prueba que las
 * invariantes del Step se mantienen), y la integración se certifica en
 * M3 con UAT-008 (instalado en distribución real) y UAT-009 (gate
 * Mandatory bloquea antes del deploy).
 *
 * **Lo que la spec exige del Step:**
 *  - inputs tipados: `AssuranceCheckInput(suite, evidence, mode, completenessPolicy)`;
 *  - output tipado: `AssuranceCheckOutput(report, reportDigest, summary, outcome)`;
 *  - fingerprint de memoización = `digest(suite) + digest(evidence[]) + engine version`;
 *  - open registry fail-closed: un plugin desconocido falla cerrado;
 *  - cero ediciones en el core de PipelineK.
 *
 * **Lo que este archivo DECLARA:**
 *  - los inputs/outputs como data classes;
 *  - el fingerprint canónico;
 *  - un helper que computa el `StepOutcome` a partir del
 *    `AssuranceReport`, sin lógica de I/O (eso es del SDK cuando esté
 *    disponible).
 *
 * **Lo que este archivo NO DECLARA:**
 *  - ninguna `when (stepKey == "assurance.check")` (AAT-10);
 *  - ningún subtipo de `StepSpec` específico de assurance (PIPELINEK_WORKSTREAM);
 *  - ninguna escritura al plugin journal (PIPELINEK_WORKSTREAM);
 *  - ningún traversal de `StepNode` (AAT-11).
 */
object AssuranceCheckStep {

    /** El nombre canónico del Step. Lo usa el registry del SDK. */
    const val STEP_KEY: String = "assurance.check"

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    /**
     * Input del Step.
     *
     * `suite` es la IR reproducible (ASSURANCE_IR.md); el caller ya
     * conoce su digest, pero se pasa completa para que el step pueda
     * verificar su propia integridad antes de evaluar. `evidence` son
     * referencias a artefactos externos (no los items en sí) — el Step
     * recoge los items por su cuenta, vía los providers registrados.
     */
    data class Input(
        val suite: AssuranceSuiteIR,
        val evidence: List<EvidenceInputRef>,
        val mode: EnforcementMode = EnforcementMode.FailClosed,
        val completenessPolicy: CompletenessPolicy = CompletenessPolicy.RequireComplete,
    )

    enum class EnforcementMode {
        /** Falla el gate en Mandatory fail. */
        FailClosed,

        /** Reporta el Mandatory fail como Advisory, sigue adelante. */
        ReportOnly,
    }

    enum class CompletenessPolicy {
        /** Un gap de completeness en una capability exigida ⇒ Inconclusive + fail. */
        RequireComplete,

        /** Un gap de completeness se reporta pero no falla el gate. */
        ReportMissing,
    }

    /**
     * Referencia a un artefacto de evidence. El Step lo recoge por
     * `mediaType` + `digest`; el consumer decide cómo descargarlo.
     */
    data class EvidenceInputRef(
        val mediaType: String,
        val digest: Digest,
        val logicalRole: String,
    )

    // -----------------------------------------------------------------------
    // Output
    // -----------------------------------------------------------------------

    /**
     * Output del Step.
     *
     * `report` es un `ArtifactRef` al report artifact, con su digest
     * canónico. `summary` es el conteo por estado (Passed/Failed/etc.).
     * `outcome` es la decisión que el Step handler toma sobre el report
     * (success / failure / aborted) — es el **único** punto donde la
     * policy de "Mandatory fail ⇒ failure" se materializa. El core
     * nunca decide esto; lo hace el Step.
     */
    data class Output(
        val report: ReportArtifactRef,
        val reportDigest: Digest,
        val summary: AssuranceSummary,
        val outcome: StepOutcome,
    )

    /**
     * Referencia al report artifact. Sigue el shape de `ArtifactRef` del
     * engine pero se materializa aquí como tipo público del Step, para
     * que el SDK pueda consumirlo sin que el core le pase sus tipos
     * internos.
     */
    data class ReportArtifactRef(
        val mediaType: String,
        val digest: Digest,
        val logicalRole: String,
    )

    /** Conteo por estado del report. */
    data class AssuranceSummary(
        val passed: Int,
        val failed: Int,
        val inconclusive: Int,
        val unsupported: Int,
        val errored: Int,
    ) {
        val total: Int get() = passed + failed + inconclusive + unsupported + errored
    }

    /**
     * Outcome que el Step handler devuelve a PipelineK.
     *
     * `Success` cuando el gate pasa; `Failure` cuando un `Mandatory`
     * falla o cuando la `completenessPolicy = RequireComplete` se
     * incumple; `Aborted` cuando un `Unsupported` o `Error` no se
     * puede resolver. Estos tres son los únicos outcomes del step
     * — un `Inconclusive` se mapea a `Failure` cuando
     * `RequireComplete`, y a `Success` con warning cuando `ReportMissing`.
     */
    sealed interface StepOutcome {
        data object Success : StepOutcome
        data class Failure(val reason: String) : StepOutcome
        data class Aborted(val reason: String) : StepOutcome
    }

    // -----------------------------------------------------------------------
    // Fingerprint de memoización
    // -----------------------------------------------------------------------

    /**
     * Fingerprint del Step para replay (UAT-024).
     *
     * Misma suite + mismos evidence inputs + misma engine version +
     * mismo provider adapter version ⇒ mismo digest. Si el caller
     * re-ejecuta con el mismo fingerprint, el SDK puede devolver el
     * output cacheado sin re-correr el motor.
     *
     * El fingerprint es del shape de la suite y de los inputs, no del
     * contenido del report. Eso es deliberado: dos runs con el mismo
     * input deben dar el mismo report digest, y el replay lo verifica
     * comparando report digest, no refireprint.
     */
    data class Fingerprint(
        val suiteId: SuiteId,
        val suiteVersion: String,
        val evidenceDigests: List<Digest>,
        val engineVersion: String,
        val adapterVersion: String,
    )

    // -----------------------------------------------------------------------
    // Mapeo Report → Outcome
    // -----------------------------------------------------------------------

    /**
     * Convierte un `AssuranceReport` en `StepOutcome` aplicando la policy
     * de `EnforcementMode` y `CompletenessPolicy`.
     *
     * Esta función es **la única** del archivo que toma una decisión
     * sobre el veredicto. Vive en el Step (no en el engine) porque el
     * core no decide el outcome del gate (FUNCTIONAL_CORE.md): la
     * policy de "Mandatory fail ⇒ failure" es del Step handler.
     *
     * Tabla (de PIPELINEK_PLUGIN_CONTRACT.md §Step outcome matrix):
     *
     * | Body        | Assurance                | Outcome                |
     * |-------------|--------------------------|------------------------|
     * | success     | pass                     | Success                |
     * | success     | Mandatory fail           | Failure(assurance)     |
     * | success     | Inconclusive + Require   | Failure(incomplete)    |
     * | success     | Inconclusive + Report    | Success                |
     * | failure     | cualquier                | Aborted(body failure)  |
     */
    fun outcomeOf(
        report: AssuranceReport,
        mode: EnforcementMode,
        completenessPolicy: CompletenessPolicy,
    ): StepOutcome {
        val failed = report.summary.failed
        val inconclusive = report.summary.inconclusive

        return when {
            // Assurance tuvo fallos Mandatory
            failed > 0 && mode == EnforcementMode.FailClosed ->
                StepOutcome.Failure("assurance: $failed assertion(s) failed (Mandatory)")

            // Inconclusive bajo RequireComplete
            inconclusive > 0 && completenessPolicy == CompletenessPolicy.RequireComplete ->
                StepOutcome.Failure("assurance-incomplete: $inconclusive inconclusive assertion(s)")

            // Pasó
            else -> StepOutcome.Success
        }
    }
}
