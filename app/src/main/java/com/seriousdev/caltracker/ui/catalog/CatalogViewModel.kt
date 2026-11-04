package com.seriousdev.caltracker.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seriousdev.caltracker.data.ProductRepository
import com.seriousdev.caltracker.domain.Product
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class CatalogViewModel(private val productRepository: ProductRepository) : ViewModel() {

    private val query = MutableStateFlow("")
    val searchQuery = query

    /** Catalog list honoring the search query. */
    val products = query.map { it.trim() }
        .debounce(150)
        .distinctUntilChanged()
        .flatMapLatest { trimmed ->
            if (trimmed.isNotBlank()) productRepository.search(trimmed) else productRepository.observeAll()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customProducts = productRepository.observeUserContent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteCustom(product: Product) {
        viewModelScope.launch { productRepository.deleteCustom(product) }
    }

    fun setFavorite(product: Product, favorite: Boolean) {
        viewModelScope.launch { productRepository.setFavorite(product.id, favorite) }
    }
}
