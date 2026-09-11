package com.prayertracker.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prayertracker.app.core.model.PrayerStatus
import com.prayertracker.app.domain.model.PrayerItem
import com.prayertracker.app.domain.usecase.GetHistoryUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class HistoryTimeRange(val displayName: String, val daysLimit: Int?) {
    WEEK("7 Hari", 7),
    MONTH("30 Hari", 30),
    ALL("Semua", null)
}

enum class HistoryFilter(val displayName: String) {
    ALL("Semua"),
    COMPLETED("Selesai"),
    MISSED("Terlewat"),
    QADHA_COMPLETED("Sudah Qadha")
}

data class FilterResult(
    val items: List<PrayerItem>,
    val totalDays: Int,
    val hasMore: Boolean
)

data class HistoryUiState(
    val prayers: List<PrayerItem> = emptyList(),
    val filteredPrayers: List<PrayerItem> = emptyList(),
    val selectedFilter: HistoryFilter = HistoryFilter.ALL,
    val selectedTimeRange: HistoryTimeRange = HistoryTimeRange.WEEK,
    val visibleDaysCount: Int = 7,
    val totalAvailableDays: Int = 0,
    val hasMoreDays: Boolean = false,
    val isLoading: Boolean = true
)

class HistoryViewModel(
    private val getHistoryUseCase: GetHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var historyJob: kotlinx.coroutines.Job? = null

    init {
        loadHistory()
    }

    fun loadHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            getHistoryUseCase().collectLatest { list ->
                _uiState.update { current ->
                    val result = applyFilterAndPagination(
                        prayers = list,
                        filter = current.selectedFilter,
                        timeRange = current.selectedTimeRange,
                        visibleDaysCount = current.visibleDaysCount
                    )
                    current.copy(
                        prayers = list,
                        filteredPrayers = result.items,
                        totalAvailableDays = result.totalDays,
                        hasMoreDays = result.hasMore,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun onFilterSelected(filter: HistoryFilter) {
        _uiState.update { current ->
            val result = applyFilterAndPagination(
                prayers = current.prayers,
                filter = filter,
                timeRange = current.selectedTimeRange,
                visibleDaysCount = current.visibleDaysCount
            )
            current.copy(
                selectedFilter = filter,
                filteredPrayers = result.items,
                totalAvailableDays = result.totalDays,
                hasMoreDays = result.hasMore
            )
        }
    }

    fun onTimeRangeSelected(range: HistoryTimeRange) {
        _uiState.update { current ->
            val initialDays = range.daysLimit ?: 14
            val result = applyFilterAndPagination(
                prayers = current.prayers,
                filter = current.selectedFilter,
                timeRange = range,
                visibleDaysCount = initialDays
            )
            current.copy(
                selectedTimeRange = range,
                visibleDaysCount = initialDays,
                filteredPrayers = result.items,
                totalAvailableDays = result.totalDays,
                hasMoreDays = result.hasMore
            )
        }
    }

    fun loadMoreDays() {
        _uiState.update { current ->
            val nextDays = current.visibleDaysCount + 7
            val result = applyFilterAndPagination(
                prayers = current.prayers,
                filter = current.selectedFilter,
                timeRange = current.selectedTimeRange,
                visibleDaysCount = nextDays
            )
            current.copy(
                visibleDaysCount = nextDays,
                filteredPrayers = result.items,
                totalAvailableDays = result.totalDays,
                hasMoreDays = result.hasMore
            )
        }
    }

    private fun applyFilterAndPagination(
        prayers: List<PrayerItem>,
        filter: HistoryFilter,
        timeRange: HistoryTimeRange,
        visibleDaysCount: Int
    ): FilterResult {
        val statusFiltered = when (filter) {
            HistoryFilter.ALL -> prayers
            HistoryFilter.COMPLETED -> prayers.filter { it.status == PrayerStatus.COMPLETED }
            HistoryFilter.MISSED -> prayers.filter { it.status == PrayerStatus.MISSED }
            HistoryFilter.QADHA_COMPLETED -> prayers.filter { it.status == PrayerStatus.QADHA_COMPLETED }
        }

        val distinctDates = statusFiltered.map { it.prayerDate }.distinct()
        val totalDays = distinctDates.size

        val allowedDates = when (timeRange) {
            HistoryTimeRange.WEEK -> distinctDates.take(7)
            HistoryTimeRange.MONTH -> distinctDates.take(30)
            HistoryTimeRange.ALL -> distinctDates.take(visibleDaysCount)
        }

        val hasMore = distinctDates.size > allowedDates.size
        val allowedDateSet = allowedDates.toSet()
        val items = statusFiltered.filter { it.prayerDate in allowedDateSet }

        return FilterResult(
            items = items,
            totalDays = totalDays,
            hasMore = hasMore
        )
    }

    class Factory(private val getHistoryUseCase: GetHistoryUseCase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HistoryViewModel(getHistoryUseCase) as T
        }
    }
}
