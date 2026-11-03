package dev.pridgey.brig.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.HealthRepo
import dev.pridgey.brig.data.Memories
import dev.pridgey.brig.data.Preset
import dev.pridgey.brig.service.LockService

private const val STEPS = 4

/** First-run assistant: welcome, permissions, a starter layout, then switch on. */
@Composable
fun SetupWizard(settings: BrigSettings, update: SettingsUpdate, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var preset by rememberSaveable { mutableStateOf<Preset?>(null) }
    var turnOn by rememberSaveable { mutableStateOf(true) }

    fun finish(skip: Boolean = false) {
        val p = preset
        update { s ->
            val base = if (!skip && p != null) p.apply(s) else s
            base.copy(setupVersion = 1, enabled = if (skip) s.enabled else turnOn)
        }
        if (!skip) LockService.sync(ctx, turnOn)
        onDone()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(STEPS) { i ->
                Box(
                    Modifier.padding(end = 6.dp).height(6.dp).width(if (i == step) 28.dp else 10.dp).clip(CircleShape)
                        .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
            Spacer(Modifier.weight(1f))
            if (step < STEPS - 1) TextButton(onClick = { finish(skip = true) }) { Text("Skip setup") }
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(spring(dampingRatio = 0.85f)) { it / 3 * dir } + fadeIn()) togetherWith
                    (slideOutHorizontally(spring(dampingRatio = 0.85f)) { -it / 3 * dir } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "setup",
        ) { s ->
            when (s) {
                0 -> Welcome()
                1 -> Permissions()
                2 -> Layouts(settings, preset) { preset = it }
                else -> Finish(turnOn) { turnOn = it }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (step < STEPS - 1) step++ else finish() },
                modifier = Modifier.height(52.dp),
                contentPadding = PaddingValues(horizontal = 28.dp),
            ) {
                Text(
                    when (step) {
                        0 -> "Get started"
                        2 -> if (preset == null) "Keep current look" else "Use ${preset!!.label}"
                        STEPS - 1 -> "Done"
                        else -> "Next"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun Welcome() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        SpinningBadge(120.dp, MaterialTheme.colorScheme.primary) {
            Icon(Icons.Filled.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(28.dp))
        Text("Welcome to Brig", style = HeroTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your lock screen, your rules. Two minutes of setup: a few permissions, a starting look, and you're done. Everything can be changed later.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun Permissions() {
    val ctx = LocalContext.current
    val (status, refresh) = rememberSetupStatus()
    var tick by remember { mutableIntStateOf(0) }
    val photos = remember(tick) { Memories.hasPermission(ctx) }
    val health by produceState(false, tick) { value = HealthRepo.hasPermission(ctx) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val healthLauncher = rememberLauncherForActivityResult(HealthRepo.permissionContract()) { tick++ }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Text("Permissions", style = MaterialTheme.typography.headlineMedium)
            Text(
                "The first two are what make Brig work. The rest switch on extra features.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { SetupGroup(status, refresh) }
        item {
            SettingsGroup("Extras · optional") {
                ClickRow(
                    if (photos) "Photo access ✓" else "Photo access",
                    "\"On this day\" memories from your own photos", Icons.Filled.PhotoLibrary,
                ) { if (!photos) photoLauncher.launch(Memories.permission()) }
                if (HealthRepo.available(ctx)) {
                    ClickRow(
                        if (health) "Health Connect ✓" else "Health Connect",
                        "Steps and activity rings", Icons.Filled.FavoriteBorder,
                    ) { if (!health) healthLauncher.launch(HealthRepo.permissions) }
                }
            }
        }
        item { RestrictedSettingsHint() }
    }
}

@Composable
private fun Layouts(settings: BrigSettings, selected: Preset?, onSelect: (Preset?) -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Text("Pick a starting look", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Swipe through and tap one. You can fine-tune anything afterwards.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            Modifier.weight(1f).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(Preset.entries) { p ->
                val isSel = p == selected
                Column(
                    Modifier
                        .clip(RoundedCornerShape(28.dp))
                        .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)) else Modifier)
                        .clickable { onSelect(if (isSel) null else p) }
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LockPreview(p.apply(settings), Modifier.weight(1f).width(200.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(p.label, style = MaterialTheme.typography.titleMedium, color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(
                        p.description, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(200.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Finish(turnOn: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        SpinningBadge(96.dp, MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.height(24.dp))
        Text("All set", style = HeroTitle)
        Spacer(Modifier.height(8.dp))
        Text(
            "Brig appears the moment your screen turns off. Keep a system PIN or fingerprint; Brig sits on top of Android's lock screen, it doesn't replace it.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable { onChange(!turnOn) }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Turn Brig on", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(16.dp))
            Switch(checked = turnOn, onCheckedChange = onChange)
        }
    }
}
