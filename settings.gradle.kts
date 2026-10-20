pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        /*
         * 火狐内核（GeckoView）的家（2026-10-14）：父亲定的——系统网页视图停在
         * Chromium 90，B站这类重页面渲染慢，改用打进应用里的火狐内核，版本自己说了算。
         * 只有这一个仓库能有 GeckoView，别处拉不到。
         */
    }
}

rootProject.name = "B0BEmbyVR"
include(":app")
