package com.seriousdev.caltracker

import android.app.Application
import androidx.compose.runtime.compositionLocalOf
import com.seriousdev.caltracker.ui.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CalTrackerApp : Application() {

    private val containers = MutableStateFlow<AppContainer?>(null)

    /**
     * Current DI root. Swapped by [rebuildContainer] after a database-file
     * replacement (backup import); MainActivity recreates itself on change so
     * every ViewModel rebinds to the new repositories.
     */
    val containerFlow: StateFlow<AppContainer?> = containers

    override fun onCreate() {
        super.onCreate()
        containers.value = AppContainer(this)
    }

    /** Builds fresh repositories over the (already swapped-in) database file. */
    fun rebuildContainer() {
        // The dropped container's database stays open on purpose: queries still
        // in flight on old ViewModels would crash on a closed database. Their
        // fds point at the renamed inode and die with the process.
        containers.value = AppContainer(this)
    }
}

val LocalAppContainer = compositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
