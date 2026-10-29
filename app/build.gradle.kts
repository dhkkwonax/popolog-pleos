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

    // 빌드 옵션 = 모드.
    //  demo: 데모 모드. 가상 차량·가상 시계·시연 오버레이·예시 데이터 (src/demo)
    //  real: 실제 동작 모드. Pleos SDK(에뮬레이터·실차) 값만 쓰고 시연 UI·예시 데이터가 없다 (src/real)
    flavorDimensions += "mode"
    productFlavors {
        create("demo") {
            dimension = "mode"
            buildConfigField("boolean", "DEMO", "true")
        }
        create("real") {
            dimension = "mode"
            versionNameSuffix = "-real"
            buildConfigField("boolean", "DEMO", "false")
        }
    }
    buildFeatures { buildConfig = true }
    // BouncyCastle 세 jar 가 같은 OSGi 매니페스트를 가진다
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    "realImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "realImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    // 일일 앵커의 RFC 3161 타임스탬프 요청·토큰 서명 검증
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
}
