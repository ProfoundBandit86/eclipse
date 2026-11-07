package com.eclipse.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown instead of the launcher after a crash, so the error can be copied or shared.
 * Deliberately uses nothing from the rest of the app, so it can't crash the same way.
 */
@Composable
fun CrashScreen(report: String, onContinue: () -> Unit) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    Column(Modifier.fillMaxSize().background(Color(0xFF1B1C20)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Eclipse crashed last time", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Copy or share this report (it helps whoever fixes the problem), then press Continue to try again.",
            color = Color(0xCCFFFFFF)
        )
        // (each button gets an equal share: a Pill fills whatever width it is given)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Copy report", Modifier.weight(1f), primary = true, focusRequester = first) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Eclipse crash report", report))
            }
            Pill("Share", Modifier.weight(1f)) {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                ctx.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Pill("Continue", Modifier.weight(1f), onClick = onContinue)
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111215)).padding(12.dp)) {
            Text(
                report,
                color = Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        }
    }
}
