package com.example.otaapplication

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.otaapplication.cloud.CloudStatus
import com.example.otaapplication.cloud.CloudSync

/** Shown instead of the whole app until a staff member signs in. */
@Composable
fun SignInScreen(cloud: CloudSync, status: CloudStatus) {
    val colors = MaterialTheme.colorScheme
    // Card slides up gently once on entry.
    val entry = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entry.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.42f)
                .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .background(Brush.verticalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.85f))))
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                // IME padding before scrolling shrinks the viewport so the focused field stays above the keyboard.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(colors.onPrimary),
                contentAlignment = Alignment.Center
            ) {
                Text("OTA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = colors.primary)
            }
            Spacer(Modifier.height(14.dp))
            Text("ONKAR TEXTILE AGENCY", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = colors.onPrimary, textAlign = TextAlign.Center)
            Text("OTA Business", style = MaterialTheme.typography.bodyMedium, color = colors.onPrimary.copy(alpha = 0.85f))
            Spacer(Modifier.height(28.dp))

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .graphicsLayer {
                        alpha = entry.value
                        translationY = (1f - entry.value) * 48.dp.toPx()
                    },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.elevatedCardColors(containerColor = colors.surface),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Staff sign in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (status.configured) {
                        Text(
                            "Use the email and password the owner gave you. You stay signed in, even offline.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        // A session the server rejected (e.g. the account was removed) explains itself here.
                        SignInForm(cloud = cloud, intro = null, initialError = status.lastError)
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(NOT_CONFIGURED_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Accounts are created by the owner. Ask them if you can't sign in.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = entry.value }
            )
        }
    }
}
