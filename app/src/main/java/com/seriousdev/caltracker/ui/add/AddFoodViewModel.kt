package com.seriousdev.caltracker.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seriousdev.caltracker.data.DiaryRepository
import com.seriousdev.caltracker.data.ProductRepository
import com.seriousdev.caltracker.domain.MealType
import com.seriousdev.caltracker.domain.Product
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AddFoodViewModel(
    private val dayEpochDay: Long,
    private val productRepository: ProductRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    val searchQuery: MutableStateFlow<String> = query

    val results: StateFlow<List<Product>> = query
        .debounce(150)
        .distinctUntilChanged()
        .flatMapLatest { text ->
            if (text.isBlank()) productRepository.observeAll() else productRepository.search(text.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites: StateFlow<List<Product>> = productRepository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Products eaten during the last 30 days, most recent first. */
    val recent: StateFlow<List<Product>> =
        productRepository.observeRecentlyUsed(
            sinceDayEpochDay = com.seriousdev.caltracker.ui.format.todayEpochDay() - 30,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFavorite(productId: Long, favorite: Boolean) {
        viewModelScope.launch { productRepository.setFavorite(productId, favorite) }
    }

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    fun add(productId: Long, mealType: MealType, grams: Double) {
        viewModelScope.launch {
            diaryRepository.add(dayEpochDay, mealType, productId, grams)
            _saved.value = true
        }
    }
}
