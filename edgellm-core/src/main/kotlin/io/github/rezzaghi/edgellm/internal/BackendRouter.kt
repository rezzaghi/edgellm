package io.github.rezzaghi.edgellm.internal

import io.github.rezzaghi.edgellm.engine.Backend
import io.github.rezzaghi.edgellm.engine.DeviceProfile

/**
 * Resolves Backend.AUTO per device. MVP table: Qualcomm SoCs get OpenCL
 * (verified on Adreno 740+); everything else stays on CPU until tested.
 */
internal object BackendRouter {

    fun route(profile: DeviceProfile): Backend {
        val maker = profile.socManufacturer.lowercase()
        val model = profile.socModel.lowercase()
        return when {
            "qti" in maker || "qualcomm" in maker || "qcom" in maker -> Backend.OPENCL
            model.startsWith("sm") || "qcom" in model -> Backend.OPENCL
            else -> Backend.CPU
        }
    }
}
