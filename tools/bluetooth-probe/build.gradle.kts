plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.shiraka.wereposition.btprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.shiraka.wereposition.btprobe"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
}
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}
