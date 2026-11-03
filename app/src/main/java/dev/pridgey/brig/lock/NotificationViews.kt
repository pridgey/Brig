package dev.pridgey.brig.lock

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.pridgey.brig.data.ActiveEffects
import dev.pridgey.brig.data.AppNotificationMode
import dev.pridgey.brig.data.BrigSettings
import dev.pridgey.brig.data.NotificationRules
import dev.pridgey.brig.data.WidgetType
import dev.pridgey.brig.service.BrigNotification
import dev.pridgey.brig.service.BrigNotificationListener
import dev.pridgey.brig.service.ChatMessage
import dev.pridgey.brig.service.NotifPriority
import dev.pridgey.brig.service.NotificationGroup
import dev.pridgey.brig.util.BrigShapes
import dev.pridgey.brig.util.PolygonShape
import kotlinx.coroutines.delay

private fun relativeTime(t: Long) =
    DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

private fun dismissAll(groups: List<NotificationGroup>) =
    groups.flatMap { it.items }.filter { it.clearable }.forEach { BrigNotificationListener.dismiss(it.key) }

/** Notifications that belong in the main list (live ones get their own widget when it's on). */
private fun visibleNotifications(all: List<BrigNotification>, rules: NotificationRules, liveWidget: Boolean) = all.filter { n ->
    val mode = rules.appModes[n.pkg]
    mode != AppNotificationMode.BLOCK &&
        (rules.showSilent || !n.silent || mode == AppNotificationMode.PRIORITY) &&
        !(liveWidget && n.live != null && n.ongoing)
}

@Composable
internal fun NotificationsWidget(settings: BrigSettings, effects: ActiveEffects, controller: LockController) {
    val all by BrigNotificationListener.notifications.collectAsState()
    val connected by BrigNotificationListener.connected.collectAsState()
    val rules = settings.notifications
    if (!connected) {
        Text("Grant notification access in Brig to see notifications here", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.5f))
        return
    }
    val visible = visibleNotifications(all, rules, settings.isWidgetOn(WidgetType.LIVE))
    val (main, quiet) = NotifPriority.arrange(visible, rules.appModes, rules.groupByApp, rules.smartPriority)

    var revealed by remember { mutableStateOf(!rules.glanceMode) }
    LaunchedEffect(rules.glanceMode) { revealed = !rules.glanceMode }
    // Glance mode folds back to icons after a while.
    LaunchedEffect(revealed, rules.glanceMode) {
        if (revealed && rules.glanceMode) {
            delay(20_000)
            revealed = false
        }
    }
    if (!revealed) {
        GlanceRow(main + quiet) { revealed = true }
        return
    }

    var expanded by remember { mutableStateOf<String?>(null) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var quietOpen by remember { mutableStateOf(false) }
    fun hidden(pkg: String) = rules.hideAllContent || effects.hideContent || rules.appModes[pkg] == AppNotificationMode.HIDE_CONTENT

    @Composable
    fun Card(n: BrigNotification) = NotificationCard(
        n,
        hideContent = hidden(n.pkg),
        allowReply = rules.allowReply,
        conversation = rules.conversationView,
        expanded = expanded == n.key,
        onToggle = { expanded = if (expanded == n.key) null else n.key },
        controller = controller,
    )

    @Composable
    fun Group(g: NotificationGroup) = when {
        g.items.size == 1 -> Card(g.latest)
        openGroup == g.key -> ExpandedGroup(g, onCollapse = { openGroup = null }) { Card(it) }
        else -> CollapsedGroupCard(g, hidden(g.pkg), onClick = { openGroup = g.key })
    }

    Column(Modifier.animateContentSize(spring(dampingRatio = 0.8f)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shown = main.take(rules.maxShown)
        shown.forEachIndexed { i, g ->
            key(g.key) {
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = appear,
                    enter = fadeIn(tween(300, delayMillis = i * 60)) + slideInVertically(spring(dampingRatio = 0.7f)) { it / 2 } + scaleIn(initialScale = 0.9f),
                ) { Group(g) }
            }
        }
        val more = main.drop(shown.size).sumOf { it.items.size }
        if (more > 0) {
            Text(
                "+$more more",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (quiet.isNotEmpty()) {
            QuietStack(quiet, quietOpen, onToggle = { quietOpen = !quietOpen }) { g -> key(g.key) { Group(g) } }
        }
    }
}

/** Just app icons with counts. Shifts a few pixels each minute so nothing sits in one place. */
@Composable
private fun GlanceRow(groups: List<NotificationGroup>, onReveal: () -> Unit) {
    if (groups.isEmpty()) return
    val minute = remember { System.currentTimeMillis() / 60_000 }
    val dx = ((minute % 5) - 2).toInt()
    val dy = (((minute / 5) % 3) - 1).toInt()
    val apps = groups.flatMap { it.items }.groupBy { it.pkg }
    Row(
        Modifier
            .offset(dx.dp, dy.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = LocalChipAlpha.current))
            .clickable(onClick = onReveal)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        apps.entries.take(8).forEach { (_, items) ->
            Box {
                items.first().appIcon?.let {
                    Image(it, items.first().appLabel, Modifier.size(28.dp).clip(PolygonShape(BrigShapes.cookie9)), contentScale = ContentScale.Crop)
                }
                if (items.size > 1) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Text("${items.size.coerceAtMost(9)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary) }
                }
            }
        }
        if (apps.size > 8) Text("+${apps.size - 8}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
    }
}

/** Promos, silent and low-priority notifications, folded into one slim row. */
@Composable
private fun QuietStack(groups: List<NotificationGroup>, open: Boolean, onToggle: () -> Unit, group: @Composable (NotificationGroup) -> Unit) {
    val count = groups.sumOf { it.items.size }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.NotificationsPaused, null, Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.width(8.dp))
            Text("Quiet · $count", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f))
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                groups.take(5).forEach { g ->
                    g.latest.appIcon?.let { Image(it, null, Modifier.size(20.dp).clip(CircleShape)) }
                }
            }
            if (open && groups.any { it.clearable }) {
                TextButton(onClick = { dismissAll(groups) }) { Text("Clear", color = Color.White.copy(alpha = 0.8f)) }
            } else {
                Text(if (open) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
            }
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { groups.forEach { group(it) } }
        }
    }
}

@Composable
private fun CountPill(count: Int) {
    Box(
        Modifier.padding(start = 6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text("$count", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/** One card standing in for a stack of notifications from the same app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsedGroupCard(g: NotificationGroup, hideContent: Boolean, onClick: () -> Unit) {
    val previews = 3
    val content: @Composable () -> Unit = {
        Column {
            GlassCard(onClick = onClick) {
                Row(verticalAlignment = Alignment.Top) {
                    g.latest.appIcon?.let {
                        Image(it, null, Modifier.size(38.dp).clip(PolygonShape(BrigShapes.cookie12)), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.latest.appLabel, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                            CountPill(g.items.size)
                            Spacer(Modifier.weight(1f))
                            Text(relativeTime(g.latest.postTime), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                        }
                        if (hideContent) {
                            Text("${g.items.size} notifications", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                        } else {
                            g.items.take(previews).forEach { n ->
                                Text(
                                    buildAnnotatedString {
                                        if (n.title.isNotBlank()) {
                                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) { append(n.title) }
                                            if (n.text.isNotBlank()) append("  ")
                                        }
                                        append(n.text)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.8f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (g.items.size > previews) {
                                Text("+${g.items.size - previews} more", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
            // Peeking edge so it reads as a stack.
            Box(
                Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(Color.White.copy(alpha = 0.07f)),
            )
        }
    }
    if (g.clearable) {
        val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
            if (v != SwipeToDismissBoxValue.Settled) {
                dismissAll(listOf(g))
                true
            } else false
        })
        SwipeToDismissBox(state = state, backgroundContent = {}) { content() }
    } else {
        content()
    }
}

/** Header plus each notification in the group as its own card. */
@Composable
private fun ExpandedGroup(g: NotificationGroup, onCollapse: () -> Unit, card: @Composable (BrigNotification) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onCollapse).padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(g.latest.appLabel, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
            CountPill(g.items.size)
            Spacer(Modifier.weight(1f))
            if (g.clearable) TextButton(onClick = { dismissAll(listOf(g)) }) { Text("Clear all", color = Color.White.copy(alpha = 0.8f)) }
            TextButton(onClick = onCollapse) { Text("Collapse", color = Color.White.copy(alpha = 0.8f)) }
        }
        g.items.forEach { n -> key(n.key) { card(n) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationCard(
    n: BrigNotification,
    hideContent: Boolean,
    allowReply: Boolean,
    conversation: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    controller: LockController,
) {
    val chat = conversation && !hideContent && n.messages.isNotEmpty()
    val content: @Composable () -> Unit = {
        GlassCard(onClick = onToggle) {
            Row(verticalAlignment = Alignment.Top) {
                val avatar = if (!hideContent) n.largeIcon ?: n.appIcon else n.appIcon
                avatar?.let {
                    Image(it, null, Modifier.size(38.dp).clip(PolygonShape(BrigShapes.cookie12)), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.appLabel, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f), maxLines = 1)
                        Text(relativeTime(n.postTime), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                    }
                    when {
                        hideContent -> Text("Content hidden", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                        chat && expanded -> {
                            val heading = n.conversationTitle ?: n.title
                            if (heading.isNotBlank()) Text(heading, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        else -> {
                            if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (n.text.isNotBlank()) Text(n.text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            n.live?.let { if (!hideContent) LiveProgress(it, Modifier.padding(top = 10.dp)) }
            AnimatedVisibility(expanded) {
                Column {
                    if (chat) ChatBubbles(n.messages.takeLast(5), n.isGroupChat)
                    NotificationActions(n, allowReply && !hideContent, inlineReply = chat, controller)
                }
            }
        }
    }
    if (n.clearable) {
        val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
            if (v != SwipeToDismissBoxValue.Settled) {
                BrigNotificationListener.dismiss(n.key)
                true
            } else false
        })
        SwipeToDismissBox(state = state, backgroundContent = {}) { content() }
    } else {
        content()
    }
}

@Composable
private fun ChatBubbles(messages: List<ChatMessage>, group: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        messages.forEachIndexed { i, m ->
            val showName = group && !m.fromMe && messages.getOrNull(i - 1)?.sender != m.sender
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start) {
                if (showName) {
                    Text(m.sender, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(start = 10.dp, top = 2.dp))
                }
                Box(
                    Modifier
                        .widthIn(max = 260.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 18.dp, topEnd = 18.dp,
                                bottomStart = if (m.fromMe) 18.dp else 6.dp, bottomEnd = if (m.fromMe) 6.dp else 18.dp,
                            ),
                        )
                        .background(if (m.fromMe) accent else Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(m.text, style = MaterialTheme.typography.bodyMedium, color = if (m.fromMe) MaterialTheme.colorScheme.onPrimary else Color.White)
                }
            }
        }
    }
}

@Composable
private fun NotificationActions(n: BrigNotification, allowReply: Boolean, inlineReply: Boolean, controller: LockController) {
    val ctx = LocalContext.current
    var replying by remember { mutableStateOf(inlineReply) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (n.contentIntent != null) FilledTonalButton(onClick = { controller.openNotification(n) }) { Text("Open") }
            if (allowReply && n.replyAction != null && !inlineReply) FilledTonalButton(onClick = { replying = !replying }) { Text("Reply") }
            if (n.clearable) TextButton(onClick = { BrigNotificationListener.dismiss(n.key) }) { Text("Dismiss", color = Color.White) }
        }
        if (replying && allowReply && n.replyAction != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Reply…") }, maxLines = 3,
                    shape = RoundedCornerShape(20.dp),
                )
                IconButton(onClick = {
                    val action = n.replyAction
                    if (text.isNotBlank() && BrigNotificationListener.reply(ctx, action, text)) {
                        text = ""
                        if (!inlineReply) replying = false
                        Toast.makeText(ctx, "Sent", Toast.LENGTH_SHORT).show()
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White) }
            }
        }
    }
}

// ---------------------------------------------------------------- Live activities

/** "12:04" from milliseconds, with hours when needed. */
internal fun clockSpan(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun LiveProgress(live: dev.pridgey.brig.service.LiveInfo, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier) {
        live.chronometerBase?.let { base ->
            val now by produceState(System.currentTimeMillis(), base) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1000)
                }
            }
            Text(
                clockSpan(if (live.countDown) base - now else now - base),
                style = MaterialTheme.typography.headlineSmall, color = Color.White,
            )
        }
        when {
            live.indeterminate -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = accent, trackColor = Color.White.copy(alpha = 0.15f))
            live.progress != null -> LinearProgressIndicator(
                progress = { live.progress }, modifier = Modifier.fillMaxWidth().clip(CircleShape),
                color = accent, trackColor = Color.White.copy(alpha = 0.15f),
            )
        }
    }
}

/** Ongoing things with progress or a timer: downloads, deliveries, rides, timers, navigation. */
@Composable
internal fun LiveActivitiesWidget(settings: BrigSettings, effects: ActiveEffects, controller: LockController) {
    val all by BrigNotificationListener.notifications.collectAsState()
    val rules = settings.notifications
    val live = all.filter { it.live != null && it.ongoing && rules.appModes[it.pkg] != AppNotificationMode.BLOCK }
    if (live.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        live.take(3).forEach { n ->
            key(n.key) {
                val hide = rules.hideAllContent || effects.hideContent || rules.appModes[n.pkg] == AppNotificationMode.HIDE_CONTENT
                GlassCard(onClick = { controller.openNotification(n) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        n.appIcon?.let { Image(it, null, Modifier.size(28.dp).clip(PolygonShape(BrigShapes.cookie9))) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (hide) n.appLabel else n.title.ifBlank { n.appLabel },
                                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (!hide && n.text.isNotBlank()) {
                                Text(n.text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    n.live?.let { LiveProgress(it, Modifier.padding(top = 8.dp)) }
                }
            }
        }
    }
}
