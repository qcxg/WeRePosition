package com.shiraka.wereposition.utils

import android.content.Context
import android.content.pm.PackageManager
import okhttp3.Request
import java.security.MessageDigest

/** Android identity headers required for direct Google Maps Platform web service requests. */
object GoogleAndroidRestrictionHeaders {
    fun apply(builder: Request.Builder, context: Context): Request.Builder {
        builder.header("X-Android-Package", context.packageName)
        val certificateSha1 = runCatching {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signature = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
                ?: return@runCatching null
            MessageDigest.getInstance("SHA-1")
                .digest(signature.toByteArray())
                .joinToString("") { "%02X".format(it) }
        }.getOrNull()
        if (certificateSha1 != null) builder.header("X-Android-Cert", certificateSha1)
        return builder
    }
}
