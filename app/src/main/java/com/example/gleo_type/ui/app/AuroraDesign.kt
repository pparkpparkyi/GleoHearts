package com.example.gleo_type.ui.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/** Project palette, not Hyundai/Gleo official brand values. */
object MidnightAurora {
    const val background = 0xFF0B1020
    const val surface = 0xFF17213B
    const val blue = 0xFF334FC7
    const val violet = 0xFF7669E8
    const val lavender = 0xFFA996FF
    const val text = 0xFFF5F6FF
    const val secondary = 0xFFB9C2EB
    const val cardEnd = 0xFF403782
    const val lightBackground = 0xFFF4F5FC
    const val lightSurface = 0xFFFFFFFF
    const val lightVariant = 0xFFE5E9FA
    const val lightSecondary = 0xFF445279
    fun contrast(a: Long, b: Long): Double {
        fun luminance(c: Long): Double {
            val v = listOf(16, 8, 0).map { shift ->
                val s = ((c shr shift) and 255).toDouble() / 255
                if (s <= .04045) s / 12.92 else ((s + .055) / 1.055).pow(2.4)
            }
            return v[0] * .2126 + v[1] * .7152 + v[2] * .0722
        }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}

data class PhysicalUiMetrics(val targetDp: Float = 92f, val gapDp: Float = 32f,
    val captionSp: Float = 24f, val primarySp: Float = 40f, val reportedDpiValid: Boolean = false) {
    companion object {
        /** Android's reported x/y dpi, not certification of an emulator host monitor's mm. */
        fun from(density: Float, scaledDensity: Float, xdpi: Float, ydpi: Float): PhysicalUiMetrics {
            val valid = density.isFinite() && density > 0 && scaledDensity.isFinite() && scaledDensity > 0 &&
                xdpi.isFinite() && ydpi.isFinite() && xdpi in 50f..1000f && ydpi in 50f..1000f
            if (!valid) return PhysicalUiMetrics()
            val dpi = max(xdpi, ydpi)
            return PhysicalUiMetrics(max(92f, ceil(12.5f * dpi / 25.4f / density)),
                max(32f, ceil(4f * dpi / 25.4f / density)),
                max(24f, ceil(3f * dpi / 25.4f / scaledDensity * 1.25f)),
                max(40f, ceil(5f * dpi / 25.4f / scaledDensity * 1.2f)), true)
        }
    }
}
val LocalHandheldUi = staticCompositionLocalOf { false }
val LocalPhysicalUi = staticCompositionLocalOf { PhysicalUiMetrics() }
val LocalDrivingUx = staticCompositionLocalOf { DrivingUx() }

@Composable fun CarButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val target = LocalPhysicalUi.current.targetDp.dp
    Button(onClick, modifier.sizeIn(minWidth = target, minHeight = target), enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if(LocalHandheldUi.current)16.dp else 28.dp, vertical = if(LocalHandheldUi.current)10.dp else 16.dp), content = content)
}
@Composable fun CarOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val target = LocalPhysicalUi.current.targetDp.dp
    OutlinedButton(onClick, modifier.sizeIn(minWidth = target, minHeight = target), enabled = enabled,
        border = androidx.compose.foundation.BorderStroke(2.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface.copy(alpha = .25f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if(LocalHandheldUi.current)16.dp else 28.dp, vertical = if(LocalHandheldUi.current)10.dp else 16.dp), content = content)
}
@Composable fun CarTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(), content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val target = LocalPhysicalUi.current.targetDp.dp
    TextButton(onClick, modifier.sizeIn(minWidth = target, minHeight = target), enabled = enabled,
        colors = colors, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if(LocalHandheldUi.current)8.dp else 20.dp, vertical = if(LocalHandheldUi.current)8.dp else 16.dp), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CarDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit = {}) {
    val gap = LocalPhysicalUi.current.gapDp.dp
    BasicAlertDialog(onDismissRequest, modifier = Modifier.widthIn(max = 1000.dp).fillMaxWidth(.88f),
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .85f).padding(32.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                title()
                Box(Modifier.weight(1f, fill = false)) { text() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, androidx.compose.ui.Alignment.End),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { dismissButton(); confirmButton() }
            }
        }
    }
}

/** MOBILE V12 light tokens; dark is an accessible app theme, not a verified Figma dark frame. */
fun handheldColors(dark:Boolean):ColorScheme {
    if(!dark)return lightColorScheme(primary=Color(0xFF002C5F),onPrimary=Color.White,
        background=Color(0xFFF6F8FA),surface=Color.White,surfaceVariant=Color(0xFFF0F3F9),
        onBackground=Color(0xFF203448),onSurface=Color(0xFF203448),onSurfaceVariant=Color(0xFF596D82),
        primaryContainer=Color(0xFFE9F1FC),onPrimaryContainer=Color(0xFF002C5F),outline=Color(0xFF667988),
        secondaryContainer=Color(0xFFE9F1FC),onSecondaryContainer=Color(0xFF002C5F),outlineVariant=Color(0xFFE4EBF1))
    return V13Design.colors()
}

fun auroraColors(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(MidnightAurora.lavender), onPrimary = Color(MidnightAurora.background),
    secondary = Color(MidnightAurora.secondary),
    primaryContainer = Color(0xFF2B3763), onPrimaryContainer = Color(MidnightAurora.text),
    secondaryContainer = Color(0xFF222E50), onSecondaryContainer = Color(MidnightAurora.text),
    background = Color(MidnightAurora.background), surface = Color(MidnightAurora.surface),
    surfaceVariant = Color(0xFF222E50), onBackground = Color(MidnightAurora.text),
    onSurface = Color(MidnightAurora.text), onSurfaceVariant = Color(MidnightAurora.secondary),
    surfaceContainer = Color(MidnightAurora.surface), surfaceContainerHigh = Color(0xFF222E50),
    surfaceContainerHighest = Color(0xFF222E50), outlineVariant = Color(MidnightAurora.secondary),
    outline = Color(MidnightAurora.secondary), error = Color(0xFFFFB4AB))
else lightColorScheme(primary = Color(MidnightAurora.blue), onPrimary = Color.White,
    background = Color(MidnightAurora.lightBackground), surface = Color(MidnightAurora.lightSurface),
    surfaceVariant = Color(MidnightAurora.lightVariant), onBackground = Color(MidnightAurora.background),
    primaryContainer = Color(MidnightAurora.lightVariant), onPrimaryContainer = Color(MidnightAurora.background),
    secondaryContainer = Color(MidnightAurora.lightVariant), onSecondaryContainer = Color(MidnightAurora.background),
    surfaceContainer = Color(MidnightAurora.lightSurface), surfaceContainerHigh = Color(MidnightAurora.lightVariant),
    surfaceContainerHighest = Color(MidnightAurora.lightVariant), outlineVariant = Color(MidnightAurora.lightSecondary),
    onSurface = Color(MidnightAurora.background), onSurfaceVariant = Color(MidnightAurora.lightSecondary),
    outline = Color(MidnightAurora.lightSecondary), error = Color(0xFFA51629))

@Composable fun auroraTypography(): Typography {
    val m = LocalPhysicalUi.current
    if(LocalHandheldUi.current) return Typography(
        headlineLarge=TextStyle(fontSize=28.sp,lineHeight=35.sp,fontWeight=FontWeight.Bold),
        headlineMedium=TextStyle(fontSize=24.sp,lineHeight=30.sp,fontWeight=FontWeight.Bold),
        titleLarge=TextStyle(fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold),
        titleMedium=TextStyle(fontSize=18.sp,lineHeight=24.sp),bodyLarge=TextStyle(fontSize=16.sp,lineHeight=22.sp),
        bodyMedium=TextStyle(fontSize=14.sp,lineHeight=20.sp),bodySmall=TextStyle(fontSize=12.sp,lineHeight=16.sp),
        labelLarge=TextStyle(fontSize=14.sp,lineHeight=20.sp))
    fun t(size: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(fontSize = size.sp,
        lineHeight = (size * 1.25f).sp, fontWeight = weight)
    return Typography(headlineLarge = t(48f, FontWeight.Bold), headlineMedium = t(m.primarySp, FontWeight.Bold),
        titleLarge = t(36f, FontWeight.Bold), titleMedium = t(30f, FontWeight.SemiBold),
        titleSmall = t(28f, FontWeight.SemiBold), bodyLarge = t(28f), bodyMedium = t(26f),
        bodySmall = t(m.captionSp), labelLarge = t(26f, FontWeight.SemiBold),
        labelMedium = t(m.captionSp, FontWeight.Medium), labelSmall = t(m.captionSp))
}

/** All app copy has a common readable floor; no tiny legacy 11/12sp labels. */
@Composable fun AppText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null,
    style: TextStyle = MaterialTheme.typography.bodyLarge, maxLines: Int = 3,
    overflow: TextOverflow = TextOverflow.Ellipsis, lineHeight: TextUnit = TextUnit.Unspecified) {
    val floor = LocalPhysicalUi.current.captionSp
    val requested = if (fontSize.isSp) fontSize.value else if (style.fontSize.isSp) style.fontSize.value else floor
    val size = if(LocalHandheldUi.current) max(requested.coerceAtMost(28f),floor) else max(requested, floor)
    Text(text, modifier, color = color, fontSize = size.sp, fontWeight = fontWeight,
        style = style, maxLines = maxLines, overflow = overflow,
        lineHeight = max(size * 1.25f, if (lineHeight.isSp) lineHeight.value else 0f).sp)
}

/** Original outlined navigation glyphs drawn on a 48dp / 48-unit grid, not font characters. */
@Composable fun AuroraIcon(index: Int, color: Color) {
    Canvas(Modifier.size(if(LocalHandheldUi.current)24.dp else 48.dp)) {
        val sx = size.width / 48; val sy = size.height / 48
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, Offset(x*sx,y*sy), Offset(a*sx,b*sy), 3*sx)
        when(index) {
            0 -> { line(5f,22f,24f,6f); line(24f,6f,43f,22f); line(10f,20f,10f,42f); line(10f,42f,38f,42f); line(38f,42f,38f,20f); line(20f,42f,20f,29f); line(20f,29f,29f,29f); line(29f,29f,29f,42f) }
            1 -> { line(19f,34f,19f,10f); line(19f,10f,39f,6f); line(39f,6f,39f,29f); drawCircle(color,6*sx,Offset(13*sx,36*sy)); drawCircle(color,6*sx,Offset(33*sx,31*sy)) }
            2 -> { val p=Path().apply { moveTo(7*sx,12*sy);lineTo(41*sx,12*sy);lineTo(41*sx,42*sy);lineTo(7*sx,42*sy);close() };drawPath(p,color,style=Stroke(3*sx));line(7f,22f,41f,22f);line(16f,6f,16f,17f);line(32f,6f,32f,17f) }
            3 -> { drawCircle(color,14*sx,Offset(24*sx,20*sy),style=Stroke(3*sx));line(14f,31f,24f,44f);line(34f,31f,24f,44f);drawCircle(color,4*sx,Offset(24*sx,20*sy)) }
            4 -> { drawCircle(color,8*sx,Offset(24*sx,14*sy),style=Stroke(3*sx));drawArc(color,180f,180f,false,Offset(7*sx,28*sy),androidx.compose.ui.geometry.Size(34*sx,25*sy),style=Stroke(3*sx)) }
            5 -> { line(39f,24f,9f,24f);line(9f,24f,23f,10f);line(9f,24f,23f,38f) }
            6 -> { line(9f,24f,39f,24f);line(39f,24f,25f,10f);line(39f,24f,25f,38f) }
        }
    }
}
