plugins { id("com.android.application") }

android {
    namespace = "vn.vhb.lpr.edge"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.vhb.lpr.edge"
        minSdk = 21
        targetSdk = 27
        versionCode = 22
        versionName = "0.2.2-TVBOX"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }
}
