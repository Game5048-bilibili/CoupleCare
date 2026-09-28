// 顶层构建脚本：只声明插件版本，不实际应用（apply false）
// 各插件在 :app 模块中按需 apply。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    // Kotlin 2.0 起，Compose 编译器随 Kotlin 一起发布，必须使用这个独立插件，
    // 不能再写 composeOptions { kotlinCompilerExtensionVersion = ... }（已废弃）。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
