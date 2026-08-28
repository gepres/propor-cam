package dev.propor.core.domain.advice

/**
 * Como se adapta el coach a una persona concreta.
 *
 * Existe desde R1 aunque el perfil real (`ProfileLearner`, epica E9) llegue en R2: si el motor
 * no naciera con el perfil inyectable, adaptarlo despues obligaria a reescribirlo. Con
 * [NEUTRAL] el comportamiento es el de un usuario nuevo, que es exactamente lo que hace falta
 * en R1.
 *
 * @param mutedKeys reglas que esta persona ya no quiere oir. Se llenan solas cuando descarta
 *   el mismo consejo tres veces: si insiste en romper una convencion, es su estilo, no un error.
 * @param weights multiplicador por regla, de 0 a 2. Sube las debilidades reales de esta persona
 *   y baja lo que ya domina. Es lo que impide que el coach se vuelva ruido cuando el usuario
 *   mejora, que es la razon por la que se abandonan las apps de la competencia.
 */
data class CoachProfile(
    val mutedKeys: Set<AdviceKey> = emptySet(),
    val weights: Map<AdviceKey, Float> = emptyMap(),
) {
    fun weightFor(key: AdviceKey): Float = weights[key]?.coerceIn(0f, 2f) ?: 1f

    fun isMuted(key: AdviceKey): Boolean = key in mutedKeys

    fun muting(key: AdviceKey): CoachProfile = copy(mutedKeys = mutedKeys + key)

    companion object {
        /** Usuario nuevo: todas las reglas activas y con el mismo peso. */
        val NEUTRAL = CoachProfile()
    }
}
