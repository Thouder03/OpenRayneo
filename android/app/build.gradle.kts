import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openrayneo.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.openrayneo.android"
        minSdk = 33
        targetSdk = 36
        versionCode = 39
        versionName = "1.2.0"
        ndk { abiFilters += providers.gradleProperty("openrayneo.abis").orElse("arm64-v8a,x86_64").get().split(',') }
    }
    buildTypes {
        getByName("release") {
            // Signed after assembly by signRelease with the rotated OpenRayneo release key.
            signingConfig = null
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "onnx" }
    sourceSets.getByName("main").assets.srcDir("../../ThirdParty/SherpaOnnx")
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/glasses-model"))
    sourceSets.getByName("test").resources.srcDir("../../Tests/Fixtures")
}

// The live 3D glasses load the shared model from assets/models; only the glb ships in the APK.
val glassesModel by tasks.registering(Sync::class) {
    from(rootProject.file("../assets/models/rayneo-io.glb"))
    into(layout.buildDirectory.dir("generated/glasses-model/models"))
}

fun downloadVoiceFile(targetUrl: String, destFile: File) {
    if (destFile.exists() && destFile.length() > 0L) return
    val parent = destFile.parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }
    val partFile = File(destFile.parentFile, "${destFile.name}.part")
    if (partFile.exists()) partFile.delete()

    val customMirror = System.getenv("OPENRAYNEO_VOICE_MIRROR")
    val mirrors = mutableListOf<String>()
    if (!customMirror.isNullOrEmpty()) {
        mirrors.add(customMirror)
    }
    mirrors.addAll(listOf("", "https://ghproxy.net/", "https://gh-proxy.com/", "https://mirror.ghproxy.com/", "https://hub.gitmirror.com/"))

    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    var success = false
    var lastError: Exception? = null

    for (mirror in mirrors) {
        val urlString = if (mirror.isNotEmpty()) {
            if (targetUrl.startsWith("https://github.com/")) {
                "${mirror}${targetUrl}"
            } else {
                continue
            }
        } else {
            targetUrl
        }

        println("Downloading ${destFile.name} from $urlString ...")
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(urlString))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(partFile.toPath()))
            if (response.statusCode() in 200..299 && partFile.exists() && partFile.length() > 0L) {
                if (destFile.exists()) destFile.delete()
                try {
                    Files.move(
                        partFile.toPath(),
                        destFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    partFile.copyTo(destFile, overwrite = true)
                    partFile.delete()
                }
                success = true
                break
            } else {
                if (partFile.exists()) partFile.delete()
            }
        } catch (e: Exception) {
            println("Failed downloading from $urlString: ${e.message}")
            lastError = e
            if (partFile.exists()) partFile.delete()
        }
    }

    if (!success) {
        throw GradleException("Failed to download $targetUrl from all sources. Last error: ${lastError?.message}")
    }
}

val prepareVoiceModels by tasks.registering {
    val voiceLibsDir = file("libs")
    val voiceAssetsDir = file("src/main/assets/voice-models")
    val aarFile = file("libs/sherpa-onnx-2.0.1.aar")
    val campplusFile = file("src/main/assets/voice-models/campplus-zh-16k.onnx")
    val sileroFile = file("src/main/assets/voice-models/silero-vad.onnx")
    val segmentationFile = file("src/main/assets/voice-models/segmentation.int8.onnx")

    outputs.files(aarFile, campplusFile, sileroFile, segmentationFile)

    doLast {
        voiceLibsDir.mkdirs()
        voiceAssetsDir.mkdirs()

        val modelsOnly = providers.environmentVariable("OPENRAYNEO_VOICE_MODELS_ONLY").orElse("0").get() == "1"
        if (!modelsOnly) {
            downloadVoiceFile(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar",
                aarFile
            )
        }

        downloadVoiceFile(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx",
            campplusFile
        )

        downloadVoiceFile(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            sileroFile
        )

        if (!segmentationFile.exists() || segmentationFile.length() == 0L) {
            val voiceCacheDir = rootProject.file(".build/android-voice-models")
            voiceCacheDir.mkdirs()
            val segmentationTarBz2 = File(voiceCacheDir, "segmentation.tar.bz2")
            downloadVoiceFile(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
                segmentationTarBz2
            )

            project.copy {
                from(tarTree(resources.bzip2(segmentationTarBz2))) {
                    include("**/model.int8.onnx")
                    eachFile {
                        path = "segmentation.int8.onnx"
                    }
                }
                into(voiceAssetsDir)
                includeEmptyDirs = false
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(prepareVoiceModels, glassesModel) }

// Release APKs are signed with the OpenRayneo release key plus a v3 rotation lineage from the original debug key,
// so phones holding debug-signed installs upgrade in place. Key, lineage and the Keychain password stay outside the repo.
val signRelease by tasks.registering(Exec::class) {
    onlyIf {
        org.gradle.internal.os.OperatingSystem.current().isMacOsX
    }
    val keys = File(System.getProperty("user.home"), ".openrayneo-signing")
    val apks = layout.buildDirectory.dir("outputs/apk/release").get().asFile
    val apksigner = "${android.sdkDirectory}/build-tools/${android.buildToolsVersion}/apksigner"
    commandLine("sh", "-c", """
        set -eu
        OPENRAYNEO_RELEASE_PASS=${'$'}(security find-generic-password -s openrayneo-android-release -w)
        export OPENRAYNEO_RELEASE_PASS
        "$apksigner" sign --ks "$keys/openrayneo-release.jks" --ks-key-alias openrayneo --ks-pass env:OPENRAYNEO_RELEASE_PASS \
            --min-sdk-version 33 --v1-signing-enabled false --v2-signing-enabled false --lineage "$keys/rotation.lineage" --out "$apks/app-release.apk" "$apks/app-release-unsigned.apk"
        "$apksigner" verify "$apks/app-release.apk"
    """.trimIndent())
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(signRelease) }

dependencies {
    implementation(fileTree("libs") { include("*.aar", "*.jar") })
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.filament:filament-android:1.75.1")
    implementation("com.google.android.filament:gltfio-android:1.75.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
