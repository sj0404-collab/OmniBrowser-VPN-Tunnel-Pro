@file:Suppress("UnstableApiUsage")

val pkg: String = "com.winwasm64.wireguard"

plugins {
    id("com.android.library")
}

android {
    namespace = "${pkg}.tunnel"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 24
    }

    externalNativeBuild {
        cmake {
            path = file("tools/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    arguments("-DANDROID_PACKAGE_NAME=${pkg}")
                }
            }
        }
        debug {
            externalNativeBuild {
                cmake {
                    arguments("-DANDROID_PACKAGE_NAME=${pkg}.debug")
                }
            }
        }
    }

    lint {
        disable += "LongLogTag"
        disable += "NewApi"
    }

    testOptions.unitTests.all {
        it.testLogging {
            events("passed", "skipped", "failed")
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.collection:collection:1.6.0")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testImplementation("junit:junit:4.13.2")
}
