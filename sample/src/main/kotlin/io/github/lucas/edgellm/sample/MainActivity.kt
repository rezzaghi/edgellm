package io.github.lucas.edgellm.sample

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.lucas.edgellm.EdgeLlm
import io.github.lucas.edgellm.EdgeLlmSession
import io.github.lucas.edgellm.Fit
import io.github.lucas.edgellm.GenerationEvent
import io.github.lucas.edgellm.ModelSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private val QWEN_05B = ModelSpec(
    id = "qwen2.5-0.5b-instruct-q4km",
    url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
    sha256 = null, // dev only; real specs must pin a checksum
    sizeBytes = 491_400_032,
)

class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var edgeLlm: EdgeLlm
    private var session: EdgeLlmSession? = null
    private var generation: Job? = null

    private lateinit var status: TextView
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeLlm = EdgeLlm.create(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Android 15 lays content out edge-to-edge; pad past the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(32, bars.top + 32, 32, bars.bottom + 32)
                insets
            }
        }

        val deviceInfo = TextView(this)
        status = TextView(this)
        val promptInput = EditText(this).apply {
            hint = "Prompt"
            setText("Why is the sky blue? Answer briefly.")
        }
        val loadBtn = Button(this).apply { text = "Load model" }
        val genBtn = Button(this).apply { text = "Generate"; isEnabled = false }
        val stopBtn = Button(this).apply { text = "Stop"; isEnabled = false }
        output = TextView(this).apply { textSize = 14f }
        val scroll = ScrollView(this).apply { addView(output) }

        root.addView(deviceInfo)
        root.addView(status)
        root.addView(promptInput)
        root.addView(loadBtn)
        root.addView(genBtn)
        root.addView(stopBtn)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        val profile = edgeLlm.deviceProfile()
        val fit = edgeLlm.checkFit(QWEN_05B)
        deviceInfo.text =
            "SoC: ${profile.socModel}, ${profile.availableRamMb}MB free — fit: ${fit.label()}"
        status.text = "Model not loaded"

        loadBtn.setOnClickListener {
            if (!edgeLlm.isDownloaded(QWEN_05B)) {
                status.text = "Model missing at ${edgeLlm.modelFile(QWEN_05B)}"
                return@setOnClickListener
            }
            status.text = "Loading…"
            loadBtn.isEnabled = false
            scope.launch {
                runCatching { edgeLlm.load(QWEN_05B) }
                    .onSuccess {
                        session = it
                        status.text = "Model loaded"
                        genBtn.isEnabled = true
                    }
                    .onFailure {
                        status.text = "Load failed: ${it.message}"
                        loadBtn.isEnabled = true
                    }
            }
        }

        genBtn.setOnClickListener {
            val s = session ?: return@setOnClickListener
            // Qwen2.5 chat template, hardcoded until core reads GGUF metadata.
            val prompt = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
                "<|im_start|>user\n${promptInput.text}<|im_end|>\n<|im_start|>assistant\n"

            output.text = ""
            genBtn.isEnabled = false
            stopBtn.isEnabled = true

            generation = scope.launch {
                runCatching {
                    s.generate(prompt, maxTokens = 256).collect { event ->
                        when (event) {
                            is GenerationEvent.Token ->
                                output.append(event.text)
                            is GenerationEvent.Done ->
                                status.text = "Done: %d tokens (%.1f tok/s)"
                                    .format(event.totalTokens, event.tokensPerSec)
                        }
                    }
                }.onFailure { status.text = "Generation failed: ${it.message}" }
                genBtn.isEnabled = true
                stopBtn.isEnabled = false
            }
        }

        stopBtn.setOnClickListener {
            generation?.cancel()
            status.text = "Stopped"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.launch { session?.close() }
        scope.cancel()
    }
}

private fun Fit.label(): String = when (this) {
    is Fit.Ok -> "OK"
    is Fit.TightRam -> "tight (${availableMb}MB free / ${requiredMb}MB needed)"
    is Fit.WontFit -> "won't fit (${availableMb}MB free / ${requiredMb}MB needed)"
}
