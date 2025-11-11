import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

// Library version - update this for each release
group = "com.github.rizukirr"
version = "1.0.0"

android {
    namespace = "com.android.audx"
    compileSdk = 36  // Fixed syntax error

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Add consumer ProGuard rules to protect JNI methods
        consumerProguardFiles("consumer-rules.pro")

        // AAR metadata
        aarMetadata {
            minCompileSdk = 24
        }

        // Only build for ABIs that have prebuilt libraries
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = false  // Not needed for library without UI
    }

    // Exclude examples from the published library
    // (Examples are now in root /examples directory, so they won't be included anyway)

    // Enable publishing with sources and javadoc
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // Core dependencies
    implementation(libs.androidx.core.ktx)

    // Kotlin Coroutines - required for suspend functions
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Test dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Maven publishing configuration for JitPack
// JitPack will automatically build and publish when you create a GitHub release
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.github.rizukirr"
                artifactId = "audx"
                version = project.version.toString()

                // Optional: Add POM metadata for better documentation
                pom {
                    name.set("Audx")
                    description.set("Real-time audio denoising library for Android with ARM NEON optimizations")
                    url.set("https://github.com/rizukirr/AudxAndroid")

                    licenses {
                        license {
                            name.set("BSD 3-Clause License")
                            url.set("https://opensource.org/licenses/BSD-3-Clause")
                        }
                    }
                }
            }
        }
    }
}
