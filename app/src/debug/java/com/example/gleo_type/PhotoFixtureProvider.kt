package com.example.gleo_type

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor

/** Non-exported debug-only synthetic image provider. Never reads the user's gallery/files. */
class PhotoFixtureProvider:ContentProvider() {
 override fun onCreate()=true
 override fun getType(uri:Uri)="image/png"
 override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
  require(mode=="r"&&uri.path in setOf("/red","/blue","/corrupt"))
  val pipes=ParcelFileDescriptor.createPipe()
  Thread {
   ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use{out->
    if(uri.path=="/corrupt")out.write(byteArrayOf(1,2,3))
    else {val image=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888);image.eraseColor(if(uri.path=="/red")Color.MAGENTA else Color.BLUE)
     image.compress(Bitmap.CompressFormat.PNG,100,out);image.recycle()}
   }
  }.start()
  return pipes[0]
 }
 override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
 override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
 override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
 override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
}
