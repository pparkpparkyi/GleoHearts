package com.example.gleo_type.context

/** Finite local semantic rules and curated English descriptions. Not an LLM, translator, ASR, or CLAP. */
data class KoreanMusicIntent(val intent: CommandIntent?, val moodKey: String?, val english: String?,
    val claims: Map<String,String>, val status: String, val version: String = "ko_music_rules_v1_1")
object KoreanMusicIntentParser {
    val descriptions = mapOf("CALM" to "Calm, soft and relaxing music.",
        "DREAMY" to "Dreamy, atmospheric and ethereal music.",
        "CALM_DREAMY" to "Calm, dreamy, atmospheric and relaxing music.",
        "ENERGETIC" to "Energetic, upbeat and lively music.",
        "RAIN_COMMUTE" to "Music for a rainy commute home.")
    fun parse(text: String): KoreanMusicIntent {
        if(text.length !in 1..2000) return KoreanMusicIntent(null,null,null,emptyMap(),"INVALID")
        val s=text.trim().replace(Regex("^글레오[ ,，]*"),"").replace(Regex("\\s+"),"").trimEnd('.','!','?')
        fun control(intent:CommandIntent)=KoreanMusicIntent(intent,null,null,emptyMap(),"RECOGNIZED")
        when(s) {
            "다음곡","다음곡틀어줘","다음노래","다음" -> return control(CommandIntent.NEXT)
            "이전곡","이전곡틀어줘","이전노래" -> return control(CommandIntent.PREVIOUS)
            "음악멈춰줘","음악멈춰","음악일시정지","일시정지","멈춰줘","잠깐멈춰줘" -> return control(CommandIntent.PAUSE)
            "음악재생","음악재생해줘","재생해줘","계속재생해줘" -> return control(CommandIntent.PLAY)
            "이노래좋아","이곡좋아","이노래좋아요" -> return control(CommandIntent.LIKE)
            "이런노래는싫어","이노래싫어","이곡싫어요","이노래는싫어" -> return control(CommandIntent.DISLIKE)
        }
        val claims=linkedMapOf<String,String>()
        if(s.contains("비오는") || s.contains("비가오는")) claims["weather"]="rain"
        if(s.contains("퇴근")) claims["tripPurpose"]="commute_home"
        if(listOf("틀지마","재생하지마","아무거나","싫어").any(s::contains))
            return KoreanMusicIntent(null,null,null,claims,"UNSUPPORTED_OR_AMBIGUOUS")
        fun present(stems:List<String>):Boolean = stems.any { stem ->
            Regex(Regex.escape(stem)).findAll(s).any { m ->
                val after=s.substring(m.range.last+1).take(7)
                !listOf("말고","않","아닌","싫").any(after::contains)
            }
        }
        val calm=present(listOf("잔잔","차분","편안"))
        val energetic=present(listOf("신나","신나는","활기","경쾌"))
        val dreamy=present(listOf("몽환","몽롱"))
        if(calm && energetic) return KoreanMusicIntent(null,null,null,claims,"UNSUPPORTED_OR_AMBIGUOUS")
        val key=when {
            energetic -> "ENERGETIC"
            calm && dreamy -> "CALM_DREAMY"
            calm -> "CALM"
            dreamy -> "DREAMY"
            claims["weather"]=="rain" && claims["tripPurpose"]=="commute_home" -> "RAIN_COMMUTE"
            else -> null
        }
        val intent=if(key==null)null else if(s.contains("좀더") || s.contains("바꿔"))CommandIntent.CHANGE_MOOD else CommandIntent.RECOMMEND_AND_PLAY
        return KoreanMusicIntent(intent,key,key?.let(descriptions::get),claims,if(key==null)"UNSUPPORTED_OR_AMBIGUOUS" else "RECOGNIZED")
    }
}
