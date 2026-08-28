package dev.propor.core.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * La regla de dependencia, verificada.
 *
 * Una regla de arquitectura que no se comprueba no existe: sobrevive exactamente hasta el
 * primer viernes con prisa. Este test hace que el desorden **no compile**, que es la unica
 * forma de que un proyecto siga ordenado a los seis meses.
 *
 * Corre sobre el codigo fuente en vez de sobre el bytecode a proposito: asi el mensaje de error
 * nombra el archivo y la linea, y quien lo rompe sabe exactamente que arreglar.
 */
class ArchitectureTest {

    private val commonMain = resolveSourceRoot("src/commonMain/kotlin")

    /**
     * Todo lo que el nucleo no puede tocar. Si aparece un import de estos, la logica de
     * composicion ha empezado a atarse a una plataforma y el hexagono se rompio.
     */
    private val forbiddenPrefixes = listOf(
        "androidx.",
        "android.",
        "platform.",          // interop de Kotlin/Native con Apple
        "java.",              // JDK: el nucleo tiene que compilar tambien para iOS
        "javax.",
        "kotlinx.coroutines.android",
        "androidx.compose",
        "SwiftUI",
        "UIKit",
        "AVFoundation",
        "org.jetbrains.skia",
    )

    @Test
    fun elNucleoNoImportaNingunFramework() {
        val violations = mutableListOf<String>()

        commonMain.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ")) return@forEachIndexed
                val imported = trimmed.removePrefix("import ").substringBefore(" as ").trim()
                forbiddenPrefixes.firstOrNull { imported.startsWith(it) }?.let { prefix ->
                    violations += "${file.relativeTo(commonMain)}:${index + 1} importa '$imported' " +
                        "(prohibido: '$prefix')"
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("El nucleo importa frameworks. Rompe la regla de dependencia (ADR-002).")
                    appendLine("Lo que va aqui es Kotlin puro; lo que necesite plataforma va tras un puerto.")
                    violations.forEach { appendLine("  - $it") }
                },
            )
        }
    }

    /**
     * El dominio no puede conocer la capa de aplicacion.
     *
     * Los puertos de SALIDA viven en `domain/port` porque quien los necesita es el dominio.
     * En `application` viven los puertos de ENTRADA, que son los casos de uso: el dominio no
     * tiene por que saber que existen.
     */
    @Test
    fun elDominioNoConoceLaCapaDeAplicacion() {
        val violations = mutableListOf<String>()
        val domain = File(commonMain, "dev/propor/core/domain")

        if (domain.exists()) {
            domain.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.trim().startsWith("import dev.propor.core.application")) {
                        violations += "${file.relativeTo(commonMain)}:${index + 1} -> ${line.trim()}"
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "El dominio importa de application:\n" + violations.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * ADR-004: `Advice` no lleva texto de interfaz.
     *
     * El dominio decide QUE esta mal; la presentacion decide COMO se dice y, sobre todo, SI se
     * dice o solo se vibra. Un String aqui romperia a la vez la i18n, la accesibilidad y el
     * principio de que nunca se lee mientras se compone.
     */
    @Test
    fun losConsejosNoLlevanTextoDeInterfaz() {
        val adviceFile = File(commonMain, "dev/propor/core/domain/advice/Advice.kt")
        assertTrue(adviceFile.exists(), "no se encontro Advice.kt en ${adviceFile.path}")

        val offenders = adviceFile.readLines()
            .withIndex()
            .filter { (_, line) ->
                val code = line.substringBefore("//").trim()
                // Propiedades o parametros de tipo String dentro de las clases de consejo.
                Regex("""\bval\s+\w+\s*:\s*String\b""").containsMatchIn(code)
            }
            .map { (i, line) -> "Advice.kt:${i + 1} -> ${line.trim()}" }

        assertTrue(
            offenders.isEmpty(),
            "Los consejos del dominio no pueden llevar texto (ADR-004):\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * Prohibido dejar deuda anonima. Un TODO sin tarjeta es trabajo que nadie va a priorizar.
     */
    @Test
    fun ningunTodoSinTarjetaDelTablero() {
        val pattern = Regex("""(TODO|FIXME)\b""")
        val withCard = Regex("""(TODO|FIXME)[^\n]*PCA-\d+""")
        val offenders = mutableListOf<String>()

        commonMain.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (pattern.containsMatchIn(line) && !withCard.containsMatchIn(line)) {
                    offenders += "${file.relativeTo(commonMain)}:${index + 1} -> ${line.trim()}"
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "Deuda sin id de tarjeta (usa 'TODO(PCA-42): ...'):\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    // ------------------------------------------------------------------ util

    /**
     * La ruta llega desde el build, que ademas declara esos archivos como entrada de la tarea
     * para que Gradle vuelva a ejecutar el guardian cuando el codigo cambia. El rastreo desde
     * el directorio de trabajo queda solo como red por si alguien lanza el test a mano.
     */
    private fun resolveSourceRoot(relative: String): File {
        System.getProperty("propor.sourceRoot")?.let { configured ->
            val dir = File(configured)
            if (dir.isDirectory) return dir
        }
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            val direct = File(dir, relative)
            if (direct.isDirectory) return direct
            val underCore = File(dir, "core/$relative")
            if (underCore.isDirectory) return underCore
            dir = dir.parentFile ?: return@repeat
        }
        error("No se encontro el codigo fuente del nucleo desde ${System.getProperty("user.dir")}")
    }
}
