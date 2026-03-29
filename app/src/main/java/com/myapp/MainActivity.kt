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
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    // ── Colors ────────────────────────────────────────────────────────────────
    private val BG     = Color.parseColor("#0F0F0F")
    private val CARD   = Color.parseColor("#1A1A1A")
    private val ACCENT = Color.parseColor("#1DB954")
    private val WHITE  = Color.WHITE
    private val MUTED  = Color.parseColor("#888888")
    private val RED    = Color.parseColor("#CF6679")

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var curlInput: EditText
    private lateinit var runButton: Button
    private lateinit var clearButton: Button
    private lateinit var copyButton: Button
    private lateinit var resultText: TextView
    private lateinit var statusText: TextView
    private lateinit var scrollView: ScrollView

    // ── State ─────────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var runJob: Job? = null

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

        // Header
        layout.addView(
            TextView(this).apply {
                text = "cURL Runner"
                setTextColor(WHITE)
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                gravity = Gravity.CENTER
            },
            lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(20))
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
            hint = "curl -X POST https://... -H \"...\" -d '{...}'"
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
        runButton   = button("▶  Run", ACCENT) { onRunPressed() }
        clearButton = button("Clear",  CARD)   { onClearPressed() }
        actionRow.addView(runButton,   LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        actionRow.addView(clearButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        layout.addView(actionRow, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(14)))

        // Status
        statusText = TextView(this).apply {
            text = "Ready"
            setTextColor(MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        layout.addView(statusText, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(10)))

        // Result label + copy button
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
        copyButton = button("Copy", CARD) { onCopyPressed() }.apply {
            textSize = 11f
            isEnabled = false
        }
        resultHeader.addView(copyButton, LinearLayout.LayoutParams(dp(72), dp(32)))
        layout.addView(resultHeader, lp(MATCH_PARENT, WRAP_CONTENT, bm = dp(6)))

        // Result output
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
    //  Actions
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
        copyButton.isEnabled = false
        runButton.isEnabled = false

        runJob = scope.launch {
            val result = try {
                executeCurl(args)
            } catch (e: Exception) {
                "ERROR: ${e.javaClass.simpleName}: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                resultText.text = result.ifEmpty { "(empty response)" }
                copyButton.isEnabled = result.isNotEmpty()
                runButton.isEnabled = true
                scrollView.scrollTo(0, 0)
            }
        }
    }

    private fun onClearPressed() {
        runJob?.cancel()
        curlInput.setText("")
        resultText.text = ""
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
    //  Core: parse curl args → HttpURLConnection
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeCurl(args: List<String>): String {
        var method = "GET"
        var url: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        var body: String? = null
        var followRedirects = true

        var i = 1 // skip "curl"
        while (i < args.size) {
            when (args[i]) {
                "-X", "--request" -> { method = args[++i] }
                "-H", "--header"  -> {
                    val h = args[++i]
                    val colon = h.indexOf(':')
                    if (colon > 0) headers.add(h.substring(0, colon).trim() to h.substring(colon + 1).trim())
                }
                "-d", "--data", "--data-raw", "--data-ascii", "--data-binary" -> {
                    body = args[++i]
                    if (method == "GET") method = "POST"
                }
                "-u", "--user" -> {
                    val encoded = android.util.Base64.encodeToString(args[++i].toByteArray(), android.util.Base64.NO_WRAP)
                    headers.add("Authorization" to "Basic $encoded")
                }
                "-L", "--location"    -> { followRedirects = true }
                "--no-location"       -> { followRedirects = false }
                "-G", "--get"         -> { method = "GET" }
                "-I", "--head"        -> { method = "HEAD" }
                "-s", "--silent",
                "-v", "--verbose",
                "-k", "--insecure",
                "--compressed"        -> { /* no-op */ }
                "-o", "--output"      -> { i++ /* skip filename */ }
                else -> {
                    val a = args[i]
                    if (!a.startsWith("-") && url == null) url = a
                }
            }
            i++
        }

        if (url == null) return "ERROR: No URL found in command"

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = followRedirects
        conn.doInput = true

        for ((k, v) in headers) conn.setRequestProperty(k, v)

        if (body != null) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""

        val respHeaders = buildString {
            append("HTTP $code ${conn.responseMessage}\n")
            conn.headerFields.entries
                .filter { it.key != null }
                .forEach { (k, v) -> append("$k: ${v.joinToString(", ")}\n") }
        }

        conn.disconnect()
        setStatus("HTTP $code ${conn.responseMessage}", error = code >= 400)
        return "$respHeaders\n$responseBody"
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Shell-style argument parser
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseCurlArgs(input: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        val s = input.trimStart().replace("\\\n", " ")
        while (i < s.length) {
            when {
                s[i] == '\'' -> {
                    i++
                    while (i < s.length && s[i] != '\'') { current.append(s[i]); i++ }
                    i++
                }
                s[i] == '"' -> {
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\' && i + 1 < s.length) { i++ }
                        current.append(s[i]); i++
                    }
                    i++
                }
                s[i] == '\\' && i + 1 < s.length -> { i++; current.append(s[i]); i++ }
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

    // ─────────────────────────────────────────────────────────────────────────
    //  View helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun setStatus(msg: String, error: Boolean = false) {
        runOnUiThread {
            statusText.text = msg
            statusText.setTextColor(if (error) RED else MUTED)
        }
    }

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
