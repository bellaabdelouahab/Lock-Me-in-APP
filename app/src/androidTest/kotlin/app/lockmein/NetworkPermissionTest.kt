package app.lockmein

import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/**
 * The DNS fallback and the training uploads read the active network. Without ACCESS_NETWORK_STATE in the manifest that throws, and
 * both stopped silently (0.4.120: training never sent, the fallback never ran). This fails the build if the permission goes again.
 */
class NetworkPermissionTest {
    @Test
    fun theAppMayReadTheActiveNetwork() {
        val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return
        cm.getNetworkCapabilities(network)
        cm.getLinkProperties(network)
    }
}
