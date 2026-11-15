package com.example.gleo_type.ui

import com.example.gleo_type.ui.app.*
import org.junit.Assert.*
import org.junit.Test

class AuroraPolicyTest {
    @Test fun textAndActionPairsMeetPleosContrastThreshold() {
        val p = MidnightAurora
        listOf(p.text to p.background, p.text to p.surface, p.secondary to p.surface,
            p.lavender to p.surface, p.background to p.lavender, p.text to p.blue,
            p.text to p.cardEnd, p.background to p.lightSurface, p.lightSecondary to p.lightSurface,
            p.blue to p.lightVariant).forEach { (text, background) ->
            assertTrue("Contrast ${p.contrast(text, background)}", p.contrast(text, background) >= 4.5)
        }
    }
    @Test fun reportedPhysicalDpiIsUsedInsteadOfEquatingDpWithMm() {
        listOf(floatArrayOf(1f,160f,160f), floatArrayOf(1.5f,210f,220f), floatArrayOf(2f,500f,450f)).forEach { (density,x,y) ->
            val m = PhysicalUiMetrics.from(density, density, x, y)
            assertTrue(m.reportedDpiValid)
            assertTrue(m.targetDp * density / x * 25.4 >= 12.5)
            assertTrue(m.targetDp * density / y * 25.4 >= 12.5)
            assertTrue(m.gapDp * density / maxOf(x,y) * 25.4 >= 4)
            assertTrue(m.captionSp >= 24f)
        }
        assertFalse(PhysicalUiMetrics.from(1f,1f,0f,Float.NaN).reportedDpiValid)
    }
    @Test fun noPlatformSignalNeverGrantsComplexUi() {
        assertTrue(DrivingUx().reduced)
        assertTrue(DrivingUx(true, true, 0).reduced)
        assertTrue(DrivingUx(true, false, 8).reduced)
        assertTrue(DrivingUx(true, true, 511).reduced)
        assertTrue(DrivingUx(true, false, 0).complexAllowed)
    }
}
