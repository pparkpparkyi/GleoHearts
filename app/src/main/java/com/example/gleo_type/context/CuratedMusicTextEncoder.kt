package com.example.gleo_type.context

import com.example.gleo_type.recommendation.*
import org.json.JSONObject

/** Five bounded semantic descriptions actually encoded on PC; Android does not run the CLAP model. */
class CuratedMusicTextEncoder(json: String) : LocalClapTextEncoder {
    private val entries: Map<String,Pair<String,FloatArray>>
    private val revision: String
    init {
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            .joinToString(""){"%02x".format(it.toInt() and 255)}
        require(sha=="faa315e30d41f58a1e839dfc02455573ac120e7be22efeddae53293e4f680394") {"Prototype artifact checksum mismatch"}
        val root=JSONObject(json)
        require(root.getInt("schemaVersion")==1 && root.getString("modelId")=="laion/clap-htsat-unfused")
        revision=root.getString("revision");require(revision=="8fa0f1c6d0433df6e97c127f64b2a1d6c0dcda8a")
        require(root.getString("processing")=="REAL_PC_CLAP_PRECOMPUTED_CURATED_ENGLISH")
        val rows=root.getJSONArray("prototypes")
        entries=(0 until rows.length()).associate { i -> val row=rows.getJSONObject(i)
            val key=row.getString("key");val english=row.getString("encodedText")
            require(KoreanMusicIntentParser.descriptions[key]==english)
            val v=row.getJSONArray("vector")
            require(v.length()==512)
            key to (english to PreferenceMath.normalize(FloatArray(512){v.getDouble(it).toFloat()}))
        }
        require(entries.size==rows.length() && entries.keys==KoreanMusicIntentParser.descriptions.keys)
    }
    override fun encode(text: String): TextVector? {
        val started=System.nanoTime()
        val parsed=KoreanMusicIntentParser.parse(text)
        val (english,vector)=entries[parsed.moodKey] ?: return null
        return TextVector(vector.copyOf(),"laion/clap-htsat-unfused",revision,text,"ko_rules_to_en",
            (System.nanoTime()-started)/1_000_000.0,english,"CURATED_ENGLISH_PRECOMPUTED_LOOKUP")
    }
}
