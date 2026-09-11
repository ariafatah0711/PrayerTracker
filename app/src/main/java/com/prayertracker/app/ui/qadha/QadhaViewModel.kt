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
    val isLoading: Boolean = true
)

class QadhaViewModel(
    private val getQadhaListUseCase: GetQadhaListUseCase,
    private val performQadhaUseCase: PerformQadhaUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(QadhaUiState())
    val uiState: StateFlow<QadhaUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
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

    fun onConfirmQadha(prayerRecordId: String, notes: String? = null) {
        viewModelScope.launch {
            performQadhaUseCase(prayerRecordId, notes)
            _uiState.update { it.copy(selectedItemForConfirmation = null) }
        }
    }

    class Factory(
        private val getQadhaListUseCase: GetQadhaListUseCase,
        private val performQadhaUseCase: PerformQadhaUseCase
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QadhaViewModel(getQadhaListUseCase, performQadhaUseCase) as T
        }
    }
}
