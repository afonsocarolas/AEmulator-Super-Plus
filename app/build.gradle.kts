import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Sunset modification, 2026-10-02: ship the actual license and dated notices.
val legalAssetsDir = layout.buildDirectory.dir("generated/legalAssets")
val copyLegalNotices = tasks.register<Copy>("copyLegalNotices") {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"),
        rootProject.file("docs/build-source.md"), rootProject.file("docs/license-audit.md"))
    into(legalAssetsDir.map { it.dir("legal") })
}

// подпись релиза: keystore.properties в корне (в git не попадает, см. .gitignore);
// без него релиз собирается неподписанным
val signProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val canSign = signProps.getProperty("storeFile")?.let { rootProject.file(it).isFile } == true

android {
    namespace = "app.aemu"
    sourceSets.getByName("main").assets.srcDir(legalAssetsDir)
    compileSdk = 36

    defaultConfig {
        applicationId = "app.aemu.superplus" // Modified for AEmulator Plus, 2026-10-04: single variant, no clone; Super Plus, 2026-10-11: own package
        minSdk = 26
        // 28: гостевые бинарники запускаются из каталога данных приложения (W^X для targetSdk>=29)
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 32
        versionName = "0.0.0.2.9" // <AEmulator base>.<Plus release>
        ndk { abiFilters += listOf("arm64-v8a") }
        // AIemulator: default proxy server, e.g. -PaiemuServerUrl=https://aiemulator.example.workers.dev; users can change it in the app
        buildConfigField("String", "AIEMU_SERVER_URL", "\"${(project.findProperty("aiemuServerUrl") as String?) ?: ""}\"")
    }

    signingConfigs {
        if (canSign) create("release") {
            storeFile = rootProject.file(signProps.getProperty("storeFile"))
            storePassword = signProps.getProperty("storePassword")
            keyAlias = signProps.getProperty("keyAlias")
            keyPassword = signProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ""
            // тот же ключ, что у релиза: отладочная сборка ставится поверх без потери образов
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        // исполняемые файлы движка лежат как lib*.so и должны распаковываться на диск
        jniLibs { useLegacyPackaging = true }
        // the Anthropic SDK and its Jackson/HTTP dependencies each ship this build-metadata file
        resources { excludes += "META-INF/DEPENDENCIES" }
    }

    androidResources {
        noCompress += listOf("payload", "tar", "gz", "so", "img")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
            optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.11.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-alpha16")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.junrar:junrar:8.1.1")
    implementation("org.brotli:dec:0.1.2")
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    implementation("com.github.luben:zstd-jni:1.5.7-4@aar")
    implementation("org.anarres.lzo:lzo-commons:1.0.6")
    // AIemulator (Super Plus): Claude through the owner's proxy server
    implementation("com.anthropic:anthropic-java:2.34.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") { dependsOn(copyLegalNotices) }

tasks.register<Copy>("copyReleaseApks") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "AEmulator-Super-Plus-app.aemu.superplus.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("release-apks"))
}
