plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.chagok.app"
    compileSdk = 34

    defaultConfig {
        applicationId = providers.gradleProperty("previewApplicationId").getOrElse("ai.chagok.app")
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        // 앱 안 표기는 모드 접미사를 붙여 0.4.0-demo / 0.4.0-real 이 된다
        versionName = "0.4.0"
    }

    // 빌드 옵션 = 모드.
    //  demo: 데모 모드. 가상 차량·가상 시계·시연 오버레이·예시 데이터 (src/demo)
    //  real: 실제 동작 모드. Pleos SDK(에뮬레이터·실차) 값만 쓰고 시연 UI·예시 데이터가 없다 (src/real)
    flavorDimensions += "mode"
    productFlavors {
        create("demo") {
            dimension = "mode"
            versionNameSuffix = "-demo"
            buildConfigField("boolean", "DEMO", "true")
        }
        create("real") {
            dimension = "mode"
            versionNameSuffix = "-real"
            buildConfigField("boolean", "DEMO", "false")
            // Pleos 음성(서버)용 앱 인증 정보. 저장소에 넣지 않고 ~/.gradle/gradle.properties 등으로만 준다
            buildConfigField("String", "PLEOS_CLIENT_ID", "\"${project.findProperty("pleos.clientId") ?: ""}\"")
            buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${project.findProperty("pleos.clientSecret") ?: ""}\"")
        }
    }
    // release 빌드: 공모전 제출용. 출시용 키스토어가 아직 없어 debug 키스토어로 서명한다(스토어 배포용이 아니다)
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
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
    // 차량 음성(질문하고 대답 듣기)과 내비 도착 이벤트·즐겨찾기
    "realImplementation"("ai.pleos.playground:SpeechToText:3.3.1")
    "realImplementation"("ai.pleos.playground:TextToSpeech:3.3.1")
    "realImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    // PDF 문서 타임스탬프. 앱의 BouncyCastle 1.79를 사용한다.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    // 일일 앵커의 RFC 3161 타임스탬프 요청·토큰 서명 검증
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    // 앱 DB 전체 암호화 (SQLCipher)
    implementation("net.zetetic:sqlcipher-android:4.5.6@aar")
    implementation("androidx.sqlite:sqlite:2.2.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
}
