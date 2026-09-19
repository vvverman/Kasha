package brain.studio

import kotlin.test.*

class ActionLayoutTest {
    @Test fun longLabelRequiresVerticalLayoutEvenInWideWindow() {
        assertFalse(equalActionsFit(520f, listOf(90f, 210f), 72f, 10f, 48f))
    }
    @Test fun actualFontScaleAndPaddingDetermineLayout() {
        assertTrue(equalActionsFit(400f, listOf(90f, 80f), 72f, 10f, 48f))
        assertFalse(equalActionsFit(390f, listOf(180f, 160f), 72f, 10f, 48f))
    }
    @Test fun threeThemesFitOnlyWhenAllFullLabelsFit() {
        assertTrue(equalActionsFit(350f, listOf(70f, 50f, 60f), 40f, 6f))
        assertFalse(equalActionsFit(288f, listOf(70f, 50f, 60f), 40f, 6f))
    }
    @Test fun invalidWidthsDoNotEnableHorizontalLayout() {
        assertFalse(equalActionsFit(-1f, listOf(80f), 40f, 6f))
        assertFalse(equalActionsFit(Float.NaN, listOf(80f), 40f, 6f))
    }
}
