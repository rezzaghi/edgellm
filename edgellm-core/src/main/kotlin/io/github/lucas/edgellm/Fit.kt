package io.github.lucas.edgellm

/** Result of asking whether a model can run on this device right now. */
sealed interface Fit {
    /** Comfortable headroom; load away. */
    data object Ok : Fit

    /** Will load, but the system may kill background apps or page heavily. */
    data class TightRam(val availableMb: Long, val requiredMb: Long) : Fit

    /** Loading would likely be OOM-killed. Don't. */
    data class WontFit(val availableMb: Long, val requiredMb: Long) : Fit
}
