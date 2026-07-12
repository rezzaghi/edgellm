package io.github.rezzaghi.edgellm.sample

import android.app.Application
import io.github.rezzaghi.edgellm.EdgeLlm

class SampleApp : Application() {
    val edgeLlm: EdgeLlm by lazy { EdgeLlm.create(this) }
}
