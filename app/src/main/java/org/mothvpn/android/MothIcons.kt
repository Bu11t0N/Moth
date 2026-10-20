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
