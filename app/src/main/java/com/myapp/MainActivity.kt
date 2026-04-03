package com.myapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View  // Added this missing import
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    // ── Config ───────────────────────────────────────────────────────────────
    private val OPENROUTER_MODEL = "qwen/qwen3.6-plus:free"
    private val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

    // ── Colors ────────────────────────────────────────────────────────────────
    private val BG     = Color.parseColor("#0F0F0F")
    private val CARD   = Color.parseColor("#1A1A1A")
    private val ACCENT = Color.parseColor("#1DB954")
    private val WHITE  = Color.WHITE
    private val MUTED  = Color.parseColor("#888888")
    private val RED    = Color.parseColor("#CF6679")
    private val BLUE   = Color.parseColor("#2D5AF5")

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var apiKeyInput: EditText
    private lateinit var aiPromptInput: EditText
    private lateinit var generateButton: Button
    private lateinit var curlInput: EditText
    private lateinit var runButton: Button
    private lateinit var clearButton: Button
    private lateinit var copyButton: Button
    private lateinit var prettyToggle: Button
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private lateinit var scrollView: ScrollView

    // ── State ─────────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var runJob: Job? = null
    private var rawOutput: String = ""
    private var isPretty: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun buildUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }

        // AI Section Header
        layout.addView(
            TextView(this).apply {
                text = "✨ AI cURL Generator"
                setTextColor(ACCENT)
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(8))
        )

        layout.addView(
            TextView(this).apply {
                text = "Powered by OpenRouter (Qwen 3.6 :free)"
                setTextColor(MUTED)
                textSize = 11f
                gravity = Gravity.CENTER
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(16))
        )

        // API Key
        layout.addView(
            TextView(this).apply {
                text = "OpenRouter API Key"
                setTextColor(MUTED)
                textSize = 11f
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(6))
        )

        apiKeyInput = EditText(this).apply {
            hint = "sk-or-v1-..."
            setTextColor(WHITE)
            setHintTextColor(Color.parseColor("#555555"))
            setBackgroundColor(CARD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or 
                       android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(apiKeyInput, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(12)))

        // Prompt
        layout.addView(
            TextView(this).apply {
                text = "Describe your request"
                setTextColor(MUTED)
                textSize = 11f
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(6))
        )

        aiPromptInput = EditText(this).apply {
            hint = "e.g., GET users from github api"
            setTextColor(WHITE)
            setHintTextColor(Color.parseColor("#555555"))
            setBackgroundColor(CARD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 12f
            minLines = 3
            gravity = Gravity.TOP
        }
        layout.addView(aiPromptInput, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(10)))

        generateButton = button("✨ Generate cURL Command", BLUE) { onGeneratePressed() }
        layout.addView(generateButton, lp(MATCH_PARENT, dp(48), bm = dp(8)))

        // Divider - Fixed with explicit View type and Import
        val divider = View(this).apply { 
            setBackgroundColor(Color.parseColor("#333333")) 
        }
        layout.addView(divider, lp(MATCH_PARENT, dp(1), bm = dp(24)))

        // Manual Section
        layout.addView(
            TextView(this).apply {
                text = "Manual cURL"
                setTextColor(WHITE)
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(12))
        )

        curlInput = EditText(this).apply {
            hint = "curl https://..."
            setTextColor(WHITE)
            setHintTextColor(MUTED)
            setBackgroundColor(CARD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            minLines = 5
            gravity = Gravity.TOP
        }
        layout.addView(curlInput, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(10)))

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runButton = button("▶  Run", ACCENT) { onRunPressed() }
        clearButton = button("Clear", CARD) { onClearPressed() }
        actionRow.addView(runButton,   LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        actionRow.addView(clearButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        layout.addView(actionRow, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(14)))

        statusText = TextView(this).apply {
            text = "Ready"
            setTextColor(MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        layout.addView(statusText, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(10)))

        val resultHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resultHeader.addView(TextView(this).apply { text = "Output"; setTextColor(MUTED); textSize = 11f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        prettyToggle = button("JSON ✦", Color.parseColor("#1A3A2A")) { onPrettyToggle() }.apply { textSize = 11f }
        copyButton = button("Copy", CARD) { onCopyPressed() }.apply { textSize = 11f; isEnabled = false }
        resultHeader.addView(prettyToggle, LinearLayout.LayoutParams(dp(80), dp(32)).apply { rightMargin = dp(6) })
        resultHeader.addView(copyButton,   LinearLayout.LayoutParams(dp(72), dp(32)))
        layout.addView(resultHeader, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(6)))

        scrollView = ScrollView(this).apply { setBackgroundColor(CARD) }
        resultText = TextView(this).apply {
            setTextColor(WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        scrollView.addView(resultText, lp(MATCH_PARENT, WRAP_CONTENT))
        layout.addView(scrollView, lp(MATCH_PARENT, dp(400)))

        root.addView(layout, lp(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    private fun onGeneratePressed() {
        val apiKey = apiKeyInput.text.toString().trim()
        val prompt = aiPromptInput.text.toString().trim()
        if (apiKey.isEmpty() || prompt.isEmpty()) {
            setStatus("API Key and Prompt required", true)
            return
        }
        runJob?.cancel()
        setStatus("Asking AI...")
        generateButton.isEnabled = false
        runJob = scope.launch {
            try {
                val result = generateCurlWithAI(apiKey, prompt)
                withContext(Dispatchers.Main) {
                    curlInput.setText(result)
                    setStatus("Generated!")
                    generateButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("Error: ${e.message}", true)
                    generateButton.isEnabled = true
                }
            }
        }
    }

    private suspend fun generateCurlWithAI(apiKey: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val url = URL(OPENROUTER_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("HTTP-Referer", "https://localhost")
            doOutput = true
        }

        val body = JSONObject().apply {
            put("model", OPENROUTER_MODEL)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "Generate only the raw curl command for: $userPrompt. No markdown, no text.")
            }))
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val response = conn.inputStream.bufferedReader().readText()
        val content = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        content.replace("```curl", "").replace("```", "").trim()
    }

    private fun onRunPressed() {
        val raw = curlInput.text.toString().trim()
        if (raw.isEmpty()) return
        runJob?.cancel()
        setStatus("Running...")
        runButton.isEnabled = false
        runJob = scope.launch {
            val result = exec(parseCurlArgs(raw))
            withContext(Dispatchers.Main) {
                rawOutput = result.stdout + result.stderr
                showOutput()
                runButton.isEnabled = true
                setStatus("Done", result.exitCode != 0)
                copyButton.isEnabled = true
            }
        }
    }

    private fun onClearPressed() {
        curlInput.setText(""); resultText.text = ""; rawOutput = ""; setStatus("Ready")
    }

    private fun onCopyPressed() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("curl", resultText.text))
        setStatus("Copied!")
    }

    private fun onPrettyToggle() {
        isPretty = !isPretty
        prettyToggle.setBackgroundColor(if (isPretty) Color.parseColor("#1A3A2A") else CARD)
        showOutput()
    }

    private fun showOutput() {
        resultText.text = if (isPretty) prettyPrintJson(rawOutput) else rawOutput
    }

    private fun prettyPrintJson(raw: String): String {
        return try {
            if (raw.trim().startsWith("{")) JSONObject(raw).toString(2)
            else if (raw.trim().startsWith("[")) JSONArray(raw).toString(2)
            else raw
        } catch (e: Exception) { raw }
    }

    private data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

    private fun exec(args: List<String>): ExecResult {
        return try {
            val p = ProcessBuilder(args).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            ExecResult(out, err, p.waitFor())
        } catch (e: Exception) { ExecResult("", e.message ?: "Error", -1) }
    }

    private fun parseCurlArgs(input: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (char in input.replace("\\\n", " ")) {
            if (char == '\"') inQuotes = !inQuotes
            else if (char == ' ' && !inQuotes) {
                if (current.isNotEmpty()) { args.add(current.toString()); current.setLength(0) }
            } else current.append(char)
        }
        if (current.isNotEmpty()) args.add(current.toString())
        return args
    }

    private fun setStatus(msg: String, error: Boolean = false) {
        runOnUiThread { statusText.text = msg; statusText.setTextColor(if (error) RED else MUTED) }
    }

    private fun button(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextColor(WHITE); setBackgroundColor(bg); isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int, h: Int, bm: Int = 0) = LinearLayout.LayoutParams(w, h).apply { bottomMargin = bm }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
