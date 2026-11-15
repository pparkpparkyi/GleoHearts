package com.example.gleo_type.context

/** Deliberately outside the unchanged three-function Gleo Schema. Never converts archive to LIKE. */
object LocalHeartsCommand {
 fun matches(text:String)=text.filterNot{it.isWhitespace()||it in "?.!"} in setOf("이노래기억해줘","이노래저장해줘")
 const val DISCLOSURE="로컬 Hearts 명령 · 공식 Gleo Schema 미지원"
}
