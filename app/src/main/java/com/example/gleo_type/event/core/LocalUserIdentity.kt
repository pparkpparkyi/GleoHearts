package com.example.gleo_type.event.core

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Installation-local pseudonym, never an account/person/device hardware identifier. */
class LocalUserIdentity(directory: File) {
    val id: String
    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create local identity directory" }
        RandomAccessFile(File(directory, "local-profile-id"), "rw").use { file ->
            file.channel.lock().use {
                if (file.length() == 0L) {
                    val created = "local_" + UUID.randomUUID().toString()
                    file.write(created.toByteArray(Charsets.UTF_8))
                    file.fd.sync()
                }
                file.seek(0)
                check(file.length() <= 100) { "Invalid local identity length" }
                val bytes = ByteArray(file.length().toInt())
                file.readFully(bytes)
                id = bytes.toString(Charsets.UTF_8)
                check(id.matches(Regex("local_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
                    "Invalid local identity; refusing to silently change profile"
                }
            }
        }
    }
}
