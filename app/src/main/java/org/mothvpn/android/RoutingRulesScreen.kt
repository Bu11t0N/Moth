package org.mothvpn.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mothvpn.core.CustomRouting
import org.mothvpn.core.RoutingRule
import org.mothvpn.core.RuleText
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch

private fun typeLabel(type: String) = when (type) {
    CustomRouting.DOMAIN -> tr(R.string.rules_type_domain); CustomRouting.SUFFIX -> tr(R.string.rules_type_suffix)
    CustomRouting.KEYWORD -> tr(R.string.rules_type_keyword); else -> tr(R.string.rules_type_cidr)
}
private fun typeExample(type: String) = when (type) {
    CustomRouting.DOMAIN -> tr(R.string.rules_ex_domain); CustomRouting.SUFFIX -> tr(R.string.rules_ex_suffix)
    CustomRouting.KEYWORD -> tr(R.string.rules_ex_keyword); else -> tr(R.string.rules_ex_cidr)
}
private fun actionLabel(action: String) = when (action) {
    CustomRouting.DIRECT -> tr(R.string.rules_action_direct); CustomRouting.BLOCK -> tr(R.string.rules_action_block); else -> tr(R.string.rules_action_proxy)
}

/**
 * Settings → Routing → Routing rules: the user's ordered list (the first matching rule wins), then the default rules (today one: Russian
 * sites and IPs directly, [org.mothvpn.core.ClientSettings.directRu]). Every rule has its own switch; a user rule also has a compact ↑/↓
 * pair and a delete button, and a tap on it opens the editor. Values are never logged.
 */
@Composable fun RoutingRulesPage(rules: List<RoutingRule>, directRu: Boolean, enabled: Boolean, save: (List<RoutingRule>) -> Unit, saveDirectRu: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf<Int?>(null) } // index, or -1 for a new rule
    var deleting by remember { mutableStateOf<Int?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var importText by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            status = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeDocument(context, uri, RuleText.format(rules)) } }
                .fold({ Logger.i("Rules", "exported ${rules.size} rule(s)"); tr(R.string.rules_exported, rules.size) }, { Logger.w("Rules", "export failed: ${it.javaClass.simpleName}"); tr(R.string.backup_failed) })
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readDocument(context, uri, RuleText.MAX_CHARS) } }
                .onSuccess { importText = it }.onFailure { Logger.w("Rules", "reading the file failed: ${it.javaClass.simpleName}"); status = tr(R.string.backup_failed) }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!enabled) Text(tr(R.string.settings_wait_hint))
        Text(tr(R.string.rules_hint), style = MaterialTheme.typography.bodySmall)
        // One main action; import (file, clipboard) and export sit in the ⋮ menu next to it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            NdButton(enabled = enabled && rules.size < CustomRouting.MAX_RULES, onClick = { editing = -1 }, modifier = Modifier.weight(1f).pressScale(enabled = enabled && rules.size < CustomRouting.MAX_RULES)) { Text(tr(R.string.rules_add)) }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.pressScale()) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.MoreVert, contentDescription = tr(R.string.rules_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(tr(R.string.rules_import_file)) }, enabled = enabled, onClick = { menu = false; status = ""; picker.launch(arrayOf("text/*")) })
                    DropdownMenuItem(text = { Text(tr(R.string.rules_paste)) }, enabled = enabled, onClick = {
                        menu = false; status = ""
                        clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { importText = it.take(RuleText.MAX_CHARS) } ?: run { status = tr(R.string.import_clipboard_empty) }
                    })
                    DropdownMenuItem(text = { Text(tr(R.string.rules_export)) }, enabled = rules.isNotEmpty(), onClick = { menu = false; status = ""; exporter.launch("moth-rules.txt") })
                }
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (rules.isEmpty()) Text(tr(R.string.rules_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(rules) { i, rule ->
                // A named rule shows its name, with the value in the caption; an unnamed one shows its value as before.
                RuleRow(rule.title, "${i + 1}. " + (if (rule.name.isNotEmpty()) rule.value + " · " else "") + "${typeLabel(rule.type)} → ${actionLabel(rule.action)}", rule.enabled, enabled,
                    onToggle = { save(CustomRouting.setEnabled(rules, i, it)) }, onClick = { editing = i },
                    up = { save(CustomRouting.move(rules, i, i - 1)) }.takeIf { i > 0 }, down = { save(CustomRouting.move(rules, i, i + 1)) }.takeIf { i < rules.lastIndex },
                    delete = { deleting = i })
            }
            item(key = "defaults") {
                Text(tr(R.string.rules_default_head), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            }
            item(key = "ru") { RuleRow(tr(R.string.direct_ru), tr(R.string.rules_default_ru_sum), directRu, enabled, onToggle = saveDirectRu, onClick = { saveDirectRu(!directRu) }) }
        }
    }
    deleting?.let { i ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr(R.string.rules_delete_confirm)) },
            text = { rules.getOrNull(i)?.let { Text(it.title, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
            confirmButton = { TextButton(onClick = { deleting = null; if (i in rules.indices) save(rules.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.pressScale()) { Text(tr(R.string.rules_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_cancel_32)) } })
    }
    importText?.let { text ->
        RulesImportDialog(text, rules, close = { importText = null }) { result ->
            importText = null; save(result.rules)
            Logger.i("Rules", "imported: added ${result.added}, invalid ${result.invalid}, duplicates ${result.duplicates}, over limit ${result.overLimit}")
            status = importSummary(result)
        }
    }
    editing?.let { index ->
        val original = rules.getOrNull(index)
        RuleDialog(original, close = { editing = null }) { rule ->
            val others = rules.filterIndexed { i, _ -> i != index }
            when {
                others.any { it.type == rule.type && it.value == rule.value } -> tr(R.string.rules_duplicate)
                index < 0 && rules.size >= CustomRouting.MAX_RULES -> tr(R.string.rules_limit)
                else -> { editing = null; save(if (index < 0) rules + rule else rules.toMutableList().apply { set(index, rule) }); null }
            }
        }
    }
}

/**
 * Add or edit one rule, only the essentials: an optional name (first), the value (domain or IP), and where it goes (Direct / VPN / Block in one
 * segmented row). The match type is a single compact line with a menu; an IP address or network switches it to IP by itself, until the
 * user picks a type. Deleting is the row's own button. [submit] returns an error to show, or null when the rule was saved.
 */
@Composable private fun RuleDialog(initial: RoutingRule?, close: () -> Unit, submit: (RoutingRule) -> String?) {
    var chosenType by remember { mutableStateOf(initial?.type) } // null: follow the value (IP → IP / CIDR, else domain suffix)
    var action by remember { mutableStateOf(initial?.action ?: CustomRouting.DIRECT) }
    var value by remember { mutableStateOf(initial?.value.orEmpty()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val type = chosenType ?: if (CustomRouting.normalize(CustomRouting.CIDR, value) != null) CustomRouting.CIDR else CustomRouting.SUFFIX
    val normalized = CustomRouting.normalize(type, value)
    AlertDialog(onDismissRequest = close, title = { Text(tr(if (initial == null) R.string.rules_add else R.string.rules_edit)) }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.replace("\n", " ").take(RoutingRule.MAX_NAME) }, label = { Text(tr(R.string.rules_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value, { value = it.take(253); error = null }, label = { Text(tr(R.string.rules_value)) }, singleLine = true,
                isError = value.isNotBlank() && normalized == null, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(if (value.isNotBlank() && normalized == null) tr(R.string.rules_invalid) else typeExample(type)) })
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(CustomRouting.DIRECT, CustomRouting.PROXY, CustomRouting.BLOCK).forEachIndexed { i, a ->
                    SegmentedButton(selected = action == a, onClick = { action = a }, shape = SegmentedButtonDefaults.itemShape(i, 3), icon = {},
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primaryContainer, activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = androidx.compose.ui.graphics.Color.Transparent, activeBorderColor = MaterialTheme.colorScheme.outline, inactiveBorderColor = MaterialTheme.colorScheme.outline),
                        modifier = Modifier.pressScale().heightIn(min = 44.dp)) { Text(routeShort(a), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            // Secondary: the match type, one line with a menu.
            var typeMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { typeMenu = true }, modifier = Modifier.pressScale(), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text(tr(R.string.rules_match, typeLabel(type)), style = MaterialTheme.typography.bodyMedium)
                    Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    CustomRouting.types.forEach { t ->
                        DropdownMenuItem(text = { Text(typeLabel(t)) }, onClick = { chosenType = t; typeMenu = false; error = null },
                            leadingIcon = { RadioButton(selected = t == type, onClick = null) })
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = normalized != null, onClick = { normalized?.let { error = submit(RoutingRule(type, it, action, initial?.enabled ?: true, RoutingRule.cleanName(name))) } }, modifier = Modifier.pressScale(enabled = normalized != null)) { Text(tr(R.string.sub_save)) } },
        dismissButton = { TextButton(onClick = close, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_cancel_32)) } })
}
private fun routeShort(action: String) = when (action) {
    CustomRouting.DIRECT -> tr(R.string.rules_route_direct); CustomRouting.BLOCK -> tr(R.string.rules_route_block); else -> tr(R.string.rules_route_vpn)
}

private fun importSummary(r: RuleText.Result): String = listOfNotNull(
    tr(R.string.rules_import_added, r.added),
    r.invalid.takeIf { it > 0 }?.let { tr(R.string.rules_import_invalid, it, r.invalidLines.take(10).joinToString(", ") + if (it > 10) "…" else "") },
    r.duplicates.takeIf { it > 0 }?.let { tr(R.string.rules_import_duplicates, it) },
    r.overLimit.takeIf { it > 0 }?.let { tr(R.string.rules_import_over_limit, it) }).joinToString(" · ")

/** Import from a file or the clipboard: lines without a type or action use the defaults chosen here (same radio lists as the rule dialog). */
@Composable private fun RulesImportDialog(text: String, rules: List<RoutingRule>, close: () -> Unit, apply: (RuleText.Result) -> Unit) {
    var type by remember { mutableStateOf(CustomRouting.SUFFIX) }
    var action by remember { mutableStateOf(CustomRouting.DIRECT) }
    val result = remember(text, rules, type, action) { RuleText.import(text, rules, type, action) }
    AlertDialog(onDismissRequest = close, title = { Text(tr(R.string.rules_import_title)) }, text = {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr(R.string.rules_import_hint), style = MaterialTheme.typography.bodySmall)
            Text(tr(R.string.rules_import_default_type), style = MaterialTheme.typography.titleSmall)
            CustomRouting.types.forEach { t ->
                Row(Modifier.pressScale().fillMaxWidth().clickable { type = t }, verticalAlignment = Alignment.CenterVertically) { RadioButton(type == t, { type = t }); Text(typeLabel(t)) }
            }
            Text(tr(R.string.rules_import_default_action), style = MaterialTheme.typography.titleSmall)
            CustomRouting.actions.forEach { a ->
                Row(Modifier.pressScale().fillMaxWidth().clickable { action = a }, verticalAlignment = Alignment.CenterVertically) { RadioButton(action == a, { action = a }); Text(actionLabel(a)) }
            }
            Text(importSummary(result), style = MaterialTheme.typography.bodyMedium)
        }
    }, confirmButton = { TextButton(enabled = result.added > 0, onClick = { apply(result) }, modifier = Modifier.pressScale(enabled = result.added > 0)) { Text(tr(R.string.rules_import_apply, result.added)) } },
        dismissButton = { TextButton(onClick = close, modifier = Modifier.pressScale()) { Text(tr(R.string.msg_cancel_32)) } })
}

/**
 * One rule in a compact Material 3 row: ↑/↓ stacked together at the start (user rules only; a default rule keeps the same indent), the
 * value and "n. type → action", then a delete button (user rules only) and the rule's switch. A rule that is off is dimmed.
 */
@Composable private fun RuleRow(title: String, caption: String, on: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit,
    up: (() -> Unit)? = null, down: (() -> Unit)? = null, delete: (() -> Unit)? = null) {
    val user = delete != null
    Card(Modifier.pressScale(enabled = enabled).fillMaxWidth().clickable(enabled = enabled, onClick = onClick), colors = ndCardColors()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 2.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (user) Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ArrowButton(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp, tr(R.string.rules_up), enabled && up != null) { up?.invoke() }
                ArrowButton(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, tr(R.string.rules_down), enabled && down != null) { down?.invoke() }
            } else Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp).alpha(if (on) 1f else 0.55f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (delete != null) IconButton(onClick = delete, enabled = enabled, modifier = Modifier.pressScale(enabled = enabled).size(40.dp)) {
                Icon(androidx.compose.material.icons.Icons.Outlined.Delete, contentDescription = tr(R.string.rules_delete), modifier = Modifier.size(22.dp))
            }
            MothSwitch(checked = on, onCheckedChange = onToggle, enabled = enabled, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Half-height ↑ or ↓ (40 × 30 dp): the pair stacks into one 60 dp row. */
@Composable private fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(width = 40.dp, height = 30.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
    }
}
