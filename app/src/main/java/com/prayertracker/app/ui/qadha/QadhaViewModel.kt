package com.prayertracker.app.ui.qadha

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prayertracker.app.domain.model.QadhaItem
import com.prayertracker.app.domain.usecase.GetQadhaListUseCase
import com.prayertracker.app.domain.usecase.PerformQadhaUseCase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class QadhaUiState(
    val missedPrayers: List<QadhaItem> = emptyList(),
    val selectedItemForConfirmation: QadhaItem? = null,
    val userMessage: String? = null,
    val isLoading: Boolean = true
)

class QadhaViewModel(
    private val getQadhaListUseCase: GetQadhaListUseCase,
    private val performQadhaUseCase: PerformQadhaUseCase,
    private val reconcileMissedPrayersUseCase: com.prayertracker.app.domain.usecase.ReconcileMissedPrayersUseCase? = null,
    private val syncCoordinator: com.prayertracker.app.core.sync.SyncCoordinator? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(QadhaUiState())
    val uiState: StateFlow<QadhaUiState> = _uiState.asStateFlow()

    private var qadhaJob: kotlinx.coroutines.Job? = null

    init {
        loadData()
    }

    fun loadData() {
        qadhaJob?.cancel()
        qadhaJob = viewModelScope.launch {
            try {
                reconcileMissedPrayersUseCase?.invoke()
            } catch (_: Exception) {}
            getQadhaListUseCase().collectLatest { list ->
                _uiState.update { it.copy(missedPrayers = list, isLoading = false) }
            }
        }
    }

    fun onQadhaButtonClicked(item: QadhaItem) {
        _uiState.update { it.copy(selectedItemForConfirmation = item) }
    }

    fun onDismissDialog() {
        _uiState.update { it.copy(selectedItemForConfirmation = null) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun onConfirmQadha(prayerRecordId: String, notes: String? = null) {
        viewModelScope.launch {
            val item = _uiState.value.selectedItemForConfirmation
            // Optimistic UI update: langsung hapus dialog dan item dari daftar tampilan
            _uiState.update { current ->
                current.copy(
                    selectedItemForConfirmation = null,
                    missedPrayers = current.missedPrayers.filterNot { it.prayerRecordId == prayerRecordId }
                )
            }
            val result = performQadhaUseCase(prayerRecordId, notes)
            if (result.isSuccess) {
                val prayerName = item?.effectiveDisplayName ?: "Salat"
                _uiState.update { it.copy(userMessage = "Alhamdulillah! $prayerName berhasil diqadha.") }
                // Beri tahu seluruh aplikasi (Beranda, Statistik, Riwayat) agar auto-refresh
                syncCoordinator?.notifyDataRefreshed()
            } else {
                _uiState.update { it.copy(userMessage = "Gagal mencatat qadha: ${result.exceptionOrNull()?.localizedMessage}") }
                loadData() // rollback
            }
        }
    }

    class Factory(
        private val getQadhaListUseCase: GetQadhaListUseCase,
        private val performQadhaUseCase: PerformQadhaUseCase,
        private val reconcileMissedPrayersUseCase: com.prayertracker.app.domain.usecase.ReconcileMissedPrayersUseCase? = null,
        private val syncCoordinator: com.prayertracker.app.core.sync.SyncCoordinator? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QadhaViewModel(getQadhaListUseCase, performQadhaUseCase, reconcileMissedPrayersUseCase, syncCoordinator) as T
        }
    }
}
