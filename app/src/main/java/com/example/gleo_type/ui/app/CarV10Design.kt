package com.example.gleo_type.ui.app

/** Exact ARGB values read from Figma 260:25413 / verified Light-Dark variables.
 * Pure tokens only: no theme activation, font substitution, OS chrome or dp scaling.
 */
data class CarV10ColorTokens(val backgroundPrimary:Long,val backgroundSecondary:Long,
    val backgroundPopup:Long,val surfaceBasic:Long,val surfaceLow:Long,val surfaceHigh:Long,
    val surfaceAccent:Long,val musicPrimary:Long,val textPrimary:Long,val textSecondary:Long)

object CarV10Design {
    const val brandDeepBlue=0xFF002C5F
    const val musicPrimary=0xFF4781FF
    val light=CarV10ColorTokens(0xFFF7F8FA,0xFFFFFFFF,0xFFFFFFFF,0xFFFFFFFF,
        0xFFF7F8FA,0xFFEDEEF2,0xFFE0E1E6,musicPrimary,0xFF1C2936,0xFF667988)
    val dark=CarV10ColorTokens(0xFF1D1E21,0xFF131417,0xFF313236,0xFF1D1E21,
        0xFF131417,0xFF313236,0xFF44464E,musicPrimary,0xFFF5F6F9,0xFFB7C0CB)
    fun colors(darkMode:Boolean)=if(darkMode)dark else light
    const val fontStatus="Asta Sans variable font bundled under SIL OFL-1.1"
    val artworkLight=listOf(0xFF8CB9E5,0xFFC7DFFD,0xFFCFACD9)
    val artworkDark=listOf(0xFF234E76,0xFF7299D7,0xFFAF85CD)
    const val exclusion=0xFFB52939
    const val iconGridDp=48
    // Grid size is neither a touch target nor the Android adaptive-icon viewport.
    const val figmaAppIconGrid=144
}
