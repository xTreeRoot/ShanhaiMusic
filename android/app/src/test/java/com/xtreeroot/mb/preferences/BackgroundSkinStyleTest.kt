package com.xtreeroot.mb.preferences

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackgroundSkinStyleTest {
    @Test fun legacyPreferencesAndEditedStyleRoundTripWithoutLosingExistingValues() {
        val old = AppPreferences.parse(JSONObject("""{"volume":0.27,"backgroundOpacity":0.65,"selectedSourceId":"saved"}"""))
        assertEquals(BackgroundSkinStyle(), old.backgroundStyle)
        val edited = old.copy(backgroundStyle = BackgroundSkinStyle(2.3f, -0.4f, 0.7f, 12f, BackgroundAccent.Rose, true))
        assertEquals(edited, AppPreferences.parse(edited.toJson()))
        assertEquals(0.27f, edited.volume, 0.001f)
        assertEquals("saved", edited.selectedSourceId)
    }
    @Test fun outOfBoundsAndNonFiniteTransformsAreClampedOrReset() {
        val style = BackgroundSkinStyle.parse(JSONObject("""{"zoom":99,"horizontal":-4,"vertical":3,"blur":"NaN"}"""))
        assertEquals(4f, style.zoom); assertEquals(-1f, style.horizontal); assertEquals(1f, style.vertical)
        assertEquals(0f, style.blur); assertFalse(style.appliesToPlayer)
    }
}
