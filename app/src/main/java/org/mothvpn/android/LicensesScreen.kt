package org.mothvpn.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** License texts packaged from licenses/ as APK assets. */
object Licenses {
    data class Entry(val title: Int, val summary: Int, val asset: String)
    val entries = listOf(
        Entry(R.string.lic_moth, R.string.lic_moth_sum, "GPL-3.0-or-later.txt"),
        Entry(R.string.lic_singbox, R.string.lic_singbox_sum, "sing-box.txt"),
        Entry(R.string.lic_xray, R.string.lic_xray_sum, "Xray-MPL-2.0.txt"),
        Entry(R.string.lic_go_modules, R.string.lic_go_modules_sum, "go-modules.txt"),
        Entry(R.string.lic_android, R.string.lic_android_sum, "Apache-2.0.txt"),
    )
}

@Composable fun LicensesList(open: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(Licenses.entries) { e ->
            Card(Modifier.fillMaxWidth().clickable { open(e.asset) }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(tr(e.title), style = MaterialTheme.typography.titleMedium)
                    Text(tr(e.summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable fun LicenseTextPage(asset: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var lines by remember(asset) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(asset) {
        lines = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open(asset).bufferedReader().use { it.readLines() } }
                .onFailure { Logger.w("Licenses", "cannot open $asset", it) }.getOrDefault(emptyList())
        }
    }
    val l = lines
    if (l == null) Box(modifier) { CircularProgressIndicator(Modifier.padding(24.dp)) }
    else SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(l) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
        }
    }
}
