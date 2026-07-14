package categorizer.app

import categorizer.domain.testing.ContractFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class AppFoundationTest {
    @Test
    fun baselineTestSourceSetRuns() {
        assertEquals(29, MINIMUM_ANDROID_API)
        assertEquals("cars-fixture-1", ContractFixtures.MODEL_VERSION)
    }

    private companion object {
        const val MINIMUM_ANDROID_API = 29
    }
}
