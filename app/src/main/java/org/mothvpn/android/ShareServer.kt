package org.mothvpn.android

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.mothvpn.core.Profile

/** QR code with the server's share link (contains the server key: shown only on request, never logged). */
fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable fun ShareServerDialog(profile: Profile, close: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val link = remember(profile) { org.mothvpn.core.ShareLinks.of(profile).orEmpty() } // generated for servers imported without a link (Clash/Xray JSON)
    val bitmap = remember(link) { link.takeIf { it.isNotEmpty() }?.let { runCatching { qrBitmap(it) }.onFailure { e -> Logger.w("Share", "QR encoding failed", e) }.getOrNull() } }
    AlertDialog(onDismissRequest = close, title = { Text(tr(R.string.server_share)) }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (bitmap == null) Text(tr(R.string.share_unavailable))
            else {
                Image(bitmap.asImageBitmap(), contentDescription = profile.name, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                Text(tr(R.string.share_warning), style = MaterialTheme.typography.bodySmall)
                if (copied) Text(tr(R.string.share_copied), style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = close, modifier = Modifier.pressScale()) { Text(tr(R.string.share_close)) } },
        dismissButton = { if (bitmap != null) TextButton(onClick = { clipboard.setText(AnnotatedString(link)); copied = true }, modifier = Modifier.pressScale()) { Text(tr(R.string.share_copy)) } })
}
