package com.example.gleo_type

import com.example.gleo_type.context.*
import com.example.gleo_type.recommendation.MusicRequestParser
import com.example.gleo_type.event.TestJson
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class KoreanMusicIntentTest {
    @Test fun fixedSyntheticCorpusAndLegacyFallbackAreMeasuredSeparately() {
        val path=System.getenv("GLEO_KOREAN_CORPUS");assumeTrue(path!=null)
        val corpus=TestJson.parse(File(path!!).readText()) as Map<*,*>
        val results=(corpus["cases"] as List<*>).map { row ->
            val r=row as Map<*,*>;val text=r["text"] as String
            val start=System.nanoTime();val parsed=KoreanMusicIntentParser.parse(text);val ms=(System.nanoTime()-start)/1e6
            val legacy=MusicRequestParser.parse(text)
            assertEquals(text,r["expectedIntent"],parsed.intent?.name)
            assertEquals(text,r["expectedMood"],parsed.moodKey)
            mapOf("text" to text,"expectedIntent" to r["expectedIntent"],"expectedMood" to r["expectedMood"],
                "intent" to parsed.intent?.name,"mood" to parsed.moodKey,"english" to parsed.english,
                "claims" to parsed.claims,"status" to parsed.status,"parseMs" to ms,
                "legacyIntent" to legacy.intent.name,"legacyStatus" to legacy.status)
        }
        System.getenv("GLEO_KOREAN_RESULTS")?.let { File(it).apply{parentFile!!.mkdirs()}.writeText(json(mapOf(
            "origin" to corpus["origin"],"descriptions" to KoreanMusicIntentParser.descriptions,"results" to results))) }
    }
    @Test fun userRainAndCommuteNeverBecomeObservedApiData() {
        val p=KoreanMusicIntentParser.parse("글레오, 비 오는 퇴근길에 어울리는 음악 틀어줘")
        assertEquals(mapOf("weather" to "rain","tripPurpose" to "commute_home"),p.claims)
        assertEquals("RAIN_COMMUTE",p.moodKey)
        assertEquals(CommandIntent.PAUSE,KoreanMusicIntentParser.parse("음악 멈춰줘").intent)
        assertNull(KoreanMusicIntentParser.parse("음악 틀지 마").intent)
    }
    private fun json(v:Any?):String=when(v) {
        null->"null"
        is Map<*,*> -> v.entries.joinToString(",","{","}"){json(it.key.toString())+":"+json(it.value)}
        is Iterable<*> -> v.joinToString(",","[","]"){json(it)}
        is Number,is Boolean ->v.toString()
        else->"\""+v.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\""
    }
}
