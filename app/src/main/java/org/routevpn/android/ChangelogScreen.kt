package org.routevpn.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.routevpn.core.Changelog
import org.routevpn.core.ChangelogSection
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object ChangelogRepository {
    private const val BASE = "https://raw.githubusercontent.com/Bu11t0N/Moth/main/"
    // TODO: remove this fallback after PR #5 (release/0.4.2) is merged into main.
    private const val FALLBACK_BASE = "https://raw.githubusercontent.com/Bu11t0N/Moth/release/0.4.2/"
    data class Result(val sections: List<ChangelogSection>?, val fromCache: Boolean)

    fun fileName(russian: Boolean) = if (russian) "CHANGELOG.ru.md" else "CHANGELOG.md"
    private fun download(url: String): String? {
        val c = URL(url).openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = true
            if (c.responseCode == HttpURLConnection.HTTP_NOT_FOUND) { Logger.w("Changelog", "HTTP 404 for ${url.substringAfterLast('/')}"); return null }
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    /** Network first (main, then the release branch on 404); the last successful text is cached and used when the network fails. */
    fun load(russian: Boolean): Result {
        val cache = File(MothApplication.instance.filesDir, "cache/${fileName(russian)}")
        try {
            val text = download(BASE + fileName(russian)) ?: download(FALLBACK_BASE + fileName(russian)) ?: error("not found")
            val sections = Changelog.parse(text)
            check(sections.isNotEmpty()) { "empty changelog" }
            runCatching { cache.parentFile?.mkdirs(); cache.writeText(text) }
            Logger.i("Changelog", "loaded ${sections.size} sections")
            return Result(sections, false)
        } catch (t: Throwable) {
            Logger.w("Changelog", "fetch failed", t)
            val cached = runCatching { if (cache.exists()) Changelog.parse(cache.readText()) else null }.getOrNull()
            return if (!cached.isNullOrEmpty()) Result(cached, true) else Result(null, false)
        }
    }
}

@Composable fun ChangelogPage(modifier: Modifier = Modifier) {
    val russian = AppLanguage.context().resources.configuration.locales[0].language == "ru"
    var version by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<ChangelogRepository.Result?>(null) }
    LaunchedEffect(version, russian) {
        loading = true
        result = withContext(Dispatchers.IO) { ChangelogRepository.load(russian) }
        loading = false
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val sections = result?.sections
        when {
            loading && sections == null -> Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            sections == null -> Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.changelog_error))
                Button(onClick = { version++ }) { Text(tr(R.string.changelog_retry)) }
            }
            else -> {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (result?.fromCache == true) Text(tr(R.string.changelog_offline), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    sections.forEach { section ->
                        item(key = "h-" + section.title) { Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) }
                        section.groups.forEachIndexed { gi, group ->
                            group.title?.let { t -> item(key = "g-${section.title}-$gi") { Text(t, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) } }
                            items(group.items) { Row { Text("•  "); Text(it, style = MaterialTheme.typography.bodyMedium) } }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                if (result?.fromCache == true) TextButton(onClick = { version++ }) { Text(tr(R.string.changelog_retry)) }
            }
        }
    }
}
