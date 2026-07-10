package io.github.rezzaghi.edgellm.internal

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.rezzaghi.edgellm.engine.DeviceProfile

internal object DeviceProfiler {

    fun profile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)

        val soc: String
        val socMaker: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            soc = Build.SOC_MODEL
            socMaker = Build.SOC_MANUFACTURER
        } else {
            soc = Build.HARDWARE
            socMaker = Build.HARDWARE
        }

        return DeviceProfile(
            totalRamMb = mem.totalMem / MB,
            availableRamMb = mem.availMem / MB,
            socModel = soc,
            socManufacturer = socMaker,
            abis = Build.SUPPORTED_ABIS.toList(),
        )
    }

    private const val MB = 1024L * 1024L
}
