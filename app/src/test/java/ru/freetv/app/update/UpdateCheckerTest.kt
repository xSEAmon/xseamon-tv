package ru.freetv.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `parses version code from tag`() {
        assertEquals(34, UpdateChecker.parseVersionCode("v34"))
        assertEquals(34, UpdateChecker.parseVersionCode("34"))
        assertEquals(7, UpdateChecker.parseVersionCode("V7"))
        assertEquals(125, UpdateChecker.parseVersionCode(" v125 "))
    }

    @Test
    fun `rejects non-numeric or version-name tags`() {
        assertNull(UpdateChecker.parseVersionCode("3.0.4"))
        assertNull(UpdateChecker.parseVersionCode("v3.0.4"))
        assertNull(UpdateChecker.parseVersionCode(""))
        assertNull(UpdateChecker.parseVersionCode("release"))
        assertNull(UpdateChecker.parseVersionCode("v34b"))
    }
}
