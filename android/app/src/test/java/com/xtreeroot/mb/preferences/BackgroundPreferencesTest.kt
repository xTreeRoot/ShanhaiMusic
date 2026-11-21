package com.xtreeroot.mb.preferences

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackgroundPreferencesTest {
    @Test fun missingOpacityKeepsThemeDefaultWhileChosenOpacityPersists() {
        val old = AppPreferences.parse(JSONObject().put("volume", 0.27))
        assertNull(old.backgroundOpacity)
        val edited = old.copy(backgroundOpacity = 0.65f)
        assertEquals(edited, AppPreferences.parse(edited.toJson()))
        assertEquals(1f, AppPreferences.parse(JSONObject().put("backgroundOpacity", 2)).backgroundOpacity)
        assertEquals(0f, AppPreferences.parse(JSONObject().put("backgroundOpacity", -1)).backgroundOpacity)
    }
}
