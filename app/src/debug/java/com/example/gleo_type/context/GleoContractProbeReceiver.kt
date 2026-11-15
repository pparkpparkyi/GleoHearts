package com.example.gleo_type.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Non-exported, same-UID instrumentation probe; never an official Gleo endpoint. */
class GleoContractProbeReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val callback=handler ?: return
        val pending=goAsync()
        Thread { try {runCatching{GleoVoiceContract.decode(intent)}.onSuccess(callback).onFailure{failureHandler?.invoke("INVALID_ACTION")}} finally {pending.finish()} }.start()
    }
    companion object {
        @Volatile var handler:((GleoVoiceContract.Action)->Unit)?=null
        @Volatile var failureHandler:((String)->Unit)?=null
    }
}
