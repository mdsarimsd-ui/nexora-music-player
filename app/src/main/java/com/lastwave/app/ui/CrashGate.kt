package com.lastwave.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.lastwave.app.diagnostics.CrashRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Launch-time crash report gate.
 *
 * When a build cannot get past startup, the crash records the diagnostics
 * stack writes on the device are the only ground truth available, and they
 * sit in app-private storage the user cannot open. This gate surfaces the
 * newest real crash record on the very next launch, before any of the normal
 * startup path runs, with a one-tap copy so it can be shared.
 */
object CrashGate {

    private const val BENIGN = "Ended without a clean-shutdown marker"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val MAX_CHARS = 30_000

    /** Newest real crash record from the last day, or null. Never throws. */
    fun pendingReport(context: Context): String? = runCatching {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        CrashRecorder.crashFiles(context)
            .firstOrNull { file -> file.lastModified() >= cutoff && isRealCrash(file) }
            ?.let(::readable)
    }.getOrNull()

    /** Clears recent crash files so the next launch starts normally. */
    fun clearShown(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            CrashRecorder.crashFiles(context)
                .filter { it.lastModified() >= cutoff }
                .forEach { it.delete() }
        }
    }

    private fun isRealCrash(file: File): Boolean = runCatching {
        val header = file.inputStream().use { stream ->
            val buf = ByteArray(600)
            val n = maxOf(0, stream.read(buf))
            String(buf, 0, n, Charsets.UTF_8)
        }
        val typeLine = header.lineSequence().firstOrNull { it.startsWith("Crash type") }
        typeLine != null && !typeLine.contains(BENIGN)
    }.getOrDefault(false)

    private fun readable(file: File): String = runCatching {
        val text = file.readText(Charsets.UTF_8)
        if (text.length <= MAX_CHARS) text
        else text.take(MAX_CHARS) + "\n… (report truncated)"
    }.getOrElse { "(report unreadable: ${it.message})" }
}

@Composable
fun CrashReportGate(report: String, onContinue: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
        ) {
            Text(
                text = "Nexora crashed last time",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "This is the error your phone recorded. Screenshot or copy it so it can be fixed.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(report))
                    copied = true
                }) {
                    Text(if (copied) "Copied" else "Copy report")
                }
                Button(onClick = onContinue) {
                    Text("Open app")
                }
            }
        }
    }
}
