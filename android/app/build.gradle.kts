plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

val quickJsLicenseDir = layout.buildDirectory.dir("generated/quickjs-assets")
val copyQuickJsLicense by tasks.registering(Copy::class) {
    from(rootProject.file("../ios/Sources/MBJavaScript/vendor/LICENSE")) { rename { "QuickJS-LICENSE.txt" } }
    into(quickJsLicenseDir)
}
val videoFixtureDir = layout.buildDirectory.dir("generated/video-fixture")
val copyVideoFixture by tasks.registering(Copy::class) {
    from(rootProject.file("../ios/Tests/MBFoundationTests/Fixtures/video.mp4"))
    into(videoFixtureDir)
}
val cencFixtureDir = layout.buildDirectory.dir("generated/cenc-fixture")
val copyCencFixture by tasks.registering(Copy::class) {
    from(rootProject.file("../ios/Tests/MBFoundationTests/Fixtures/cenc-aac.m4a"))
    into(cencFixtureDir)
}

android {
    namespace = "com.xtreeroot.mb"
    buildToolsVersion = "36.1.0"
    ndkVersion = "28.2.13676358"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.xtreeroot.mb"
        minSdk = 26
        targetSdk = 36
        versionCode = 60
        versionName = "0.1.59"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets["main"].assets.srcDir("../../ios/Sources/MBFoundation/Resources")
    sourceSets["main"].assets.srcDir(quickJsLicenseDir)
    sourceSets["androidTest"].assets.srcDir("../../ios/Tests/MBFoundationTests/Fixtures/BilibiliPlugin")
    sourceSets["androidTest"].assets.srcDir(videoFixtureDir)
    sourceSets["androidTest"].assets.srcDir(cencFixtureDir)
    sourceSets["test"].resources.srcDir(cencFixtureDir)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = false }
}

tasks.named("preBuild") { dependsOn(copyQuickJsLicense, copyVideoFixture, copyCencFixture) }
kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.mediarouter:mediarouter:1.8.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
