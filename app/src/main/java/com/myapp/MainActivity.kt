package com.myapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
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
    // UPDATED: Using qwen 3.6 free tier model
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
    // AI Section
    private lateinit var apiKeyInput: EditText
    private lateinit var aiPromptInput: EditText
    private lateinit var generateButton: Button

    // Manual cURL Section
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

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }

        // ═══════════════════════════════════════════════════════════════════
        // AI GENERATOR SECTION
        // ═══════════════════════════════════════════════════════════════════
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

        // API Key Input
        layout.addView(
            TextView(this).apply {
                text = "OpenRouter API Key (sk-or-v1-...)"
                setTextColor(MUTED)
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(6))
        )

        apiKeyInput = EditText(this).apply {
            hint = "Paste your API key from openrouter.ai"
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

        // Prompt Input
        layout.addView(
            TextView(this).apply {
                text = "Describe your request"
                setTextColor(MUTED)
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(6))
        )

        aiPromptInput = EditText(this).apply {
            hint = "e.g., 'GET https://api.github.com/users/octocat with Authorization header', 'Post JSON to httpbin.org/test with content-type application/json'"
            setTextColor(WHITE)
            setHintTextColor(Color.parseColor("#555555"))
            setBackgroundColor(CARD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 12f
            typeface = Typeface.DEFAULT
            minLines = 3
            maxLines = 6
            isSingleLine = false
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(aiPromptInput, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(10)))

        // Generate Button
        generateButton = button("✨ Generate cURL Command", BLUE) { onGeneratePressed() }
        layout.addView(generateButton, lp(MATCH_PARENT, dp(48), bm = dp(8)))

        // Divider
        layout.addView(
            View(this).apply { setBackgroundColor(Color.parseColor("#333333")) },
            lp(MATCH_PARENT, dp(1), bm = dp(24))
        )

        // ═══════════════════════════════════════════════════════════════════
        // MANUAL cURL SECTION (Existing)
        // ═══════════════════════════════════════════════════════════════════
        layout.addView(
            TextView(this).apply {
                text = "Manual cURL"
                setTextColor(WHITE)
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(12))
        )

        // Input label
        layout.addView(
            TextView(this).apply {
                text = "Command"
                setTextColor(MUTED)
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(6))
        )

        // curl input
        curlInput = EditText(this).apply {
            hint = "curl https://example.com"
            setTextColor(WHITE)
            setHintTextColor(MUTED)
            setBackgroundColor(CARD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            minLines = 5
            maxLines = 10
            isSingleLine = false
            setHorizontallyScrolling(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layout.addView(curlInput, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(10)))

        // Run / Clear row
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        runButton = button("▶  Run", ACCENT) { onRunPressed() }
        clearButton = button("Clear", CARD) { onClearPressed() }

        actionRow.addView(runButton,   LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        actionRow.addView(clearButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        layout.addView(actionRow, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(14)))

        // Status line
        statusText = TextView(this).apply {
            text = "Ready"
            setTextColor(MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        layout.addView(statusText, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(10)))

        // Result label + copy + pretty toggle
        val resultHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resultHeader.addView(
            TextView(this).apply {
                text = "Output"
                setTextColor(MUTED)
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        prettyToggle = button("JSON ✦", Color.parseColor("#1A3A2A")) { onPrettyToggle() }.apply {
            textSize = 11f
        }
        copyButton = button("Copy", CARD) { onCopyPressed() }.apply {
            textSize = 11f
            isEnabled = false
        }
        resultHeader.addView(prettyToggle, LinearLayout.LayoutParams(dp(80), dp(32)).apply { rightMargin = dp(6) })
        resultHeader.addView(copyButton,   LinearLayout.LayoutParams(dp(72), dp(32)))
        layout.addView(resultHeader, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(6)))

        // Result output (scrollable independently)
        scrollView = ScrollView(this).apply { setBackgroundColor(CARD) }
        resultText = TextView(this).apply {
            text = ""
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

    // ─────────────────────────────────────────────────────────────────────────
    //  AI Actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun onGeneratePressed() {
        val apiKey = apiKeyInput.text.toString().trim()
        val prompt = aiPromptInput.text.toString().trim()

        if (apiKey.isEmpty()) {
            setStatus("Enter OpenRouter API key first", error = true)
            return
        }
        if (!apiKey.startsWith("sk-or-v1-")) {
            setStatus("API key should start with sk-or-v1-", error = true)
            return
        }
        if (prompt.isEmpty()) {
            setStatus("Enter a description first", error = true)
            return
        }

        runJob?.cancel()
        setStatus("Asking Qwen 3.6...")
        generateButton.isEnabled = false
        generateButton.text = "Generating..."

        runJob = scope.launch {
            try {
                val generatedCurl = generateCurlWithAI(apiKey, prompt)
                withContext(Dispatchers.Main) {
                    curlInput.setText(generatedCurl)
                    setStatus("✓ cURL generated! Press Run to execute")
                    generateButton.isEnabled = true
                    generateButton.text = "✨ Generate cURL Command"
                    // Auto scroll to manual section
                    curlInput.requestFocus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("AI Error: ${e.message}", error = true)
                    generateButton.isEnabled = true
                    generateButton.text = "✨ Generate cURL Command"
                }
            }
        }
    }

    private suspend fun generateCurlWithAI(apiKey: String, userPrompt: String): String {
        return withContext(Dispatchers.IO) {
            val url = URL(OPENROUTER_URL)
            val connection = url.openConnection() as HttpURLConnection

            try {
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("HTTP-Referer", "https://localhost") // Required by OpenRouter
                    setRequestProperty("X-Title", "cURL Runner AI")
                    doOutput = true
                    connectTimeout = 30000
                    readTimeout = 60000 // AI might take time
                }

                val jsonBody = JSONObject().apply {
                    put("model", OPENROUTER_MODEL)
                    put("temperature", 0.1) // Low temp for consistent formatting
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "You are a precise cURL command generator. Given a user request, output ONLY the raw cURL command, nothing else. No markdown code blocks, no explanations, no backticks. Start with 'curl'. Ensure proper header escaping with -H flags. Use -X for methods other than GET.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userPrompt)
                        })
                    })
                }

                connection.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    val errorText = connection.errorStream?.bufferedReader()?.readText() 
                        ?: "HTTP $responseCode"
                    throw Exception("OpenRouter error: $errorText")
                }

                val response = connection.inputStream.bufferedReader().readText()
                val jsonResponse = JSONObject(response)

                if (jsonResponse.has("error")) {
                    throw Exception(jsonResponse.getJSONObject("error").getString("message"))
                }

                val content = jsonResponse
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                // Aggressive cleanup of markdown and quotes
                content
                    .replace("""```[a-z]*""".toRegex(), "")
                    .replace("```", "")
                    .replace("`", "")
                    .lines()
                    .joinToString(" ") { it.trim() }
                    .trim()
                    .removePrefix("curl")
                    .let { if (it.isNotEmpty()) "curl $it" else "curl $content" }

            } finally {
                connection.disconnect()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Existing Actions (Unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private fun onRunPressed() {
        val raw = curlInput.text.toString().trim()
        if (raw.isEmpty()) { setStatus("Paste a curl command first", error = true); return }

        val args = parseCurlArgs(raw)
        if (args.isEmpty() || args[0].lowercase() != "curl") {
            setStatus("Command must start with 'curl'", error = true)
            return
        }

        runJob?.cancel()
        setStatus("Running…")
        resultText.text = ""
        rawOutput = ""
        copyButton.isEnabled = false
        runButton.isEnabled = false

        runJob = scope.launch {
            val startMs = System.currentTimeMillis()
            val (stdout, stderr, exitCode) = exec(args)
            val elapsed = System.currentTimeMillis() - startMs

            val output = buildString {
                if (stdout.isNotEmpty()) append(stdout)
                if (stderr.isNotEmpty()) {
                    if (stdout.isNotEmpty()) append("
")
                    append("── stderr ──
").append(stderr)
                }
            }

            withContext(Dispatchers.Main) {
                rawOutput = output
                copyButton.isEnabled = output.isNotEmpty()
                runButton.isEnabled = true
                val label = if (exitCode == 0) "Done  ${elapsed}ms" else "Exit $exitCode  ${elapsed}ms"
                setStatus(label, error = exitCode != 0)
                showOutput()
                scrollView.scrollTo(0, 0)
            }
        }
    }

    private fun onClearPressed() {
        runJob?.cancel()
        curlInput.setText("")
        resultText.text = ""
        rawOutput = ""
        copyButton.isEnabled = false
        runButton.isEnabled = true
        setStatus("Ready")
    }

    private fun onCopyPressed() {
        val text = resultText.text.toString()
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("curl output", text))
        setStatus("Copied to clipboard")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Core: exec
    // ─────────────────────────────────────────────────────────────────────────

    private data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

    private fun exec(args: List<String>): ExecResult {
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()

            // Read stdout and stderr concurrently to avoid deadlock
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread { stdout = process.inputStream.bufferedReader().readText() }
            val stderrThread = Thread { stderr = process.errorStream.bufferedReader().readText() }
            stdoutThread.start(); stderrThread.start()
            stdoutThread.join(); stderrThread.join()

            val exit = process.waitFor()
            ExecResult(stdout, stderr, exit)
        } catch (e: Exception) {
            ExecResult("", "Failed to run process: ${e.message}", -1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseCurlArgs(input: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        val s = input.trimStart().replace("\
", " ")
        while (i < s.length) {
            when {
                s[i] == ''' -> {
                    i++
                    while (i < s.length && s[i] != ''') { current.append(s[i]); i++ }
                    i++
                }
                s[i] == '"' -> {
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\' && i + 1 < s.length) { i++ }
                        current.append(s[i]); i++
                    }
                    i++
                }
                s[i] == '\' && i + 1 < s.length -> {
                    i++; current.append(s[i]); i++
                }
                s[i].isWhitespace() -> {
                    if (current.isNotEmpty()) { args.add(current.toString()); current.clear() }
                    i++
                }
                else -> { current.append(s[i]); i++ }
            }
        }
        if (current.isNotEmpty()) args.add(current.toString())
        return args
    }

    private fun onPrettyToggle() {
        isPretty = !isPretty
        prettyToggle.setBackgroundColor(
            if (isPretty) Color.parseColor("#1A3A2A") else CARD
        )
        showOutput()
    }

    private fun showOutput() {
        if (rawOutput.isEmpty()) { resultText.text = "(no output)"; return }
        resultText.text = if (isPretty) prettyPrintJson(rawOutput) else rawOutput
    }

    private fun prettyPrintJson(raw: String): String {
        val jsonStart = raw.indexOfFirst { it == '{' || it == '[' }
        if (jsonStart == -1) return raw
        val prefix = if (jsonStart > 0) raw.substring(0, jsonStart) else ""
        val jsonPart = raw.substring(jsonStart)
        return try {
            val pretty = when {
                jsonPart.trimStart().startsWith("{") -> {
                    val obj = org.json.JSONObject(jsonPart)
                    obj.toString(2)
                }
                jsonPart.trimStart().startsWith("[") -> {
                    val arr = org.json.JSONArray(jsonPart)
                    arr.toString(2)
                }
                else -> return raw
            }
            prefix + pretty
        } catch (e: Exception) {
            raw
        }
    }

    private fun setStatus(msg: String, error: Boolean = false) {
        runOnUiThread {
            statusText.text = msg
            statusText.setTextColor(if (error) RED else MUTED)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  View factory helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun button(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(WHITE)
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setBackgroundColor(bg)
        setPadding(dp(8), 0, dp(8), 0)
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int, h: Int, bm: Int = 0) = LinearLayout.LayoutParams(w, h).apply { bottomMargin = bm }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}