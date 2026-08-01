package tech.ula

import android.app.admin.DeviceAdminReceiver

/**
 * Optional Device Administrator endpoint. The declared policy is deliberately
 * limited to force-lock; it does not enable wipe/reset-password policies.
 */
class TerminalDeviceAdminReceiver : DeviceAdminReceiver()
