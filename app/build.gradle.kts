plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.dovecoteescapee.byedpi"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        val membershipUrl = providers.gradleProperty("updateMembershipUrl")
            .orElse(providers.environmentVariable("UPDATE_MEMBERSHIP_URL"))
            .getOrElse("").trim().trimEnd('/')
        require(membershipUrl.isEmpty() ||
            Regex("^https://[a-z0-9][a-z0-9.-]+(?::443)?$").matches(membershipUrl)
        ) { "UPDATE_MEMBERSHIP_URL must be a public HTTPS origin, without credentials or a path" }
        buildConfigField("String", "UPDATE_MEMBERSHIP_URL", "\"$membershipUrl\"")
        // Keep the source namespace for the native JNI bridge, but publish the
        // fork as an independent app that can coexist with ByeDPIAndroid.
        applicationId = "io.github.lolososka.zapretmobile"
        minSdk = 21
        targetSdk = 34
        versionCode = 26
        versionName = "0.4.0"
        manifestPlaceholders["applicationLabel"] = "@string/app_name"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86")
            abiFilters.add("x86_64")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}\"")

            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Optional side-by-side QA build; never replaces the signed public app.
            if (providers.gradleProperty("sideBySidePreview").orNull == "true") {
                applicationIdSuffix = ".preview"
                versionNameSuffix = "-preview"
                manifestPlaceholders["applicationLabel"] = "@string/preview_app_name"
            }
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}-debug\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets"))
    // The legacy ndk-build task regenerates src/main/jniLibs. Keep the
    // Telegram Rust core in a separate, stable directory so CMake can import
    // it before ndk-build output is produced.
    sourceSets["main"].jniLibs.srcDir("src/main/tgwsLibs")

    // https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en#blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.takisoft.preferencex:preferencex:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

val windowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val preparedNative = layout.buildDirectory.dir("generated/windowsJni")
val prepareWindowsNativeSources = tasks.register<Sync>("prepareWindowsNativeSources") {
    onlyIf { windowsHost }
    from("src/main/jni")
    into(preparedNative)
    exclude("**/.git", "**/.git/**")
    doLast {
        // Git without Windows symlink privileges checks headers out as one-line paths.
        // Expand only those forwarding headers in the disposable build copy, never in submodules.
        val root = preparedNative.get().asFile.canonicalFile
        root.walkTopDown().filter { it.isFile && it.extension == "h" && it.length() < 256 }.forEach { header ->
            val link = header.readText().trim()
            if (Regex("^\\.\\./[A-Za-z0-9_./-]+$").matches(link)) {
                val target = header.parentFile.resolve(link).canonicalFile
                require(target.toPath().startsWith(root.toPath()) && target.isFile) {
                    "Native forwarding header leaves prepared sources: ${header.name}"
                }
                header.writeBytes(target.readBytes())
            }
        }
    }
}

tasks.register<Exec>("runNdkBuild") {
    group = "build"
    if (windowsHost) dependsOn(prepareWindowsNativeSources)

    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    setArgs(listOf(
        "NDK_PROJECT_PATH=${layout.buildDirectory.dir("intermediates/ndkBuild").get().asFile}",
        "NDK_LIBS_OUT=${file("src/main/jniLibs")}",
        "APP_BUILD_SCRIPT=${if (windowsHost) preparedNative.get().file("Android.mk").asFile else file("src/main/jni/Android.mk")}",
        "NDK_APPLICATION_MK=${if (windowsHost) preparedNative.get().file("Application.mk").asFile else file("src/main/jni/Application.mk")}",
        "-j${Runtime.getRuntime().availableProcessors().coerceIn(1, 8)}"
    ))

    println("Command: $commandLine")
}

tasks.preBuild {
    dependsOn("runNdkBuild", "prepareLicenseAssets")
}

tasks.register<Copy>("prepareLicenseAssets") {
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
    from(rootProject.file("LICENSE")) { rename { "GPL-3.0.txt" } }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from("src/main/cpp/byedpi/LICENSE") { rename { "ByeDPI-MIT.txt" } }
    from("src/main/cpp/byedpi/kavl.h") { rename { "KAVL-notice.txt" } }
    from("src/main/jni/hev-socks5-tunnel/LICENSE") { rename { "hev-socks5-tunnel-MIT.txt" } }
    from("src/main/jni/hev-socks5-tunnel/src/core/LICENSE") { rename { "hev-core-MIT.txt" } }
    from("src/main/jni/hev-socks5-tunnel/third-part/hev-task-system/LICENSE") { rename { "hev-task-system-MIT.txt" } }
    from("src/main/jni/hev-socks5-tunnel/third-part/lwip/LICENSE") { rename { "lwIP-BSD.txt" } }
    from("src/main/jni/hev-socks5-tunnel/third-part/yaml/LICENSE") { rename { "libyaml-MIT.txt" } }
}
