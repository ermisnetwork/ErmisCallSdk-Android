plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "network.ermis.call"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        viewBinding = true
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

    // Ensure jniLibs are packaged
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(libs.coil)
    implementation(libs.permissionx)
    implementation(libs.okhttp)
//    implementation(libs.draggableview)
    implementation(libs.stream.log)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    api("net.java.dev.jna:jna:5.18.1@aar")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
            }
            groupId = "com.github.ermisnetwork"
            artifactId = "ermis-call-ui"
            version = "1.0.7"

            pom.withXml {
                val dependenciesNode = asNode().get("dependencies") as? groovy.util.Node
                    ?: asNode().appendNode("dependencies")

                // Thêm JNA vào POM một cách an toàn
                val dependency = dependenciesNode.appendNode("dependency")
                dependency.appendNode("groupId", "net.java.dev.jna")
                dependency.appendNode("artifactId", "jna")
                dependency.appendNode("version", "5.16.0")
                dependency.appendNode("scope", "compile")
                dependency.appendNode("type", "aar")
            }
        }
    }
}
