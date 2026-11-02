package app.lockmein

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.os.Bundle
import android.widget.TextView

/** A status line for now: is this app the device owner. The real screens come with the request and window features. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName)
        setContentView(
            TextView(this).apply {
                setPadding(48, 96, 48, 48)
                textSize = 18f
                text = if (owner) "Lock Me In is the device owner." else "Lock Me In is installed but is not the device owner yet."
            },
        )
    }
}
