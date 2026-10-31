package dev.pridgey.brig.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pridgey.brig.data.UnlockConfig
import dev.pridgey.brig.util.BrigFonts
import dev.pridgey.brig.util.BrigShapes
import dev.pridgey.brig.util.MathProblems
import dev.pridgey.brig.util.PinHasher
import dev.pridgey.brig.util.PolygonShape
import dev.pridgey.brig.util.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Horizontal shake for wrong answers. */
private suspend fun Animatable<Float, *>.shake() {
    for (x in listOf(24f, -20f, 14f, -8f, 0f)) animateTo(x, spring(stiffness = Spring.StiffnessHigh))
}

@Composable
fun PinGate(config: UnlockConfig, accent: Color, onSuccess: () -> Unit, onCancel: () -> Unit) {
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current

    fun check() {
        val salt = config.pinSalt ?: return onSuccess()
        val hash = config.pinHash ?: return onSuccess()
        if (entry.length < 4 || checking) return
        checking = true
        scope.launch {
            val ok = withContext(Dispatchers.Default) { PinHasher.verify(entry, salt, hash) }
            checking = false
            if (ok) {
                onSuccess()
            } else {
                error = true
                entry = ""
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                shake.shake()
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(PolygonShape(BrigShapes.cookie9)).background(accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, tint = Color.Black)
        }
        Spacer(Modifier.height(16.dp))
        Text(if (error) "Wrong PIN, try again" else "Enter your Brig PIN", style = MaterialTheme.typography.titleLarge, color = if (error) Color(0xFFFF8A80) else Color.White)
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.height(20.dp).graphicsLayer { translationX = shake.value },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(maxOf(4, entry.length)) { i ->
                val filled = i < entry.length
                val size by animateDpAsState(if (filled) 16.dp else 12.dp, spring(dampingRatio = 0.4f), label = "dot")
                Box(Modifier.size(size).clip(CircleShape).background(if (filled) accent else Color.White.copy(alpha = 0.25f)))
            }
        }
        Spacer(Modifier.height(32.dp))
        Keypad(
            accent,
            onDigit = { if (entry.length < 12) { entry += it; error = false } },
            onBackspace = { entry = entry.dropLast(1) },
            onConfirm = ::check,
        )
        TextButton(onClick = onCancel) { Text("Cancel", color = Color.White.copy(alpha = 0.8f)) }
    }
}

@Composable
fun MathGate(difficulty: Int, accent: Color, onSuccess: () -> Unit, onCancel: () -> Unit) {
    var problem by remember { mutableStateOf(MathProblems.generate(difficulty)) }
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (error) "Not quite. New problem:" else "Solve to unlock", style = MaterialTheme.typography.titleMedium, color = if (error) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(12.dp))
        Text(
            "${problem.text} =",
            fontFamily = BrigFonts.display, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.graphicsLayer { translationX = shake.value },
        )
        Text(entry.ifEmpty { "?" }, fontFamily = BrigFonts.display, fontSize = 52.sp, fontWeight = FontWeight.Bold, color = accent)
        Spacer(Modifier.height(24.dp))
        Keypad(
            accent,
            onDigit = { if (entry.length < 6) entry += it },
            onBackspace = { entry = entry.dropLast(1) },
            onConfirm = {
                if (entry.toIntOrNull() == problem.answer) {
                    onSuccess()
                } else {
                    error = true
                    entry = ""
                    problem = MathProblems.generate(difficulty)
                    scope.launch { shake.shake() }
                }
            },
        )
        TextButton(onClick = onCancel) { Text("Cancel", color = Color.White.copy(alpha = 0.8f)) }
    }
}

@Composable
private fun Keypad(accent: Color, onDigit: (Int) -> Unit, onBackspace: () -> Unit, onConfirm: () -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { k ->
                    KeyButton(k, accent) {
                        when (k) {
                            "⌫" -> onBackspace()
                            "✓" -> onConfirm()
                            else -> onDigit(k.toInt())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(key: String, accent: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val confirm = key == "✓"
    // Confirm gets an expressive cookie shape; digits stay round.
    val shape = if (confirm) PolygonShape(BrigShapes.cookie6) else CircleShape
    Box(
        Modifier
            .size(74.dp)
            .pressScale(source, 0.85f)
            .clip(shape)
            .background(if (confirm) accent else Color.White.copy(alpha = 0.12f))
            .clickable(source, null) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            "⌫" -> Icon(Icons.AutoMirrored.Filled.Backspace, "Backspace", tint = Color.White)
            "✓" -> Icon(Icons.Filled.Check, "Confirm", tint = Color.Black)
            else -> Text(key, fontFamily = BrigFonts.display, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
    }
}
