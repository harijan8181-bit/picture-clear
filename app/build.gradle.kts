import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 출시용 비밀 설정(서명 키, 실제 광고 ID)은 깃에 올리지 않는 release.properties에서 읽음
// 예시는 release.properties.example 참고
val releaseProps = Properties().apply {
    val file = rootProject.file("release.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// 구글 공식 테스트 광고 ID (개발·디버그용)
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testAdmobBannerId = "ca-app-pub-3940256099942544/6300978111"

android {
    namespace = "com.yeoncompany.photoinfoeraser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yeoncompany.photoinfoeraser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        manifestPlaceholders["admobAppId"] = testAdmobAppId
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$testAdmobBannerId\"")
    }

    signingConfigs {
        if (releaseProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 코드·리소스 축소로 용량을 줄이고 분석을 어렵게 함
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))

            val appId = releaseProps.getProperty("admobAppId")
            val bannerId = releaseProps.getProperty("admobBannerId")
            if (appId == null || bannerId == null) {
                logger.warn("⚠️ release.properties에 실제 AdMob ID가 없어 테스트 광고 ID로 빌드합니다. 출시 전에 꼭 설정하세요.")
            }
            manifestPlaceholders["admobAppId"] = appId ?: testAdmobAppId
            buildConfigField("String", "ADMOB_BANNER_ID", "\"${bannerId ?: testAdmobBannerId}\"")

            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    // 안드로이드 13+ 설정의 "앱 언어"에서 한국어/영어를 고를 수 있게 함 (기본 문구는 영어)
    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.play.services.ads)
    implementation(libs.ump)
    implementation(libs.play.review)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.text.recognition.korean)
    implementation(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
}
