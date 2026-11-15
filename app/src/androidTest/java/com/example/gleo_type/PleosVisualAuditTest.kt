package com.example.gleo_type

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.gleo_type.ui.app.PhysicalUiMetrics
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PleosVisualAuditTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun screenCaptureAndReportedPhysicalAudit() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit visual audit", args.getString("visualAudit") == "true")
        val phase = args.getString("capturePhase") ?: "after"
        val context = ui.activity
        val dir = context.filesDir.resolve("diagnostics/pleos-redesign/$phase").apply { mkdirs() }
        ui.waitUntil(30000) { ui.onAllNodesWithTag("nav_0").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1500)
        if (ui.onAllNodesWithText("닫기").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithText("닫기").performClick()
        ui.waitUntil(60000) { runCatching { ui.onAllNodes(hasTestTag("app_root") and SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "기록 로드됨")).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        val originalDark = context.getSharedPreferences("gleo_ui", 0).getBoolean("dark", true)
        val requestedDark = args.getString("darkMode")?.toBooleanStrictOrNull() ?: originalDark
        if (requestedDark != originalDark) {
            ui.onNodeWithTag("nav_4").performClick()
            ui.onNodeWithTag("dark_mode").performScrollTo().performClick()
        }
        val metrics = context.resources.displayMetrics
        val report = JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT).put("manufacturer", android.os.Build.MANUFACTURER)
            .put("model", android.os.Build.MODEL).put("widthPx", metrics.widthPixels).put("heightPx", metrics.heightPixels)
            .put("densityDpi", metrics.densityDpi).put("density", metrics.density).put("scaledDensity", metrics.scaledDensity)
            .put("reportedXdpi", metrics.xdpi).put("reportedYdpi", metrics.ydpi)
            .put("physicalCertification", "emulated_dpi_only_host_scaling_and_real_panel_unverified")
        val screens = JSONArray()
        val uxNodes = ui.onAllNodesWithTag("ux_status").fetchSemanticsNodes()
        report.put("uxStatus", uxNodes.firstOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text } ?: "before_ui_without_ux_monitor")
        (0..4).forEach { tab ->
            if (ui.onAllNodesWithText("닫기").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithText("닫기").performClick()
            ui.onNodeWithTag("nav_$tab").performClick(); ui.waitForIdle(); Thread.sleep(600)
            if (ui.onAllNodesWithText("닫기").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithText("닫기").performClick()
            if (tab == 0) repeat(2) { ui.onNodeWithTag("sheet_handle").performTouchInput { swipeDown() } }
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            shot?.let { image -> dir.resolve("${listOf("home", "music", "calendar", "pins", "profile")[tab]}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle() }
            val boxes = JSONArray()
            ui.onAllNodes(hasClickAction() and isEnabled()).fetchSemanticsNodes().forEach { node ->
                val b = node.boundsInRoot
                if (b.width > 0 && b.height > 0) boxes.put(JSONObject().put("left", b.left).put("top", b.top).put("right", b.right).put("bottom", b.bottom)
                    .put("widthPx", b.width).put("heightPx", b.height)
                    .put("reportedWidthMm", b.width / metrics.xdpi * 25.4).put("reportedHeightMm", b.height / metrics.ydpi * 25.4))
            }
            screens.put(JSONObject().put("tab", tab).put("clickTargets", boxes))
        }
        val physical = PhysicalUiMetrics.from(metrics.density, metrics.scaledDensity, metrics.xdpi, metrics.ydpi)
        val font = Paint().apply { textSize = physical.captionSp * metrics.scaledDensity }
        val bounds = android.graphics.Rect(); font.getTextBounds("음악 재생 Ag", 0, 8, bounds)
        report.put("screens", screens).put("candidateTargetDp", physical.targetDp).put("candidateGapDp", physical.gapDp)
            .put("captionSp", physical.captionSp).put("captionGlyphPx", bounds.height())
            .put("captionGlyphReportedMm", bounds.height() / metrics.ydpi * 25.4)
        font.textSize = physical.primarySp * metrics.scaledDensity
        font.getTextBounds("음악 재생 Ag", 0, 8, bounds)
        report.put("primaryGlyphPx", bounds.height()).put("primaryGlyphReportedMm", bounds.height() / metrics.ydpi * 25.4)
        dir.resolve("display-audit.json").writeText(report.toString(2), Charsets.UTF_8)
        if (requestedDark != originalDark) {
            ui.onNodeWithTag("nav_4").performClick()
            ui.onNodeWithTag("dark_mode").performScrollTo().performClick()
        }
    }
}
