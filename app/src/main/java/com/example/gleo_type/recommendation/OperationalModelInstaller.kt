package com.example.gleo_type.recommendation

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class ApprovedModel(val modelVersion:String,val manifestSize:Int,val manifestSha256:String,
    val embeddingSize:Int,val embeddingSha256:String) {
    init {require(modelVersion.matches(Regex("[a-zA-Z0-9_.-]{1,96}")))
        require(manifestSize in 1..1_000_000 && embeddingSize in 16..2_097_152)
        require(listOf(manifestSha256,embeddingSha256).all{it.matches(Regex("[0-9a-f]{64}"))})}
}
enum class ModelInstallResult { INSTALLED, ALREADY_MATCHING, EXISTING_MODEL_CONFLICT }

/** Explicitly approved, pinned two-file install. No MP3/profile copying, replacement or auto onboarding.
 * IO-only. A crash may leave a private staging folder; it cannot expose a half-installed live model. */
class OperationalModelInstaller(private val filesDir:File) {
    companion object {
        private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun install(approved:ApprovedModel,manifest:()->InputStream,embeddings:()->InputStream):ModelInstallResult =
        synchronized(locks.computeIfAbsent(filesDir.canonicalPath){Any()}) {
        fun read(open:()->InputStream,size:Int,hash:String):ByteArray {
            val data=open().use{input->
                val buffer=ByteArray(size+1);var count=0
                while(count<buffer.size){val n=input.read(buffer,count,buffer.size-count);if(n<0)break
                    if(n==0){val byte=input.read();if(byte<0)break;buffer[count++]=byte.toByte()}else count+=n}
                buffer.copyOf(count)
            }
            require(data.size==size && sha(data)==hash){"Approved model size/hash mismatch"};return data
        }
        val metadata=read(manifest,approved.manifestSize,approved.manifestSha256)
        val binary=read(embeddings,approved.embeddingSize,approved.embeddingSha256)
        val j=JSONObject(metadata.toString(Charsets.UTF_8))
        require(j.getInt("formatVersion")==1 && j.getInt("rows")==500 && j.getInt("dimensions")==512)
        require(j.getString("modelVersion")==approved.modelVersion && j.getString("sha256")==approved.embeddingSha256)
        val destination=File(filesDir,"models/clap500")
        if(destination.exists()) {
            val m=File(destination,"manifest.json");val b=File(destination,"embeddings.bin")
            return@synchronized if(m.isFile&&b.isFile&&m.length()==metadata.size.toLong()&&b.length()==binary.size.toLong()&&
                sha(m.readBytes())==approved.manifestSha256&&sha(b.readBytes())==approved.embeddingSha256)
                ModelInstallResult.ALREADY_MATCHING else ModelInstallResult.EXISTING_MODEL_CONFLICT
        }
        val stage=File(filesDir,"model-install-${UUID.randomUUID()}");check(stage.mkdir())
        val model=File(stage,"models/clap500");check(model.mkdirs())
        try {
            fun write(name:String,data:ByteArray) {FileOutputStream(File(model,name)).use{it.write(data);it.fd.sync()}}
            write("manifest.json",metadata);write("embeddings.bin",binary)
            // Same production loader validates canonical IDs, row order, float32/finite vectors and mappings.
            require(RecommendationModelAdapter(stage,"MODEL_INSTALL_VALIDATION_ONLY").load()){"Invalid production model"}
            check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            check(!destination.exists() && model.renameTo(destination)){"Concurrent model installation"}
            ModelInstallResult.INSTALLED
        } finally {
            // Only known, newly-created staging files. Never recursively remove user/model directories.
            listOf("manifest.json","embeddings.bin").forEach{File(model,it).delete()}
            model.delete();File(stage,"models").delete();stage.delete()
        }
    }
}
