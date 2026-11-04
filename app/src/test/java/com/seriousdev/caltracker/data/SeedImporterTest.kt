package com.seriousdev.caltracker.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeedImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var settingsStore: SettingsStore

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.Unconfined),
        ) { tempFolder.newFile("settings.preferences_pb") }
        settingsStore = SettingsStore(dataStore)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `import seeds catalog once and is idempotent`() = runTest {
        val importer = SeedImporter(context, db.productDao(), settingsStore)

        val first = importer.importIfNeeded()
        assertThat(first).isAtLeast(500)
        assertThat(db.productDao().count()).isEqualTo(first)

        val second = importer.importIfNeeded()
        assertThat(second).isEqualTo(0)
        assertThat(db.productDao().count()).isEqualTo(first)
    }

    @Test
    fun `imported products are searchable and not custom`() = runTest {
        SeedImporter(context, db.productDao(), settingsStore).importIfNeeded()

        val found = db.productDao().search("куриц").first()
        assertThat(found).isNotEmpty()
        assertThat(found.all { !it.isCustom }).isTrue()
    }
}
