package io.github.rezzaghi.edgellm.internal

import io.github.rezzaghi.edgellm.engine.Backend
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendRouterTest {

    @Test
    fun qualcommManufacturerGetsOpenCl() {
        assertEquals(Backend.OPENCL, route(maker = "QTI", model = "SM8550"))
        assertEquals(Backend.OPENCL, route(maker = "Qualcomm", model = "whatever"))
    }

    @Test
    fun snapdragonModelWithUnknownMakerGetsOpenCl() {
        assertEquals(Backend.OPENCL, route(maker = "", model = "SM8650"))
        assertEquals(Backend.OPENCL, route(maker = "unknown", model = "qcom"))
    }

    @Test
    fun nonQualcommStaysOnCpu() {
        assertEquals(Backend.CPU, route(maker = "Samsung", model = "s5e9945"))
        assertEquals(Backend.CPU, route(maker = "Mediatek", model = "MT6989"))
        assertEquals(Backend.CPU, route(maker = "Google", model = "Tensor G4"))
        assertEquals(Backend.CPU, route(maker = "", model = ""))
    }

    private fun route(maker: String, model: String): Backend =
        BackendRouter.route(
            io.github.rezzaghi.edgellm.engine.DeviceProfile(
                totalRamMb = 8192,
                availableRamMb = 4096,
                socModel = model,
                socManufacturer = maker,
                abis = listOf("arm64-v8a"),
            ),
        )
}
