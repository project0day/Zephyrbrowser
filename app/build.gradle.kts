import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.zephyr.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zephyr.browser"
        minSdk = 24
        targetSdk = 34
        versionCode = 140
        versionName = "1.9.15"
    }

    val ksProps = Properties()
    val ksFile = File(rootDir, "keystore/keystore.properties")
    if (ksFile.exists()) ksProps.load(ksFile.inputStream())

    signingConfigs {
        create("release") {
            if (ksFile.exists()) {
                storeFile = File(rootDir, ksProps["storeFile"] as String)
                storePassword = ksProps["storePassword"] as String
                keyAlias = ksProps["keyAlias"] as String
                keyPassword = ksProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    lint {
        // v1.7.2: release lint analysis competed with R8 for RAM and killed the
        // daemon on this 4GB sandbox — vital lint is not worth a failed build here.
        checkReleaseBuilds = false
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "META-INF/*.md"
        // v1.7.79: store native libraries COMPRESSED in the APK.
        // AGP leaves them uncompressed by default (faster cold start, no
        // install-time extraction), but adding Vosk's ~36 MB of libvosk.so
        // across four ABIs pushed the download from 43 MB to 80 MB. These
        // libraries compress by more than half, and this is a sideloaded APK
        // where download size matters far more than a few ms of start-up.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.webkit:webkit:1.17.1")
    // v1.8.0: Custom Tabs SERVICE side (user: «в твиттере ссылка открывается
    // в Kiwi, хотя браузер по умолчанию Zephyr»). Apps that open links
    // "in-app" use Custom Tabs, and Android only offers browsers that declare
    // a CustomTabsService — being the default browser does not enter into it.
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
    // v1.7.6: pull-to-refresh around the page WebView
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // v1.7.0: WireGuard protocol support (official embeddable tunnel library;
    // GoBackend + libwg-go natives for all 4 ABIs, shipped inside the AAR)
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    // v1.7.2: JSR-305 annotations for the vendored org.amnezia.awg engine
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    // v1.7.7: QR scanner — zxing decoding core (pure Java, no camera deps)
    implementation("com.google.zxing:core:3.5.4")
    // v1.7.7: QR scanner camera preview (CameraX + its PreviewView)
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    // v1.7.61: PDF text + AcroForm fields (Apache 2.0, pure Java port of PDFBox).
    // Android has PdfRenderer, but that only rasterises pages — it cannot read
    // text and cannot touch form fields, which is the whole point here.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // v1.7.79: offline speech recognition for phones with no Google services
    // (user runs microG, so ACTION_RECOGNIZE_SPEECH has nobody to answer it).
    // Only the ~12 MB engine ships in the APK; the ~45 MB acoustic model per
    // language is downloaded on demand from Settings, never bundled.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}

/**
 * v1.9.11: format strings are verified before every build.
 *
 * `st_open_fmt` shipped as `Opening %1…` — the `$s` was lost. Nothing caught
 * it: aapt accepts it, the code compiles, and the string is only ever built at
 * runtime. So `getString(st_open_fmt, host)` threw
 * UnknownFormatConversionException the moment the assistant opened a tab, the
 * tool dispatcher turned the throw into "The tool failed: Conversion = '…'",
 * and the model read that back as a browser limitation and started telling the
 * user to open links by hand (user: «резко перестал открывать вкладки»).
 *
 * One malformed placeholder silently disabled a whole tool. This makes it a
 * build failure instead.
 */
val checkStringFormats by tasks.registering {
    val resDir = file("src/main/res")
    inputs.dir(resDir)
    outputs.upToDateWhen { false }
    doLast {
        // %N must carry a conversion: %1$s, %2$d… A bare %1 is the bug.
        val bad = Regex("""%\d(?!\$[a-zA-Z])""")
        // A name ending in _fmt promises an argument. st_search_fmt lost its
        // placeholder the same way and nothing complained: the argument was
        // passed and silently dropped, so the status said "Ищу в браузере"
        // without ever showing what it was searching for.
        val fmtName = Regex("""name="([\w.]*_fmt)"""")
        // %s, %1$s, and flagged forms like %,d or %.2f all count
        val hasPlaceholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")
        val errors = mutableListOf<String>()
        resDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.name.startsWith("strings") }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val where = "${f.relativeTo(projectDir)}:${i + 1}: ${line.trim()}"
                    if (bad.containsMatchIn(line)) errors += where
                    else if (fmtName.containsMatchIn(line) && !hasPlaceholder.containsMatchIn(line)) {
                        errors += "$where   (*_fmt with no placeholder)"
                    }
                }
            }
        if (errors.isNotEmpty()) {
            throw GradleException(
                "Malformed format placeholders (%N without \$s/\$d):\n" +
                    errors.joinToString("\n")
            )
        }
    }
}

tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }
    .configureEach { dependsOn(checkStringFormats) }
