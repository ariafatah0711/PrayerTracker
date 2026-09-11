package com.prayertracker.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.domain.model.PrayerItem
import com.prayertracker.app.domain.usecase.GetHistoryUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class HistoryFilter(val displayName: String) {
    ALL("Semua"),
    COMPLETED("Selesai"),
    MISSED("Terlewat"),
    QADHA_COMPLETED("Sudah Qadha")
}

data class HistoryUiState(
    val prayers: List<PrayerItem> = emptyList(),
    val filteredPrayers: List<PrayerItem> = emptyList(),
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val isLoading: Boolean = true
)

class HistoryViewModel(
    private val getHistoryUseCase: GetHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            getHistoryUseCase().collectLatest { list ->
                _uiState.update { current ->
                    current.copy(
                        prayers = list,
                        filteredPrayers = applyFilter(list, current.selectedFilter),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun onFilterSelected(filter: HistoryFilter) {
        _uiState.update { current ->
            current.copy(
                selectedFilter = filter,
                filteredPrayers = applyFilter(current.prayers, filter)
            )
        }
    }

    private fun applyFilter(list: List<PrayerItem>, filter: HistoryFilter): List<PrayerItem> {
        return when (filter) {
            HistoryFilter.ALL -> list
            HistoryFilter.COMPLETED -> list.filter { it.status == PrayerStatus.COMPLETED }
            HistoryFilter.MISSED -> list.filter { it.status == PrayerStatus.MISSED }
            HistoryFilter.QADHA_COMPLETED -> list.filter { it.status == PrayerStatus.QADHA_COMPLETED }
        }
    }

    class Factory(private val getHistoryUseCase: GetHistoryUseCase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(getHistoryUseCase) as T
        }
    }
}
