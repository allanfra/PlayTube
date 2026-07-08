/*
 * PlayTube Project Original (2026)
 * arslandaim-hub (GitHub.com/arslandaim-hub)
 * Licenced Under GPL-3.0+
*/
package com.arslandaim.playtube.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arslandaim.playtube.data.local.FavoriteEntity
import com.arslandaim.playtube.data.local.SearchHistoryDao
import com.arslandaim.playtube.domain.model.StreamBundle
import com.arslandaim.playtube.domain.model.VideoItem
import com.arslandaim.playtube.domain.repository.LibraryRepository
import com.arslandaim.playtube.domain.repository.SearchRepository
import com.arslandaim.playtube.domain.repository.VideoRepository
import com.arslandaim.playtube.domain.usecase.DownloadVideoUseCase
import com.arslandaim.playtube.domain.usecase.GetVideoStreamsUseCase
import com.arslandaim.playtube.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val libraryRepository: LibraryRepository,
    private val videoRepository: VideoRepository,
    private val searchHistoryDao: SearchHistoryDao,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val getVideoStreamsUseCase: GetVideoStreamsUseCase,
    private val downloadVideoUseCase: DownloadVideoUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeState())
    val uiState: StateFlow<HomeState> = _uiState.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0: For You, 1: Subscriptions
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    // Download Dialog States
    private val _downloadState = MutableStateFlow<DownloadDialogState>(DownloadDialogState.Idle)
    val downloadState: StateFlow<DownloadDialogState> = _downloadState.asStateFlow()

    private val categoryCache = mutableMapOf<String, List<VideoItem>>()
    private var trendingFetchJob: Job? = null

    init {
        loadTrending()
    }

    fun onTabSelected(index: Int) {
        _selectedTab.value = index
        if (index == 0 && _uiState.value.trendingVideos.isEmpty()) {
            loadTrending()
        } else if (index == 1 && _uiState.value.subscriptionVideos.isEmpty()) {
            loadSubscriptionsFeed()
        }
    }

    fun onCategorySelected(category: String) {
        if (_selectedCategory.value == category) return
        _selectedCategory.value = category
        
        // Instant UI update if cached
        categoryCache[category]?.let { cachedVideos ->
            _uiState.value = _uiState.value.copy(
                trendingVideos = cachedVideos,
                isTrendingLoading = false,
                isPersonalized = category == "All" && _uiState.value.isPersonalized,
                error = null
            )
            return 
        }

        loadTrending()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            if (_selectedTab.value == 0) {
                // Clear cache on manual refresh to get fresh data
                categoryCache.clear()
                fetchTrending()
            } else {
                fetchSubscriptionsFeed()
            }
            _isRefreshing.value = false
        }
    }

    fun loadTrending() {
        trendingFetchJob?.cancel()
        trendingFetchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTrendingLoading = true, error = null)
            fetchTrending()
            _uiState.value = _uiState.value.copy(isTrendingLoading = false)
        }
    }

    private suspend fun fetchTrending() {
        try {
            val history = searchHistoryDao.getAllSearchHistory().first()
            val category = _selectedCategory.value
            
            val trendingVideos = if (category != "All") {
                searchRepository.search(category)
            } else {
                coroutineScope {
                    val topics = if (history.isNotEmpty()) {
                        // Blend history topics with general ones
                        val topQueries = history.map { it.query }.distinct().take(2)
                        (topQueries + listOf("trending", "music", "gaming", "news")).distinct()
                    } else {
                        // Richer initial list for new installs
                        listOf("trending", "music", "gaming", "news", "movies", "tech")
                    }

                    val deferredResults = topics.map { topic ->
                        async { 
                            try {
                                searchRepository.search(topic).take(15)
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }
                    
                    deferredResults.awaitAll().flatten().distinctBy { it.id }.shuffled()
                }
            }
            
            // Update cache
            categoryCache[category] = trendingVideos

            _uiState.value = _uiState.value.copy(
                trendingVideos = trendingVideos,
                isPersonalized = category == "All" && history.isNotEmpty()
            )
        } catch (e: Exception) {
            val errorMessage = if (e is java.net.UnknownHostException || e is java.io.IOException) {
                "No internet connection"
            } else {
                e.message ?: "Unknown error"
            }
            _uiState.value = _uiState.value.copy(
                error = errorMessage
            )
        }
    }

    fun loadSubscriptionsFeed() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubscriptionsLoading = true, error = null)
            fetchSubscriptionsFeed()
            _uiState.value = _uiState.value.copy(isSubscriptionsLoading = false)
        }
    }

    private suspend fun fetchSubscriptionsFeed() {
        try {
            val subscriptions = libraryRepository.getSubscriptions().first()
            if (subscriptions.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    subscriptionVideos = emptyList()
                )
                return
            }

            val allVideos = mutableListOf<VideoItem>()
            subscriptions.take(15).forEach { sub ->
                try {
                    val details = videoRepository.getChannelDetails(sub.channelId)
                    allVideos.addAll(details.videos)
                } catch (e: Exception) {
                    // Skip failed channel fetches
                }
            }
            
            _uiState.value = _uiState.value.copy(
                subscriptionVideos = allVideos.shuffled().take(30)
            )
        } catch (e: Exception) {
            val errorMessage = if (e is java.net.UnknownHostException || e is java.io.IOException) {
                "No internet connection"
            } else {
                e.message ?: "Unknown error"
            }
            _uiState.value = _uiState.value.copy(
                error = errorMessage
            )
        }
    }

    fun toggleFavorite(video: VideoItem) {
        viewModelScope.launch {
            val isFavorite = libraryRepository.isFavorite(video.id).first()
            toggleFavoriteUseCase(
                FavoriteEntity(
                    videoId = video.id,
                    title = video.title,
                    thumbnailUrl = video.thumbnailUrl,
                    uploaderName = video.uploaderName
                )
            )
            _snackbarMessage.emit(if (isFavorite) "Removed from Favorites" else "Added to Favorites")
        }
    }

    fun prepareDownload(video: VideoItem) {
        viewModelScope.launch {
            _downloadState.value = DownloadDialogState.Loading(video)
            getVideoStreamsUseCase(video.id)
                .onSuccess { bundle ->
                    _downloadState.value = DownloadDialogState.ShowDialog(video, bundle)
                }
                .onFailure { error ->
                    _downloadState.value = DownloadDialogState.Idle
                }
        }
    }

    fun download(video: VideoItem, bundle: StreamBundle, url: String?, quality: String?, format: String?, isAdaptive: Boolean) {
        viewModelScope.launch {
            val audioUrl = if (isAdaptive) {
                val isWebm = format?.contains("webm", ignoreCase = true) == true
                val compatibleStreams = bundle.audioStreams.filter { audio ->
                    if (isWebm) {
                        audio.format.contains("webm", ignoreCase = true) || 
                        audio.format.contains("opus", ignoreCase = true)
                    } else {
                        audio.format.contains("m4a", ignoreCase = true) || 
                        audio.format.contains("aac", ignoreCase = true)
                    }
                }

                compatibleStreams.filter { it.trackType == "ORIGINAL" }
                    .maxByOrNull { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                    ?.url ?: compatibleStreams.maxByOrNull { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }?.url
            } else null

            downloadVideoUseCase(
                videoId = video.id,
                url = url,
                title = video.title,
                thumbnailUrl = video.thumbnailUrl,
                uploaderName = video.uploaderName,
                quality = quality,
                format = format,
                audioUrl = audioUrl
            )
            _snackbarMessage.emit("Downloading started")
            _downloadState.value = DownloadDialogState.Idle
        }
    }

    fun dismissDownloadDialog() {
        _downloadState.value = DownloadDialogState.Idle
    }
}

sealed class DownloadDialogState {
    object Idle : DownloadDialogState()
    data class Loading(val video: VideoItem) : DownloadDialogState()
    data class ShowDialog(val video: VideoItem, val bundle: StreamBundle) : DownloadDialogState()
}

data class HomeState(
    val trendingVideos: List<VideoItem> = emptyList(),
    val subscriptionVideos: List<VideoItem> = emptyList(),
    val isTrendingLoading: Boolean = false,
    val isSubscriptionsLoading: Boolean = false,
    val isPersonalized: Boolean = false,
    val error: String? = null
)
