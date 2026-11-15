import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val mapsLocal = Properties().apply {
    rootProject.file("maps.local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val mapsKey = mapsLocal.getProperty("MAPS_API_KEY", "").trim()

android {
    namespace = "com.example.gleo_type"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gleohearts.submission"
        minSdk = 28
        targetSdk = 37

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey.ifBlank { "MISSING_MAPS_API_KEY" }
        buildConfigField("boolean", "MAPS_CONFIGURED", mapsKey.isNotBlank().toString())

        // 분석 서버 이벤트 수신 주소
        buildConfigField(
            "String",
            "VEHICLE_EVENT_ENDPOINT",
            "\"\""
        )
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Render unchanged SVG icon assets exported from the read-only Figma V12 reference.
    implementation("com.caverock:androidsvg-aar:1.4")
    // Pins to a Kotlin 2.2 / Compose-compatible release; includes Maps SDK transitively.
    implementation("com.google.maps.android:maps-compose:6.12.0")
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // JVM-only Android-compatible wire/journal codec tests.

    androidTestImplementation(
        platform(libs.androidx.compose.bom)
    )
    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )
    androidTestImplementation(
        libs.androidx.espresso.core
    )
    androidTestImplementation(
        libs.androidx.junit
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )
    debugImplementation(
        libs.androidx.compose.ui.tooling
    )

    // Pleos Connect Vehicle SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    val media3Version = "1.11.1"

    implementation("androidx.media3:media3-exoplayer:${media3Version}")
    implementation("androidx.media3:media3-session:${media3Version}")
}

// Reproducible fixture paths without an external init script.
tasks.withType<Test>().configureEach { workingDir = rootProject.projectDir }
