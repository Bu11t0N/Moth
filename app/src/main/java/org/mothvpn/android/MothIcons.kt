package org.mothvpn.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI
import org.mothvpn.core.VersionLabel
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer

@Composable fun MothIcon(name: String, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val s = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(w*x,h*y), Offset(w*x2,h*y2), s.width, StrokeCap.Round)
        when (name) {
            "heart" -> { val p = Path().apply { moveTo(w*.5f,h*.86f); cubicTo(w*.1f,h*.58f,w*.02f,h*.32f,w*.2f,h*.18f); cubicTo(w*.34f,h*.07f,w*.47f,h*.16f,w*.5f,h*.28f); cubicTo(w*.53f,h*.16f,w*.66f,h*.07f,w*.8f,h*.18f); cubicTo(w*.98f,h*.32f,w*.9f,h*.58f,w*.5f,h*.86f); close() }; drawPath(p, color) }
            "update" -> { line(.5f,.1f,.5f,.68f); line(.5f,.68f,.25f,.43f); line(.5f,.68f,.75f,.43f); line(.18f,.9f,.82f,.9f) }
            "eye" -> { val p = Path().apply { moveTo(w*.06f,h*.5f); quadraticBezierTo(w*.5f,h*.05f,w*.94f,h*.5f); quadraticBezierTo(w*.5f,h*.95f,w*.06f,h*.5f) }; drawPath(p,color,style=s); drawCircle(color,w*.14f,Offset(w*.5f,h*.5f),style=s) }
            "eye_off" -> { val p = Path().apply { moveTo(w*.06f,h*.5f); quadraticBezierTo(w*.5f,h*.05f,w*.94f,h*.5f); quadraticBezierTo(w*.5f,h*.95f,w*.06f,h*.5f) }; drawPath(p,color,style=s); line(.15f,.9f,.85f,.1f) }
            else -> for(i in 0..2) { val y=.23f+i*.27f; line(.18f,y,.82f,y) }
        }
    }
}

/**
 * Telegram's paper plane (the plane from the Simple Icons "telegram" glyph, CC0, without the circle), tilted as in the official
 * logo. Drawn in the content colour, so it stays monochrome like the other link tiles.
 */
val TelegramPlane: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("TelegramPlane", 24.dp, 24.dp, 24f, 24f).apply {
        addGroup(pivotX = 11.25f, pivotY = 12.67f, scaleX = 1.5f, scaleY = 1.5f, translationX = 0.75f, translationY = -0.67f)
        addPath(androidx.compose.ui.graphics.vector.addPathNodes(
            "M16.906 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.962 6.502-1.36 8.627" +
            "-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91" +
            ".177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345" +
            "-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663" +
            " 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z"),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black))
        clearGroup()
    }.build()
}
