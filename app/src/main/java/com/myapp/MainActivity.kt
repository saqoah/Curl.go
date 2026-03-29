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

        // Cancel any previous run
        runJob?.cancel()

        setStatus("Running…")
        resultText.text = ""
        copyButton.isEnabled = false
        runButton.isEnabled = false

        runJob = scope.launch {
            val (stdout, stderr, exitCode) = exec(args)
            val output = buildString {
                if (stdout.isNotEmpty()) append(stdout)
                if (stderr.isNotEmpty()) {
                    if (stdout.isNotEmpty()) append("\n")
                    append("── stderr ──\n").append(stderr)
                }
            }
            withContext(Dispatchers.Main) {
                resultText.text = output.ifEmpty { "(no output)" }
                copyButton.isEnabled = output.isNotEmpty()
                runButton.isEnabled = true
                val label = if (exitCode == 0) "Done (exit 0)" else "Exit code $exitCode"
                setStatus(label, error = exitCode != 0)
                // scroll output to top
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

    /**
     * Minimal shell-style argument parser.
     * Handles single/double quoted strings and backslash-escaped spaces.
     * Does NOT invoke a shell — safe from injection.
     */
    private fun parseCurlArgs(input: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        // Strip leading 'curl ' if pasted with it; we re-add programmatically
        val s = input.trimStart().replace("\\\n", " ")
        while (i < s.length) {
            when {
                s[i] == '\'' -> {
                    i++
                    while (i < s.length && s[i] != '\'') { current.append(s[i]); i++ }
                    i++ // closing quote
                }
                s[i] == '"' -> {
                    i++
                    while (i < s.length && s[i] != '"') {
                        if (s[i] == '\\' && i + 1 < s.length) { i++ }
                        current.append(s[i]); i++
                    }
                    i++
                }
                s[i] == '\\' && i + 1 < s.length -> {
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
