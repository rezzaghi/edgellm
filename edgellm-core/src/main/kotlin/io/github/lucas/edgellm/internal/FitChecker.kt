package io.github.lucas.edgellm.internal

import io.github.lucas.edgellm.Fit
import io.github.lucas.edgellm.ModelSpec
import io.github.lucas.edgellm.engine.DeviceProfile

internal object FitChecker {

    /**
     * Heuristic v1: runtime footprint ≈ 1.3× file size (weights stay mapped,
     * plus KV cache and compute buffers), and Ok requires 1.5× that so the
     * app and OS keep breathing room. Refine with real measurements later.
     */
    fun check(profile: DeviceProfile, model: ModelSpec): Fit {
        val requiredMb = model.sizeBytes * 13 / 10 / (1024 * 1024)
        val availableMb = profile.availableRamMb
        return when {
            availableMb >= requiredMb * 3 / 2 -> Fit.Ok
            availableMb >= requiredMb -> Fit.TightRam(availableMb, requiredMb)
            else -> Fit.WontFit(availableMb, requiredMb)
        }
    }
}
