package app.xinghe.tv.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CecHomeTest {
    @Test fun appleVendorIdentifiesRenamedDevice() {
        assertTrue(CecHome.isAppleTv(0x0010fa, "Living Room"))
    }

    @Test fun nameFallbackRequiresAppleTvName() {
        assertTrue(CecHome.isAppleTv(null, "Apple TV"))
        assertTrue(CecHome.isAppleTv(0, "apple_tv"))
        assertTrue(CecHome.isAppleTv(null, "Apple TV 4K"))
        assertFalse(CecHome.isAppleTv(null, "Playback_1"))
        assertFalse(CecHome.isAppleTv(null, "TV"))
        assertFalse(CecHome.isAppleTv(null, "Not Apple TV"))
    }

    @Test fun anotherVendorOverridesAnAppleTvLabel() {
        assertFalse(CecHome.isAppleTv(0x123456, "Apple TV"))
    }
}
