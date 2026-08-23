package com.freeproxy.app

import android.app.Application
import com.freeproxy.app.data.ProxyRepository
import com.freeproxy.app.data.UserPreferences
import com.freeproxy.app.discover.ProxyDiscoverer
import com.freeproxy.app.net.OkHttpFactory
import com.freeproxy.app.net.SpeedTester
import com.freeproxy.app.net.ValidationTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FreeProxyApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val okhttp by lazy { OkHttpFactory.create(this) }
    val repository by lazy { ProxyRepository(this) }
    val userPrefs by lazy { UserPreferences(this) }
    val discoverer by lazy { ProxyDiscoverer(this, okhttp, repository) }
    val speedTester by lazy { SpeedTester(okhttp) }
    val validationTester by lazy { ValidationTester(okhttp) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 首次启动把内置数据源种子化到数据库（自定义源功能的基础）
        appScope.launch {
            runCatching { repository.seedBuiltinSourcesIfEmpty() }
        }
    }

    companion object {
        lateinit var instance: FreeProxyApp
            private set
    }
}
