package app.lockmein

import android.app.admin.DeviceAdminReceiver

/** The device-owner component: `dpm set-device-owner app.lockmein/.AdminReceiver` makes this app the owner. */
class AdminReceiver : DeviceAdminReceiver()
