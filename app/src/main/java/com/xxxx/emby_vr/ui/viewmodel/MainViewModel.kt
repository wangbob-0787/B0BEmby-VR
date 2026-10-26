package com.xxxx.emby_vr.ui.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xxxx.emby_vr.data.local.PreferencesManager
import com.xxxx.emby_vr.data.remote.UpdateApi
import com.xxxx.emby_vr.data.update.ApkUpdater
import com.xxxx.emby_vr.data.repository.EmbyRepository
import com.xxxx.emby_vr.util.ErrorHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局状态管理 ViewModel
 * 
 * 职责：
 * - 主题管理
 * - 全局登录状态观察
 * - 应用初始化
 * - 版本检查
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)
    private val repository = EmbyRepository.getInstance(application)

    // === 主题 ===
    var currentThemeId by mutableStateOf("dark")
        private set

    // === 登录状态（从 Repository 观察）===
    val isLoggedIn: Boolean get() = repository.isLoggedIn
    val isLoaded: Boolean get() = repository.isLoaded

    // === 版本与更新（父亲 2026-10-26：只做「检查更新」，点版本号才更新）===
    var currentVersion by mutableStateOf("")
        private set

    /** 更新流程所处阶段；界面只按它显示，不各自推断 */
    var updatePhase by mutableStateOf(UpdatePhase.Idle)
        private set

    /** 下载百分比（0~100），只在 Downloading 时有意义 */
    var updateProgress by mutableStateOf(0)
        private set

    /** 失败/取消后跟在版本号后面的那句话；空表示没有话要说 */
    var updateNote by mutableStateOf("")
        private set

    /** 有新版待处理 —— 界面据此决定版本号能不能点 */
    val needUpdate: Boolean
        get() = updatePhase != UpdatePhase.Idle

    private var manifest: UpdateApi.Manifest? = null

    /** 自身编译流水号（`versionName` 末段），与清单里的 run 比大小 */
    private var selfRun: Int = 0

    init {
        loadThemeId()
        loadCurrentVersion()
    }

    /**
     * 初始化应用（加载凭证）
     */
    fun initialize(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            repository.loadCredentials()
            onComplete()
        }
    }

    /**
     * 保存主题 ID
     */
    fun saveThemeId(themeId: String) {
        prefs.themeId = themeId
        currentThemeId = themeId
    }

    /**
     * 加载主题 ID
     */
    private fun loadThemeId() {
        currentThemeId = prefs.themeId
    }

    /**
     * 加载当前版本号
     */
    private fun loadCurrentVersion() {
        val context = getApplication<Application>().applicationContext
        var runNumber = 0
        currentVersion = try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val name = packageInfo.versionName ?: ""
            runNumber = name.substringAfterLast('.').toIntOrNull() ?: 0
            "v$name"
        } catch (e: Exception) {
            ""
        }
        selfRun = runNumber
    }

    /**
     * 问一次内网更新源；有新版只把状态置成「有新版本」，界面在版本号后面加括号。
     *
     * 走不通就当作没有新版（家里网络抖动、.15 没开服务都不该在启动路径上报错）。
     */
    fun checkUpdate() {
        if (updatePhase != UpdatePhase.Idle) return
        viewModelScope.launch {
            val m = UpdateApi.fetchManifest() ?: return@launch
            manifest = m
            updateNote = ""
            updatePhase = if (selfRun > 0 && m.run > selfRun) UpdatePhase.Available
            else UpdatePhase.Idle
        }
    }

    /**
     * 点版本号。
     *
     * 只有「有新版本」「上次失败」才真的开始下载；下载中再点一次是取消；
     * 安装阶段不响应 —— 那时包已经交给系统安装器，进程随时会被结束。
     */
    fun onVersionClick() {
        when (updatePhase) {
            UpdatePhase.Available, UpdatePhase.Failed -> startDownload()
            UpdatePhase.Downloading -> cancelDownload()
            UpdatePhase.Idle, UpdatePhase.Installing -> Unit
        }
    }

    private fun startDownload() {
        val m = manifest ?: return
        val context = getApplication<Application>().applicationContext
        updateNote = ""
        updateProgress = 0
        updatePhase = UpdatePhase.Downloading
        ApkUpdater.download(
            context = context,
            url = m.apkUrl,
            onProgress = { p -> viewModelScope.launch { updateProgress = p } },
            onDone = { file ->
                viewModelScope.launch {
                    updatePhase = UpdatePhase.Installing
                    val err = ApkUpdater.install(context, file)
                    if (err != null) {
                        updatePhase = UpdatePhase.Failed
                        updateNote = "更新失败，请重试"
                        Log.w("MainViewModel", "调起安装器失败：$err")
                    }
                }
            },
            onError = { msg ->
                viewModelScope.launch {
                    updatePhase = UpdatePhase.Failed
                    updateNote = if (msg == "已取消") "已取消更新" else "更新失败，请重试"
                }
            }
        )
    }

    private fun cancelDownload() {
        ApkUpdater.cancel()
        updatePhase = UpdatePhase.Failed
        updateNote = "已取消更新"
    }

    /**
     * 退出登录
     */
    fun logout() {
        repository.logout()
    }
}

// 扩展函数
private fun com.google.gson.JsonObject.safeGetString(key: String): String? {
    return try {
        this[key]?.asString
    } catch (e: Exception) {
        null
    }
}

/**
 * 更新流程的阶段。
 *
 * Idle 表示"没有待装的新版本"，此时版本号只是一行说明文字、点不动；
 * 其余阶段都算"有新版本这件事还没收场"，界面据此保持可操作或提示。
 */
enum class UpdatePhase { Idle, Available, Downloading, Installing, Failed }
