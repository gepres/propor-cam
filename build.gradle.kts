/**
 * Los plugins se declaran aqui con `apply false` para que queden en el classpath compartido de
 * la build. Sin esto, un modulo KMP que aplique tambien el plugin de Android falla al arrancar
 * con "Can't infer current AndroidGradlePluginVersion": cada subproyecto resolveria el plugin
 * por su cuenta y no se verian entre si.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
