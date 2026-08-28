package dev.propor.core.domain.port

/**
 * Reloj monotono en milisegundos.
 *
 * No es purismo academico: sin un reloj inyectable, el `AdviceThrottler` y las rachas de
 * aprendizaje serian intestables y sus tests necesitarian `sleep()`, que es como se consiguen
 * suites lentas e intermitentes. Con este puerto, una sesion de coach de tres minutos se
 * reproduce en milisegundos y siempre da el mismo resultado.
 *
 * Monotono, no de calendario: no puede saltar hacia atras si el usuario cambia la hora.
 *
 * Vive en `domain/port` y no en `application`: los puertos de SALIDA pertenecen a quien los
 * necesita, y quien necesita el reloj es un servicio de dominio (`AdviceThrottler`). En
 * `application` viven los puertos de ENTRADA, es decir los casos de uso. Asi el dominio nunca
 * tiene que importar nada de la capa de aplicacion, y eso se puede verificar.
 */
fun interface ClockPort {
    fun nowMillis(): Long
}
