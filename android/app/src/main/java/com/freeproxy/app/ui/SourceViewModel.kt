package com.freeproxy.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeproxy.app.FreeProxyApp
import com.freeproxy.app.data.ProxyRepository
import com.freeproxy.app.data.model.ProxySourceEntity
import com.freeproxy.app.discover.BuiltinSources
import com.freeproxy.app.discover.ProxySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SourceItem(
    val entity: ProxySourceEntity?,
    val source: ProxySource,
) {
    val isBuiltin: Boolean get() = entity == null
}

class SourceViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = getApplication<FreeProxyApp>()
    private val repo: ProxyRepository = appCtx.repository

    /** 入库源（含种子化内置）+ 未入库内置定义 合并展示，同 URL 入库优先 */
    val sources: StateFlow<List<SourceItem>> = combine(
        flowOf(BuiltinSources.default),
        repo.observeCustomSources(),
    ) { builtin, stored ->
        val storedUrls = stored.map { it.url }.toHashSet()
        val storedItems = stored.map { SourceItem(entity = it, source = it.toSource()) }
        val builtinItems = builtin
            .filterNot { it.url in storedUrls }
            .map { SourceItem(entity = null, source = it) }
        storedItems + builtinItems
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() {
        _toast.value = null
    }

    fun addSource(entity: ProxySourceEntity) = viewModelScope.launch {
        repo.addSourceEntity(entity)
        _toast.value = "src added: ${entity.name}"
    }

    fun updateSource(entity: ProxySourceEntity) = viewModelScope.launch {
        repo.updateSourceEntity(entity)
        _toast.value = "src saved: ${entity.name}"
    }

    fun deleteSource(entity: ProxySourceEntity) = viewModelScope.launch {
        repo.deleteSourceEntity(entity)
        _toast.value = "src deleted: ${entity.name}"
    }

    /**
     * 启停：入库源直接改；未入库内置源先落库再改（持久化用户对内置源的开关）
     */
    fun toggle(item: SourceItem, enabled: Boolean) = viewModelScope.launch {
        val e = item.entity
        if (e != null) {
            repo.setSourceEnabled(e.id, enabled)
        } else {
            repo.addSourceEntity(
                ProxySourceEntity.fromSource(item.source).copy(enabled = enabled)
            )
        }
    }

    /** 补齐缺失的内置源（保留用户已改/已停用的现状，仅恢复缺失项） */
    fun restoreBuiltin() = viewModelScope.launch {
        val added = repo.restoreBuiltinSources()
        _toast.value = if (added > 0) "builtin restored: $added" else "builtin complete"
    }
}
