package org.mothvpn.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mothvpn.core.SubscriptionChanges

/**
 * Developer option "Subscription changes": the change history of one subscription, newest first. Each update that changed
 * provider content is a card with its date and time, the servers added, changed and removed, and other changes (expiry, traffic
 * limit, provider name, announcement). A tap on a changed server shows exactly which fields changed (secret values hidden).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SubscriptionChangesSheet(source: org.mothvpn.core.SubscriptionSource, close: () -> Unit) {
    val context = LocalContext.current
    val entries by produceState<List<SubscriptionChanges.Entry>?>(null, source.id, source.changedAt) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SubscriptionChangeLog.of(context, source.id) }
    }
    // The sheet opens only once the history is read: a sheet that grows after opening would keep its first, short height.
    val list = entries ?: return
    val locale = AppLanguage.context().resources.configuration.locales[0]
    val zone = java.time.ZoneId.systemDefault()
    val hour24 = android.text.format.DateFormat.is24HourFormat(AppLanguage.context())
    var detail by remember { mutableStateOf<SubscriptionChanges.Changed?>(null) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = NdTone.sheet()) {
        Text(tr(R.string.changes_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, end = 24.dp))
        Text(source.title(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        if (list.isEmpty()) Text(tr(R.string.changes_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.75f).dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.at }) { e ->
                Surface(shape = MaterialTheme.shapes.medium, color = NdTone.raised(), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Text(org.mothvpn.core.SubscriptionMoments.format(e.at, locale, zone, withTime = true, hour24 = hour24), style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp))
                        val counts = listOfNotNull(e.added.size.takeIf { it > 0 }?.let { tr(R.string.changes_added, it) }, e.changed.size.takeIf { it > 0 }?.let { tr(R.string.changes_changed, it) },
                            e.removed.size.takeIf { it > 0 }?.let { tr(R.string.changes_removed, it) }).joinToString(" · ")
                        if (counts.isNotEmpty()) Text(counts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                        e.added.forEach { ChangeRow(Icons.Rounded.Add, it.name, it.subtitle()) }
                        e.changed.forEach { c -> ChangeRow(Icons.Rounded.Edit, c.after.name, c.after.subtitle(), onClick = { detail = c }) }
                        e.removed.forEach { ChangeRow(Icons.Rounded.Remove, it.name, it.subtitle()) }
                        e.other.forEach { o ->
                            val text = when (o) {
                                SubscriptionChanges.OTHER_EXPIRES -> tr(R.string.changes_other_expires)
                                SubscriptionChanges.OTHER_TOTAL -> tr(R.string.changes_other_total)
                                SubscriptionChanges.OTHER_PROVIDER -> tr(R.string.changes_other_provider)
                                SubscriptionChanges.OTHER_ANNOUNCE -> tr(R.string.changes_other_announce)
                                else -> null
                            }
                            if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
                        }
                    }
                }
            }
        }
    }
    detail?.let { c -> ServerChangeDialog(c) { detail = null } }
}

private fun SubscriptionChanges.Server.subtitle() = listOf(protocol, address).filter { it.isNotBlank() }.joinToString(" · ")

@Composable private fun ChangeRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onClick != null) Modifier.pressScale().clickable(onClick = onClick) else Modifier)
        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

/** Exactly what changed on one server: every field with its old and new value (secret fields only as "changed"). */
@Composable private fun ServerChangeDialog(c: SubscriptionChanges.Changed, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(c.after.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            c.fields.forEach { f ->
                Column {
                    val label = when (f.key) {
                        SubscriptionChanges.FIELD_NAME -> tr(R.string.changes_field_name)
                        SubscriptionChanges.FIELD_PROTOCOL -> tr(R.string.changes_field_protocol)
                        "server", "settings.vnext[0].address", "settings.servers[0].address" -> tr(R.string.changes_field_address)
                        "server_port", "settings.vnext[0].port", "settings.servers[0].port" -> tr(R.string.changes_field_port)
                        else -> f.key
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    val none = tr(R.string.changes_absent)
                    val value = if (f.secret) when {
                        f.old == null -> tr(R.string.changes_secret_added)
                        f.new == null -> tr(R.string.changes_secret_removed)
                        else -> tr(R.string.changes_secret_changed)
                    } else "${f.old ?: none}  →  ${f.new ?: none}"
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close, modifier = Modifier.pressScale()) { Text(tr(R.string.sub_link_close)) } })
}
