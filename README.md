# ErmisCallSdk-Android
1. Thêm Repository
Mở file settings.gradle (hoặc build.gradle gốc) và thêm JitPack vào danh sách repositories:

Gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' } // Thêm dòng này
    }
}
2. Thêm Dependency
Thêm thư viện vào file build.gradle của module app:

Gradle
dependencies {
    // Thay 'Tag' bằng phiên bản mới nhất (ví dụ: 1.0.9)
    implementation 'com.github.ermisnetwork:ErmisCallSdk-Android:109'
}
