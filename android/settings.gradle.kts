pluginManagement {
    repositories {
        // 国内网络如拉取缓慢，可把 google()/mavenCentral() 换成阿里云镜像：
        //   maven("https://maven.aliyun.com/repository/google")
        //   maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 高德地图 SDK（com.amap.api:*）发布在 Maven Central 上，无需额外仓库。
        // 国内网络如果拉取缓慢，可以换成阿里云镜像：
        //   maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "YiDiZhiYue"
include(":app")
