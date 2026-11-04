package com.seriousdev.caltracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.seriousdev.caltracker.data.ThemeMode
import com.seriousdev.caltracker.ui.AppRoot
import com.seriousdev.caltracker.ui.theme.CalTrackerTheme

class MainActivity : ComponentActivity() {
    @androidx.camera.core.ExperimentalGetImage
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CalTrackerApp
        setContent {
            val container by app.containerFlow.collectAsStateWithLifecycle()
            val first = remember { container }
            // Container was replaced (import swapped the DB file): rebuild every
            // ViewModel by recreating the activity; nav state survives via keys.
            LaunchedEffect(container) {
                if (container != null && container !== first) recreate()
            }
            container?.let { current ->
                val themeMode by current.settingsStore.themeModeFlow.collectAsStateWithLifecycle(
                    initialValue = ThemeMode.SYSTEM,
                )
                CalTrackerTheme(themeMode = themeMode) {
                    CompositionLocalProvider(LocalAppContainer provides current) {
                        AppRoot()
                    }
                }
            }
        }
    }
}
