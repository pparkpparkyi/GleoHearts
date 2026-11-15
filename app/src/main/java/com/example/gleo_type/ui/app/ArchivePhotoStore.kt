package com.example.gleo_type.ui.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

data class ArchiveMediaDraft(val attachments:List<ArchiveAttachment>,val coverAttachmentId:String?,val note:String) {
 init {
  require(attachments.size<=5 && attachments.map{it.id}.distinct().size==attachments.size)
  require(attachments.all{it.kind=="photo"})
  require(coverAttachmentId==null || attachments.any{it.id==coverAttachmentId})
  require(note.length<=2000)
 }
}

/** Selected URI only. No gallery scan, EXIF extraction, network or original-file deletion. IO thread required. */
class ArchivePhotoStore(private val context:Context,private val directory:File) {
 companion object { const val MAX_INPUT_BYTES=15*1024*1024;const val MAX_PHOTOS=5 }
 fun importSelected(uri:Uri):ArchiveAttachment {
  require(uri.scheme=="content"){"Only a user-selected content URI is supported"}
  val type=context.contentResolver.getType(uri)
  require(type in setOf("image/jpeg","image/png","image/webp","image/heic","image/heif")){"Unsupported image type"}
  val bytes=context.contentResolver.openInputStream(uri)?.use{input->
   val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(32768);var count=0
   while(true){val n=input.read(buffer);if(n<0)break;count+=n;require(count<=MAX_INPUT_BYTES);out.write(buffer,0,n)}
   out.toByteArray()
  } ?: error("Selected image unavailable")
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
  require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 && bounds.outWidth.toLong()*bounds.outHeight<=40_000_000)
  val options=BitmapFactory.Options().apply{inSampleSize=1;while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>1600)inSampleSize*=2}
  val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("Invalid selected image")
  // Read only display orientation, never GPS/date or other EXIF metadata.
  val orientation=runCatching{android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
  val matrix=android.graphics.Matrix().apply{when(orientation){
   2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
   5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f)
  }}
  val bitmap=if(matrix.isIdentity)decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also{if(it!==decoded)decoded.recycle()}
  try {
   check(directory.isDirectory||directory.mkdirs())
   require(directory.listFiles().orEmpty().filter{it.isFile}.sumOf{it.length()}<=124L*1024*1024){"Local photo storage limit reached"}
   val id=UUID.randomUUID().toString();val file=File(directory,"$id.jpg")
   require(file.canonicalFile.parentFile==directory.canonicalFile && file.createNewFile())
   file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))};require(file.length() in 1..4L*1024*1024)
   // Re-encoding strips EXIF/location and avoids depending on transient Photo Picker URI permissions.
   return ArchiveAttachment(id,"photo",Uri.fromFile(file).toString(),"선택한 사진","image/jpeg")
  } finally {bitmap.recycle()}
 }
}

/** Presentation port, shared by home/calendar/Hearts. Does not open arbitrary paths or remote URLs. */
object ArchiveCover {
 fun selected(pin:Pin):ArchiveAttachment? = pin.attachments.firstOrNull{it.kind=="photo"&&it.id==pin.coverAttachmentId}
  ?: pin.attachments.firstOrNull{it.kind=="photo"}
 fun decode(context:Context,attachment:ArchiveAttachment):Bitmap?=runCatching {
  val uri=Uri.parse(attachment.localReference);require(uri.scheme in setOf("file","content"))
  fun open()=if(uri.scheme=="content")context.contentResolver.openInputStream(uri) else {
   val f=File(uri.path!!).canonicalFile
   val roots=listOf(context.filesDir.canonicalFile,context.cacheDir.canonicalFile,context.noBackupFilesDir.canonicalFile)
   require(roots.any{f.toPath().startsWith(it.toPath())});f.inputStream()
  }
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};open()?.use{BitmapFactory.decodeStream(it,null,bounds)}
  require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384)
  val options=BitmapFactory.Options().apply{inSampleSize=1;while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>768)inSampleSize*=2}
  open()?.use{BitmapFactory.decodeStream(it,null,options)}
 }.getOrNull()
}
