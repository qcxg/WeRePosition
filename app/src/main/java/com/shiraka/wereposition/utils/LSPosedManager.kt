package com.shiraka.wereposition.utils

import android.content.Context
import android.content.pm.PackageManager
import com.shiraka.wereposition.LocationApp
import com.shiraka.wereposition.data.model.AppInfoItem

class LSPosedManager {
    private val excludedPackages = setOf(
        "android", "system", "com.google.android.gms", "com.android.location.fused",
        "com.android.phone", "com.android.networkstack"
    )

    fun isModuleActive(): Boolean {
        return LocationApp.isModuleActive.value
    }

    fun getHookedApps(context: Context): List<AppInfoItem> {
        val scope = com.shiraka.wereposition.LocationApp.mService?.scope ?: emptyList()
        val pm = context.packageManager
        return scope.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                val isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 || 
                               (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                AppInfoItem(pkg, label, isSystem)
            } catch (e: Exception) {
                AppInfoItem(pkg, pkg, false) // fallback
            }
        }.sortedBy { it.appName }
    }

    fun getInstalledApps(context: Context): List<AppInfoItem> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .asSequence()
            .filter { it.packageName != context.packageName && it.packageName !in excludedPackages }
            .map { info ->
                AppInfoItem(
                    info.packageName,
                    pm.getApplicationLabel(info).toString(),
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                )
            }
            .sortedWith(compareBy<AppInfoItem> { it.isSystem }.thenBy { it.appName.lowercase() })
            .toList()
    }

    fun setRfScope(packageName: String, enabled: Boolean, onResult: (Boolean) -> Unit): Boolean {
        if (packageName in excludedPackages || packageName == "com.shiraka.wereposition") return false
        val service = LocationApp.mService ?: return false
        return runCatching {
            if (enabled) {
                service.requestScope(listOf(packageName), object :
                    io.github.libxposed.service.XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(scope: List<String>) = onResult(true)
                    override fun onScopeRequestFailed(message: String) = onResult(false)
                })
            } else {
                service.removeScope(listOf(packageName))
                onResult(true)
            }
            true
        }.getOrDefault(false)
    }
}
