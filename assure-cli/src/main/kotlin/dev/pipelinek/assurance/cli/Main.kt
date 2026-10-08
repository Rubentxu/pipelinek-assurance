package dev.pipelinek.assurance.cli

/**
 * Punto de entrada del proceso.
 *
 * Existe sólo para que `main` tenga un nombre y el CLI sea ejecutable con
 * `./gradlew :assure-cli:run --args=...`. Toda la lógica está en [AssureCli],
 * que es pura: este fichero es el único sitio que toca stdout y `exitProcess`.
 */
fun main(args: Array<String>) = AssureCli.main(args)
