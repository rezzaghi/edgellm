package io.github.rezzaghi.edgellm.internal

import io.github.rezzaghi.edgellm.Fit
import io.github.rezzaghi.edgellm.ModelSpec
import io.github.rezzaghi.edgellm.engine.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitCheckerTest {

    // 100MB model -> requiredMb = 130 (1.3x), Ok needs >= 195 (1.5x required)
    private val model = spec(sizeBytes = 100L * 1024 * 1024)

    @Test
    fun okWithComfortableHeadroom() {
        assertEquals(Fit.Ok, check(availableMb = 195))
    }

    @Test
    fun tightJustBelowHeadroom() {
        val fit = check(availableMb = 194)
        assertTrue(fit is Fit.TightRam)
        assertEquals(130, (fit as Fit.TightRam).requiredMb)
    }

    @Test
    fun tightAtExactRequirement() {
        assertTrue(check(availableMb = 130) is Fit.TightRam)
    }

    @Test
    fun wontFitBelowRequirement() {
        val fit = check(availableMb = 129)
        assertTrue(fit is Fit.WontFit)
        assertEquals(129, (fit as Fit.WontFit).availableMb)
    }

    @Test
    fun scalesWithModelSize() {
        val big = spec(sizeBytes = 1024L * 1024 * 1024) // 1GB -> needs 1331MB
        assertTrue(FitChecker.check(profile(1331), big) is Fit.TightRam)
        assertTrue(FitChecker.check(profile(1330), big) is Fit.WontFit)
    }

    private fun check(availableMb: Long): Fit = FitChecker.check(profile(availableMb), model)

    private fun profile(availableMb: Long) = DeviceProfile(
        totalRamMb = 8192,
        availableRamMb = availableMb,
        socModel = "TEST",
        socManufacturer = "TEST",
        abis = listOf("arm64-v8a"),
    )

    private fun spec(sizeBytes: Long) = ModelSpec(
        id = "test-model",
        url = "https://example.com/test.gguf",
        sha256 = null,
        sizeBytes = sizeBytes,
    )
}
