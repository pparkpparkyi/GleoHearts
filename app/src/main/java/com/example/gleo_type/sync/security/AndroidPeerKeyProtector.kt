package com.example.gleo_type.sync.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No software fallback and no replacement key on decrypt failure. Device test still required. */
class AndroidPeerKeyProtector:PeerKeyProtector {
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    private fun validate(alias:String){require(alias.matches(Regex("gleo\\.review\\.peer\\.[a-f0-9-]{36}")))}
    @Synchronized override fun protect(alias:String,aad:ByteArray,secret:ByteArray):ByteArray {
        validate(alias);require(secret.size==32);check(!store().containsAlias(alias)){"Enrollment alias already exists"}
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        var created=false
        try{
            val key=generator.generateKey();created=true
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad)
            val ciphertext=cipher.doFinal(secret);check(cipher.iv.size==12)
            return cipher.iv+ciphertext
        }catch(e:Exception){if(created)runCatching{store().deleteEntry(alias)};throw e}
    }
    @Synchronized override fun unprotect(alias:String,aad:ByteArray,sealed:ByteArray):ByteArray {
        validate(alias);require(sealed.size==60)
        val key=store().getKey(alias,null) as? SecretKey ?: error("Peer key unavailable; explicit re-pairing required")
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,sealed.copyOfRange(0,12)));cipher.updateAAD(aad)
        return cipher.doFinal(sealed.copyOfRange(12,sealed.size))
    }
    @Synchronized override fun revoke(alias:String){validate(alias);store().deleteEntry(alias)}
    companion object {
        /** Explicit caller-owned identity; never invent or migrate a localUserId. */
        fun vault(context:Context,localUser:String,sourceDevice:String)=PeerKeyVault(
            File(context.noBackupFilesDir,"review-peer-v1/keys.jsonl"),localUser,sourceDevice,AndroidPeerKeyProtector())
    }
}
