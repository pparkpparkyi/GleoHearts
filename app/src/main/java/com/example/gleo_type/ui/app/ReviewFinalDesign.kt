package com.example.gleo_type.ui.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import com.caverock.androidsvg.SVG
import com.example.gleo_type.music.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Review-only Figma 362:25811 tokens. Never writes a theme preference or changes other tabs. */
internal object ReviewFinalDesign {
 val foreground=Color(0xFFFFF9FF)
 val secondary=Color(0xFFC6B8D3)
 val stroke=Color(0xFFE0CAFF).copy(alpha=.21f)
 val glass=Color.White.copy(alpha=.11f)
 val background=Brush.horizontalGradient(0f to Color(0xFF0A0B1C),.55f to Color(0xFF20172F),1f to Color(0xFF0C1225))
 // V13 accessibility adjustment: middle stop darkened so 15sp labels reach >=4.5:1.
 val primary=Brush.horizontalGradient(0f to Color(0xFF7653B5),.55f to Color(0xFF7D60BF),1f to Color(0xFF5A6FC0))
}
@Composable internal fun ReviewFinalTheme(content:@Composable ()->Unit) {
 MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFBCA4E2),onPrimary=ReviewFinalDesign.foreground,
  background=Color(0xFF0A0B1C),surface=Color(0xFF20172F),onSurface=ReviewFinalDesign.foreground,
  onBackground=ReviewFinalDesign.foreground,onSurfaceVariant=ReviewFinalDesign.secondary)) {
   CompositionLocalProvider(LocalContentColor provides ReviewFinalDesign.foreground,content=content)
  }
}
/** Preserve exported SVG root dimensions; assets are not reconstructed from path data. */
@Composable internal fun ReviewFinalSvg(name:String,modifier:Modifier=Modifier) {
 SubmissionVector(name,modifier)
}
@Composable internal fun ReviewFinalBackground(content:@Composable BoxScope.()->Unit) {
 Box(Modifier.fillMaxSize().background(ReviewFinalDesign.background)) {
  // AndroidSVG does not implement feGaussianBlur; preserve its exported geometry and apply
  // the original 31.5 design-unit blur in Compose after density scaling.
  ReviewFinalSvg("glow",Modifier.align(Alignment.TopEnd).offset(x=51.dp,y=(-181).dp).size(361.dp,426.dp).blur(31.5.dp))
  content()
 }
}
/** Only exact canonical IDs from the existing hash-validated registry. No network, alias or audio transfer. */
internal class ReviewArtworkLoader(private val context:android.content.Context,
 private val provider:()->AudioAssetRegistry={AndroidAudioAssetCatalog.load(context)}) {
 private val registry by lazy(provider)
 @Synchronized fun load(id:String):ImageBitmap? = runCatching {
  val r=registry.resolve(id,rehash=false)
  if(r.status!=AudioAssetRegistry.Status.AVAILABLE)return null
  val retriever=MediaMetadataRetriever()
  val bytes=try {retriever.setDataSource(r.file!!.absolutePath);retriever.embeddedPicture}finally{retriever.release()}
  if(bytes==null || bytes.size>2_097_152)return null
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
  if(bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192)return null
  val options=BitmapFactory.Options().apply{inSampleSize=(maxOf(bounds.outWidth,bounds.outHeight)/512).coerceAtLeast(1)}
  BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.asImageBitmap()
 }.getOrNull()
}
@Composable internal fun ReviewFinalArtwork(id:String,loader:ReviewArtworkLoader,modifier:Modifier,observedArtwork:ByteArray?=null) {
 var bitmap by remember(id,loader){mutableStateOf<ImageBitmap?>(null)}
 var loading by remember(id,loader){mutableStateOf(true)}
 LaunchedEffect(id,loader,observedArtwork){loading=true;bitmap=withContext(Dispatchers.IO){
  val bytes=observedArtwork?.takeIf{it.size<=2_097_152}
  val observed=bytes?.let{runCatching{
   val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(it,0,it.size,bounds)
   require(bounds.outWidth in 1..8192&&bounds.outHeight in 1..8192)
   bounds.inJustDecodeBounds=false;bounds.inSampleSize=1
   while(maxOf(bounds.outWidth,bounds.outHeight)/bounds.inSampleSize>512)bounds.inSampleSize*=2
   BitmapFactory.decodeByteArray(it,0,it.size,bounds)?.asImageBitmap()
  }.getOrNull()}
  observed ?: loader.load(id)
 };loading=false}
 Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF30213D),Color(0xFF100E1C)))),contentAlignment=Alignment.Center) {
  if(bitmap!=null)androidx.compose.foundation.Image(bitmap!!,"해당 곡의 내장 앨범 이미지",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
  else Text(if(loading)"이미지 확인 중…" else "앨범 이미지 없음",color=ReviewFinalDesign.secondary,style=MaterialTheme.typography.labelSmall)
 }
}

@Composable internal fun ReviewFinalAction(label:String,enabled:Boolean,tag:String,modifier:Modifier=Modifier,primary:Boolean=false,onClick:()->Unit) {
 Box(modifier.heightIn(min=52.dp).clip(RoundedCornerShape(15.dp))
  .background(if(primary)ReviewFinalDesign.primary else androidx.compose.ui.graphics.SolidColor(ReviewFinalDesign.glass))
  .border(1.dp,ReviewFinalDesign.stroke,RoundedCornerShape(15.dp)).testTag(tag)
  .semantics{if(!enabled)disabled()}.clickable(enabled=enabled,onClick=onClick),contentAlignment=Alignment.Center) {
  Text(label,Modifier.padding(horizontal=8.dp,vertical=12.dp),fontSize=15.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,
   color=ReviewFinalDesign.foreground.copy(alpha=if(enabled)1f else .5f))
 }
}
@Composable internal fun ReviewFinalBottomBar(tab:Int,onTab:(Int)->Unit) {
 Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=7.dp,vertical=2.dp)) {
  Row(Modifier.fillMaxWidth().background(ReviewFinalDesign.glass,RoundedCornerShape(23.dp)).border(1.dp,ReviewFinalDesign.stroke,RoundedCornerShape(23.dp)).padding(horizontal=3.dp,vertical=4.dp)) {
   val names=listOf("home","music","calendar","hearts","my")
   listOf("홈","음악","캘린더","Hearts","마이").forEachIndexed{i,label ->
    Column(Modifier.weight(1f).heightIn(min=69.dp).testTag("nav_$i").semantics{selected=tab==i}.clickable{onTab(i)},
     horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp,Alignment.CenterVertically)) {
      Box(Modifier.size(47.dp,39.dp).background(if(tab==i)Color.White.copy(alpha=.18f) else Color.Transparent,RoundedCornerShape(17.dp)),contentAlignment=Alignment.Center){ReviewFinalSvg(names[i],Modifier.size(23.dp))}
      Text(label,fontSize=10.sp,color=if(tab==i)ReviewFinalDesign.foreground else Color(0xFFA59CB5),maxLines=2)
    }
   }
  }
 }
}
