package com.example.gleo_type.context

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Names below belong to our schema; only the response action/extra keys come from Pleos documentation. */
object GleoVoiceContract {
    const val REQUEST="REQUEST_MUSIC"
    const val CONTROL="CONTROL_PLAYBACK"
    const val RATE="RATE_MUSIC"
    const val RESPONSE="ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    data class Action(val function: String, val intent: CommandIntent, val text: String?) {
        init {
            val valid=when(function) {
                REQUEST -> setOf(CommandIntent.RECOMMEND_AND_PLAY,CommandIntent.CHANGE_MOOD)
                CONTROL -> setOf(CommandIntent.PLAY,CommandIntent.PAUSE,CommandIntent.NEXT,CommandIntent.PREVIOUS)
                RATE -> setOf(CommandIntent.LIKE,CommandIntent.DISLIKE)
                else -> emptySet()
            }
            require(intent in valid)
            require(if(function==REQUEST)!text.isNullOrBlank() && text.length<=2000 else text==null)
        }
    }
    fun decode(intent: Intent): Action {
        require(intent.action in setOf(REQUEST,CONTROL,RATE)) { "Unknown function" }
        val extras=intent.extras
        val packed=extras?.get("ACTION_PARAM")
        require(packed==null || packed is String)
        val json=if(packed==null)JSONObject() else {
            require((packed as String).length<=8192)
            JSONObject(packed) // An extra quote-wrapped string is not silently repaired.
        }
        val allowed=if(intent.action==REQUEST)setOf("operation","musicRequest") else setOf("operation")
        require(json.keys().asSequence().all { it in allowed })
        extras?.keySet()?.forEach { key ->
            require(key in allowed || key=="ACTION_PARAM") { "Unknown parameter" }
            if(key in allowed) {
                val value=extras.get(key);require(value is String)
                require(!json.has(key) || json.get(key)==value) { "Conflicting documented input formats" }
                json.put(key,value)
            }
        }
        fun string(key:String):String { require(json.has(key) && json.get(key) is String);return json.getString(key).also {require(it.isNotBlank() && it.length<=2000)} }
        val operation=CommandIntent.valueOf(string("operation"))
        val valid=when(intent.action) {
            REQUEST -> setOf(CommandIntent.RECOMMEND_AND_PLAY,CommandIntent.CHANGE_MOOD)
            CONTROL -> setOf(CommandIntent.PLAY,CommandIntent.PAUSE,CommandIntent.NEXT,CommandIntent.PREVIOUS)
            else -> setOf(CommandIntent.LIKE,CommandIntent.DISLIKE)
        }
        require(operation in valid)
        return Action(intent.action!!,operation,if(intent.action==REQUEST)string("musicRequest") else null)
    }
    data class Callback(val functionName:String,val status:String,val message:String,
        val data:List<Map<String,Any?>> = emptyList(),val mode:String="silent") {
        init {
            require(functionName in setOf(REQUEST,CONTROL,RATE) && status in setOf("success","asking","fail"))
            require(mode in setOf("voice","beep","silent"))
            // The guide requires an English message and permits non-English names/content inside it.
            require(message.isNotBlank() && Regex("[A-Za-z]").containsMatchIn(message)) {"An English result message is required"}
        }
        fun json():String=JSONObject().put("functionName",functionName).put("status",status).put("message",message)
            .put("mode",mode).put("data",JSONArray(data).toString()).toString()
        fun intent(packageName:String):Intent {
            require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
            return Intent(RESPONSE).putExtra("packageName",packageName).putExtra("response",json())
        }
    }
}
