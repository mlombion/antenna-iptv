package com.malick.antenna.tv

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.malick.antenna.tv.cast.BootReceiver
import com.malick.antenna.tv.cast.CastService
import com.malick.antenna.tv.pointer.PointerService

/** One APK for TVs and phones: the TV gets the remote-friendly screens, a phone the touch ones. */
object DeviceKind {
    fun isTv(context: Context): Boolean {
        val ui = context.getSystemService(UiModeManager::class.java)
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /**
     * On a phone, switch off the TV-only parts: the listener phones send channels to, its start at boot, and the
     * accessibility service (so "Antenna phone remote" doesn't show up in the phone's accessibility settings).
     */
    fun disableTvParts(context: Context) {
        val pm = context.packageManager
        listOf(CastService::class.java, BootReceiver::class.java, PointerService::class.java).forEach { cls ->
            val name = ComponentName(context, cls)
            if (pm.getComponentEnabledSetting(name) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                runCatching { pm.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
            }
        }
    }
}
