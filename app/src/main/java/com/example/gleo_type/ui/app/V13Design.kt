package com.example.gleo_type.ui.app

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.caverock.androidsvg.SVG

/** Read from V13 356:25767 / 355:25808. App styling, not replacement OS chrome. */
internal object V13Design {
 val foreground=Color(0xFFFFF9FF)
 val secondary=Color(0xFFC6BBD4)
 val accent=Color(0xFFCFB3F6)
 val glass=Color(0xFFFFF6FF).copy(alpha=.13f)
 val stroke=Color(0xFFE6D2FF).copy(alpha=.20f)
 val background=Brush.horizontalGradient(0f to Color(0xFF090D1C),.5f to Color(0xFF18152D),1f to Color(0xFF0B1022))
 val carBackground=Brush.horizontalGradient(0f to Color(0xFF070914),.45f to Color(0xFF171128),1f to Color(0xFF0B1026))
 fun colors()=darkColorScheme(primary=accent,onPrimary=Color(0xFF171128),background=Color(0xFF090D1C),
  surface=Color(0xFF242236),surfaceVariant=glass,onSurface=foreground,onBackground=foreground,onSurfaceVariant=secondary,
  primaryContainer=Color.White.copy(alpha=.18f),onPrimaryContainer=foreground,secondaryContainer=glass,
  onSecondaryContainer=foreground,outline=Color(0xFF8E819F),outlineVariant=stroke,error=Color(0xFFFFB4AB))
}

/** Original vector root dimensions are retained; wrappers only position the rendered SVG. */
@Composable internal fun V13Svg(name:String,modifier:Modifier=Modifier) {
 SubmissionVector(name,modifier)
}
@Composable internal fun V13Background(car:Boolean=false,content:@Composable BoxScope.()->Unit) {
 CompositionLocalProvider(LocalContentColor provides V13Design.foreground) {
 Box(Modifier.fillMaxSize().background(if(car)V13Design.carBackground else V13Design.background)) {
  if(car){
   V13Svg("car-violet",Modifier.align(Alignment.TopEnd).offset(x=47.dp,y=(-74).dp).size(450.285.dp,593.64.dp).blur(40.dp))
   V13Svg("car-peach",Modifier.align(Alignment.BottomStart).offset(x=(-140).dp,y=117.dp).size(474.944.dp,368.54.dp).blur(35.dp))
  } else V13Svg("mobile-glow",Modifier.align(Alignment.TopEnd).offset(x=184.dp,y=(-135).dp).size(470.dp,545.dp).blur(45.dp))
  content()
 }
 }
}
@Composable internal fun V13BottomBar(tab:Int,onTab:(Int)->Unit) {
 Box(Modifier.fillMaxWidth().navigationBarsPadding()) {
  com.example.gleo_type.ui.gleohearts.MobileTabBar(V13LiveProjection.tab(tab)){onTab(V13LiveProjection.index(it))}
 }
}
