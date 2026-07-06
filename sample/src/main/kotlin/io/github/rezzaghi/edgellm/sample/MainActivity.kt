package io.github.rezzaghi.edgellm.sample

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import io.github.rezzaghi.edgellm.ChatMessage
import io.github.rezzaghi.edgellm.EdgeLlm
import io.github.rezzaghi.edgellm.EdgeLlmSession
import io.github.rezzaghi.edgellm.Fit
import io.github.rezzaghi.edgellm.GenerationEvent
import io.github.rezzaghi.edgellm.ModelSpec
import io.github.rezzaghi.edgellm.engine.EngineConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var edgeLlm: EdgeLlm
    private lateinit var model: ModelSpec
    private var session: EdgeLlmSession? = null
    private var generation: Job? = null

    private lateinit var deviceInfo: TextView
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var loadBtn: Button
    private lateinit var genBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeLlm = EdgeLlm.create(this)
        val catalog = edgeLlm.catalog()
        model = catalog.first()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Android 15 lays content out edge-to-edge; pad past the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(32, bars.top + 32, 32, bars.bottom + 32)
                insets
            }
        }

        deviceInfo = TextView(this)
        status = TextView(this)
        val modelPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                catalog.map { it.id },
            )
        }
        val promptInput = EditText(this).apply {
            hint = "Prompt"
            setText("Why is the sky blue? Answer briefly.")
        }
        val downloadBtn = Button(this).apply { text = "Download model" }
        val gpuToggle = CheckBox(this).apply { text = "Use GPU (OpenCL)" }
        loadBtn = Button(this).apply { text = "Load model" }
        genBtn = Button(this).apply { text = "Generate"; isEnabled = false }
        val stopBtn = Button(this).apply { text = "Stop"; isEnabled = false }
        output = TextView(this).apply { textSize = 14f }
        val scroll = ScrollView(this).apply { addView(output) }

        root.addView(deviceInfo)
        root.addView(status)
        root.addView(modelPicker)
        root.addView(promptInput)
        root.addView(downloadBtn)
        root.addView(gpuToggle)
        root.addView(loadBtn)
        root.addView(genBtn)
        root.addView(stopBtn)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        modelPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectModel(catalog[pos])
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        selectModel(model)

        downloadBtn.setOnClickListener {
            val m = model
            downloadBtn.isEnabled = false
            scope.launch {
                runCatching {
                    edgeLlm.download(m).collect { p ->
                        status.text = "Downloading: %.1f%% (%d / %d MB)".format(
                            p.fraction * 100, p.bytesDownloaded / MB, p.totalBytes / MB,
                        )
                    }
                }
                    .onSuccess { status.text = "Download complete (verified)" }
                    .onFailure { status.text = "Download failed: ${it.message}" }
                downloadBtn.isEnabled = true
            }
        }

        loadBtn.setOnClickListener {
            val m = model
            if (!edgeLlm.isDownloaded(m)) {
                status.text = "Model not downloaded yet — tap Download model"
                return@setOnClickListener
            }
            status.text = "Loading…"
            loadBtn.isEnabled = false
            val config = EngineConfig(gpuLayers = if (gpuToggle.isChecked) 99 else 0)
            scope.launch {
                runCatching { edgeLlm.load(m, config) }
                    .onSuccess {
                        session = it
                        status.text = "Model loaded: ${m.id}"
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
            val messages = listOf(
                ChatMessage.system("You are a helpful assistant."),
                ChatMessage.user(promptInput.text.toString()),
            )

            output.text = ""
            genBtn.isEnabled = false
            stopBtn.isEnabled = true

            generation = scope.launch {
                runCatching {
                    s.chat(messages, maxTokens = 256).collect { event ->
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

    /** Switching models invalidates the loaded session and refreshes the UI. */
    private fun selectModel(m: ModelSpec) {
        model = m
        generation?.cancel()
        val old = session
        session = null
        if (old != null) scope.launch { old.close() }

        genBtn.isEnabled = false
        loadBtn.isEnabled = true
        output.text = ""

        val profile = edgeLlm.deviceProfile()
        val fit = edgeLlm.checkFit(m)
        deviceInfo.text =
            "SoC: ${profile.socModel}, ${profile.availableRamMb}MB free — fit: ${fit.label()}"
        status.text = if (edgeLlm.isDownloaded(m)) {
            "Downloaded, not loaded"
        } else {
            "Not downloaded (${m.sizeBytes / MB}MB)"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.launch { session?.close() }
        scope.cancel()
    }
}

private const val MB = 1024L * 1024L

private fun Fit.label(): String = when (this) {
    is Fit.Ok -> "OK"
    is Fit.TightRam -> "tight (${availableMb}MB free / ${requiredMb}MB needed)"
    is Fit.WontFit -> "won't fit (${availableMb}MB free / ${requiredMb}MB needed)"
}
