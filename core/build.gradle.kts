plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

// Los targets de Apple solo se compilan en macOS. En Windows y Linux el nucleo se construye y
// se prueba entero sobre la JVM: es Kotlin puro, sin frameworks.
val hostIsMac = System.getProperty("os.name").startsWith("Mac")

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    if (hostIsMac) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            // api y no implementation: los puertos exponen Flow y StateFlow en su firma
            // publica, asi que quien implemente un adaptador necesita el tipo en su classpath.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "dev.propor.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * El test de arquitectura lee el codigo FUENTE, no el bytecode. Sin declarar esos archivos como
 * entrada de la tarea, Gradle la da por actualizada cuando solo cambian comentarios o imports que
 * no alteran el resultado compilado, y el guardian deja de ejecutarse sin que nadie se entere.
 *
 * Un test de arquitectura que no corre es peor que no tenerlo: da una falsa sensacion de orden.
 */
tasks.named<Test>("jvmTest") {
    val coreSources = layout.projectDirectory.dir("src/commonMain/kotlin")
    inputs.dir(coreSources)
        .withPropertyName("coreSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("propor.sourceRoot", coreSources.asFile.absolutePath)
}
