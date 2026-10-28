plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.chagok.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.chagok.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-demo"
    }

    // sim: 가상 차량(src/main/vehicle/GatewayFactory 원본), pleos: 실제 Pleos SDK 어댑터(src/pleos)
    flavorDimensions += "vehicle"
    productFlavors {
        create("sim") { dimension = "vehicle" }
        create("pleos") {
            dimension = "vehicle"
            versionNameSuffix = "-pleos"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "pleosImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    // 신고용 PDF 의 검증 QR (코어만, 안드로이드 의존 없음)
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
}
