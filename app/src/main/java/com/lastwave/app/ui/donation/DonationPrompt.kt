package com.lastwave.app.ui.donation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lastwave.app.R
import kotlinx.coroutines.delay

private const val PREFS = "nexora_promo"
private const val KEY_LAUNCHES = "donation_launch_count"
private const val KEY_LAST_SHOWN_DAY = "donation_last_shown_day"
private const val KEY_LAST_SUPPORTED = "donation_last_supported"

/**
 * Donation prompt: a liquid-glass card that opens with the app on a slow
 * cadence (every 7th launch, 30 once the dev was supported, never twice a
 * day) so it asks quietly instead of nagging.
 */
@Composable
fun DonationPromptHost() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(2800)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val launches = prefs.getInt(KEY_LAUNCHES, 0) + 1
        prefs.edit().putInt(KEY_LAUNCHES, launches).apply()
        val today = System.currentTimeMillis() / 86_400_000L
        val shownDay = prefs.getLong(KEY_LAST_SHOWN_DAY, -1L)
        val supported = prefs.getLong(KEY_LAST_SUPPORTED, 0L)
        val cadence = if (supported > 0L) 30 else 7
        // First-ever ask comes early (3rd launch) so new users meet it once;
        // after that it stays on the slow cadence.
        val due = if (shownDay == -1L) launches >= 3 else launches % cadence == 0
        if (due && shownDay != today) {
            prefs.edit().putLong(KEY_LAST_SHOWN_DAY, today).apply()
            show = true
        }
    }

    if (show) {
        DonationDialog(
            onDismiss = { show = false },
            onSupport = {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_SUPPORTED, System.currentTimeMillis()).apply()
                show = false
            },
        )
    }
}

private fun openUpi(context: Context) {
    val upiIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("upi://pay?pa=9112270039@fam&pn=Sarim%20Siddiqui&cu=INR"),
    )
    if (!runCatching { context.startActivity(upiIntent) }.isSuccess) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("nexora_upi", "9112270039@fam"))
        Toast.makeText(context, "UPI ID copied: 9112270039@fam", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DonationDialog(onDismiss: () -> Unit, onSupport: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.06f),
                        ),
                    ),
                    shape = RoundedCornerShape(28.dp),
                )
                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(28.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = Color(0xFFFF5C7A),
                modifier = Modifier.height(26.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.donation_prompt_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.donation_prompt_body),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onSupport()
                    openUpi(context)
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.92f),
                    contentColor = Color(0xFF14121F),
                ),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.height(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.donation_prompt_support), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.donation_prompt_later),
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}
