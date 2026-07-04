package io.github.lucas.edgellm.sample

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.lucas.edgellm.engine.llamacpp.LlamaBridge
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private var handle = 0L
    @Volatile private var generating = false

    private lateinit var status: TextView
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        status = TextView(this).apply { text = "Model not loaded" }
        val promptInput = EditText(this).apply {
            hint = "Prompt"
            setText("Why is the sky blue? Answer briefly.")
        }
        val loadBtn = Button(this).apply { text = "Load model" }
        val genBtn = Button(this).apply { text = "Generate"; isEnabled = false }
        val stopBtn = Button(this).apply { text = "Stop"; isEnabled = false }
        output = TextView(this).apply { textSize = 14f }
        val scroll = ScrollView(this).apply { addView(output) }

        root.addView(status)
        root.addView(promptInput)
        root.addView(loadBtn)
        root.addView(genBtn)
        root.addView(stopBtn)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        val modelFile = File(getExternalFilesDir(null), "model.gguf")

        loadBtn.setOnClickListener {
            if (!modelFile.exists()) {
                status.text = "Missing model: ${modelFile.absolutePath}"
                return@setOnClickListener
            }
            status.text = "Loading…"
            loadBtn.isEnabled = false
            thread {
                val h = LlamaBridge.nativeLoadModel(modelFile.absolutePath, 2048)
                runOnUiThread {
                    if (h == 0L) {
                        status.text = "Load failed (see logcat, tag: edgellm)"
                        loadBtn.isEnabled = true
                    } else {
                        handle = h
                        status.text = "Model loaded"
                        genBtn.isEnabled = true
                    }
                }
            }
        }

        genBtn.setOnClickListener {
            // Qwen2.5 chat template, hardcoded for the spike.
            // The real SDK will read the template from GGUF metadata.
            val prompt = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
                "<|im_start|>user\n${promptInput.text}<|im_end|>\n<|im_start|>assistant\n"

            output.text = ""
            genBtn.isEnabled = false
            stopBtn.isEnabled = true
            generating = true

            thread {
                val bytes = ByteArrayOutputStream()
                val startMs = System.currentTimeMillis()
                val n = LlamaBridge.nativeGenerate(handle, prompt, 256) { piece ->
                    bytes.write(piece)
                    val text = bytes.toString("UTF-8")
                    runOnUiThread { output.text = text }
                }
                val secs = (System.currentTimeMillis() - startMs) / 1000.0
                runOnUiThread {
                    status.text = if (n >= 0) {
                        "Done: %d tokens in %.1fs (%.1f tok/s)".format(n, secs, n / secs)
                    } else {
                        "Generation failed: error $n"
                    }
                    genBtn.isEnabled = true
                    stopBtn.isEnabled = false
                    generating = false
                }
            }
        }

        stopBtn.setOnClickListener {
            if (generating) LlamaBridge.nativeStop(handle)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (handle != 0L) LlamaBridge.nativeFree(handle)
    }
}
