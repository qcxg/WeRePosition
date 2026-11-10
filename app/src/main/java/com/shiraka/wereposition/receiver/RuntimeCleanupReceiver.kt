package com.shiraka.wereposition.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shiraka.wereposition.BuildConfig
import com.shiraka.wereposition.provider.SpooferProvider
import com.shiraka.wereposition.utils.ConfigManager
import com.shiraka.wereposition.utils.RootManager
import com.shiraka.wereposition.utils.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RuntimeCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_BOOT_COMPLETED
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val rootManager = RootManager()
                val pureMode = SettingsManager(context.applicationContext).pureModeEnabled
                val locationDisabled = if (pureMode) {
                    rootManager.setPureArmed(true)
                    rootManager.setLocationMasterEnabled(false)
                } else true
                if (!locationDisabled) {
                    // Keep the previous active snapshot as a real-location fence if
                    // Android's master switch cannot be turned off yet.
                    Log.e("RuntimeCleanupReceiver", "location switch stayed on; preserving active runtime fence")
                    return@launch
                }
                val saved = ConfigManager(rootManager).saveConfig(0.0, 0.0, false)
                if (pureMode && locationDisabled && saved) rootManager.setPureArmed(false)
                if (saved) {
                    SpooferProvider.isActive = false
                    SpooferProvider.publishRuntimeConfigGeneration(0L)
                    SpooferProvider.publishAnchorPosition(0.0, 0.0)
                    SpooferProvider.publishCurrentPosition(0.0, 0.0)
                    SpooferProvider.publishRuntimeHeartbeat(0L, "")
                    SpooferProvider.clearRfForCoverageTransition()
                    SpooferProvider.routeJson = "[]"
                    SpooferProvider.isRouteMode = false
                    SettingsManager(context.applicationContext).apply {
                        isSpoofingActive = false
                        // Release replacements require an LSPosed reload. Debug APKs
                        // are also installed for service-only testing.
                        moduleRestartRequired = action == Intent.ACTION_MY_PACKAGE_REPLACED &&
                            !BuildConfig.DEBUG
                    }
                    Log.d("RuntimeCleanupReceiver", "runtime config marked inactive after $action")
                } else {
                    Log.e(
                        "RuntimeCleanupReceiver",
                        "runtime stop fence was requested, but inactive files were not fully persisted after $action"
                    )
                }
            } catch (e: Throwable) {
                Log.e("RuntimeCleanupReceiver", "failed to mark runtime config inactive after $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
