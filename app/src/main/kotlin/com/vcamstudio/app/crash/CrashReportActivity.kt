package com.vcamstudio.app.crash

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Card
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vcamstudio.app.ui.theme.StudioTheme
import java.io.File

/**
 * r54.4-D6: one parsed crash file — the summary fields the owner was asked
 * to report (process, exception class, message, first 3 app frames, last
 * phase) plus the full text for COPY/SHARE.
 *
 * Header parsing is prefix-based (no regex escapes): header keys are fixed
 * and everything after them is the stack trace.
 */
data class CrashEntry(
    val fileName: String,
    val proc: String,
    val exClass: String,
    val message: String,
    val appFrames: List<String>,
    val lastPhase: String,
    val full: String,
) {
    companion object {
        private val HEADER_KEYS = listOf(
            "app=", "device=", "proc=", "sink=", "at=", "thread=",
            "last_phase_main=", "last_phase_ai=",
        )

        fun parse(f: File): CrashEntry? = runCatching {
            val lines = f.readLines()
            var i = 0
            var proc = "?"
            var pMain = ""
            var pAi = ""
            while (i < lines.size) {
                val l = lines[i]
                val key = HEADER_KEYS.firstOrNull { l.startsWith(it) } ?: break
                when (key) {
                    "proc=" -> proc = l.removePrefix("proc=")
                    "last_phase_main=" -> pMain = l.removePrefix("last_phase_main=")
                    "last_phase_ai=" -> pAi = l.removePrefix("last_phase_ai=")
                }
                i++
            }
            val stack = lines.drop(i).dropWhile { it.isBlank() }
            val exLine = stack.firstOrNull() ?: "unknown"
            val exClass = exLine.substringBefore(": ").trim().ifEmpty { exLine.trim() }
            val message = exLine.substringAfter(": ", "")
            val appFrames = stack.filter { it.trimStart().startsWith("at com.vcamstudio") }.take(3)
            // The relevant breadcrumb is the CRASHING process's own; fall
            // back to the other file's value when the own one is absent.
            val phase = when {
                proc == ":ai" && pAi.isNotEmpty() -> pAi
                proc == ":ai" -> pMain
                pMain.isNotEmpty() -> pMain
                else -> pAi
            }
            CrashEntry(
                fileName = f.name,
                proc = proc,
                exClass = exClass,
                message = message,
                appFrames = appFrames,
                lastPhase = phase.ifEmpty { "unknown" },
                full = lines.joinToString("\n"),
            )
        }.getOrNull()
    }
}

/**
 * r54.4-D3: auto-shown by MainActivity BEFORE the permission flow whenever
 * new crash files exist (newer than the last launch marker). Shows EVERY
 * new crash, newest first, monospace + scrollable, with COPY TO CLIPBOARD,
 * SHARE and CONTINUE (dismiss). D5's Log.e("VCAM-CRASH") covers system bug
 * reports; D4's breadcrumbs arrive via the file headers (last_phase_main /
 * last_phase_ai) and D2 made :ai write these files itself.
 */
class CrashReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = intent.getStringArrayListExtra(PATHS) ?: arrayListOf()
        val crashes = paths.map { File(it) }.mapNotNull { CrashEntry.parse(it) }
        setContent {
            StudioTheme {
                CrashScreen(crashes = crashes, onClose = { finish() })
            }
        }
    }

    companion object {
        private const val PATHS = "crash_paths"

        fun intent(context: Context, files: List<File>): Intent =
            Intent(context, CrashReportActivity::class.java).apply {
                putStringArrayListExtra(PATHS, ArrayList(files.map { it.absolutePath }))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

@Composable
private fun CrashScreen(crashes: List<CrashEntry>, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val allText = crashes.joinToString("\n\n========================\n\n") { it.full }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            "App crashed — ${crashes.size} new crash report(s), newest first",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                clipboard.setText(AnnotatedString(allText))
                copied = true
            }) { Text(if (copied) "COPIED" else "COPY TO CLIPBOARD") }
            Button(onClick = {
                val i = Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "VCamStudio crash report")
                    putExtra(Intent.EXTRA_TEXT, allText)
                }
                runCatching { startActivity(Intent.createChooser(i, "Share crash report")) }
            }) { Text("SHARE") }
            Button(onClick = onClose) { Text("CONTINUE") }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            crashes.forEach { c ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("process: ${c.proc}", style = MaterialTheme.typography.labelLarge)
                        Text("exception: ${c.exClass}", style = MaterialTheme.typography.bodyMedium)
                        Text("message: ${c.message}", style = MaterialTheme.typography.bodyMedium)
                        Text("last phase: ${c.lastPhase}", style = MaterialTheme.typography.bodyMedium)
                        Text("app frames:", style = MaterialTheme.typography.labelMedium)
                        c.appFrames.forEach { f ->
                            Text(
                                "  $f",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer {
                            Text(
                                c.full,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
