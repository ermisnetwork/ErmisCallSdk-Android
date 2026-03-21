# ErmisCallSdk-Android
# ErmisCallSdk-Android Integration

### 1. Thêm Repository
Mở file `settings.gradle` (hoặc `build.gradle` gốc) và thêm cấu hình **JitPack** vào danh sách repositories:

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url '[https://jitpack.io](https://jitpack.io)' }
    }
}
```
### 2. Thêm Dependency
Thêm thư viện vào file `build.gradle` của module app::

```gradle
dependencies {
    // Thay 'Tag' bằng phiên bản mới nhất (ví dụ: 1.0.9)
    implementation 'com.github.ermisnetwork:ErmisCallSdk-Android:109'
}
```
