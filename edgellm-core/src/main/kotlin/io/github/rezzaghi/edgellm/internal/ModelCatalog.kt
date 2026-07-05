package io.github.rezzaghi.edgellm.internal

import android.content.Context
import io.github.rezzaghi.edgellm.ModelFormat
import io.github.rezzaghi.edgellm.ModelSpec
import io.github.rezzaghi.edgellm.R
import org.json.JSONObject

internal object ModelCatalog {

    @Volatile
    private var cached: List<ModelSpec>? = null

    fun load(context: Context): List<ModelSpec> {
        cached?.let { return it }

        val json = context.resources.openRawResource(R.raw.edgellm_catalog)
            .bufferedReader()
            .use { it.readText() }

        val models = JSONObject(json).getJSONArray("models")
        val specs = buildList {
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                add(
                    ModelSpec(
                        id = m.getString("id"),
                        url = m.getString("url"),
                        sha256 = m.getString("sha256"),
                        sizeBytes = m.getLong("sizeBytes"),
                        format = ModelFormat.valueOf(m.getString("format")),
                    )
                )
            }
        }
        cached = specs
        return specs
    }
}
