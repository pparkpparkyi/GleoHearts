package com.example.gleo_type.ui.app
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
/** Submission-owned geometric fallback. No imported Figma paths or imagery. */
@Composable internal fun SubmissionVector(name:String,modifier:Modifier=Modifier) {
 Canvas(modifier) {
  if(name.contains("glow")||name.contains("violet")||name.contains("peach")) {
   drawRect(Brush.radialGradient(listOf(Color(0x228E6CE7),Color.Transparent),radius=size.maxDimension.coerceAtLeast(1f)))
  } else {
   val c=Color(0xFFD5BEFA);val r=size.minDimension*.32f
   drawCircle(c,r,style=Stroke((size.minDimension*.07f).coerceAtLeast(1f)))
   drawLine(c,Offset(size.width*.35f,size.height*.5f),Offset(size.width*.65f,size.height*.5f),2f)
  }
 }
}
