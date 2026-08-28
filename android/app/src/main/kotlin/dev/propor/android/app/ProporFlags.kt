package dev.propor.android.app

/**
 * Interruptores del andamiaje de la fase de prueba.
 *
 * Todo lo que hay aqui **esta destinado a desaparecer**. Tenerlo junto y con nombre propio es lo
 * que evita que se quede: el andamiaje disperso por el codigo nunca se retira, porque nadie sabe
 * donde esta ni si alguien depende de el.
 */
object ProporFlags {

    /**
     * Muestra la guia de prueba al primer arranque.
     *
     * **Contradice a proposito el diseno del producto**, que dice cero pantallas de bienvenida:
     * se entra al visor y la primera foto llega en menos de un minuto. Existe solo mientras la
     * app se entrega a personas que no conocen el proyecto y necesitan saber que estan probando.
     *
     * Al cerrar la prueba de campo: poner a `false`, borrar `TestGuideScreen.kt` y quitar el
     * boton de ayuda del visor.
     */
    const val SHOW_TEST_GUIDE = true
}
