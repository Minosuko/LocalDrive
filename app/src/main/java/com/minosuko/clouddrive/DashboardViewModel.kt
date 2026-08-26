package com.minosuko.clouddrive

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

data class DashboardState(
    val loading: Boolean = true,
    val deviceStorage: StorageStats? = null,
    val cloudStorage: List<CloudStorageState> = emptyList(),
)

data class CloudStorageState(
    val drive: DriveProfile,
    val storage: StorageStats? = null,
    val error: String? = null,
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var refreshGeneration = 0L

    fun refresh(drives: List<DriveProfile>, force: Boolean = false) {
        loadJob?.cancel()
        val generation = ++refreshGeneration
        loadJob = viewModelScope.launch {
            val previous = mutableState.value.cloudStorage.associateBy { it.drive.id }
            mutableState.update { current ->
                current.copy(
                    loading = true,
                    cloudStorage = drives.map { drive -> previous[drive.id]?.copy(drive = drive, error = null) ?: CloudStorageState(drive) },
                )
            }
            coroutineScope {
                val jobs = drives.map { drive ->
                    launch(Dispatchers.IO) {
                        val cached = previous[drive.id]?.storage ?: AppSettings.cachedServerStorage(getApplication(), drive)
                        if (cached != null && generation == refreshGeneration) mutableState.update { current ->
                            current.copy(cloudStorage = current.cloudStorage.map {
                                if (it.drive.id == drive.id) it.copy(storage = cached) else it
                            })
                        }
                        val result = runCatching { davClient(getApplication(), drive).storageStats(force) }
                            .fold(
                                onSuccess = {
                                    AppSettings.saveServerStorage(getApplication(), drive, it)
                                    CloudStorageState(drive, storage = it)
                                },
                                onFailure = { CloudStorageState(drive, storage = cached, error = it.message ?: "Server unavailable") },
                            )
                        if (generation == refreshGeneration) mutableState.update { current ->
                            current.copy(cloudStorage = current.cloudStorage.map { if (it.drive.id == drive.id) result else it })
                        }
                    }
                }.toMutableList()
                jobs += launch(Dispatchers.IO) {
                    val device = deviceStorageStats(getApplication())
                    if (generation == refreshGeneration) mutableState.update { it.copy(deviceStorage = device) }
                }
                jobs.joinAll()
            }
            if (generation == refreshGeneration) mutableState.update { it.copy(loading = false) }
        }
    }
}
