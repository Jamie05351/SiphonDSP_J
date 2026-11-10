package app.siphondsp

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.pluto.Pluto
import com.pluto.plugins.exceptions.PlutoExceptions
import com.pluto.plugins.exceptions.PlutoExceptionsPlugin
import com.pluto.plugins.logger.PlutoLoggerPlugin
import com.pluto.plugins.logger.PlutoTimberTree
import com.pluto.plugins.preferences.PlutoSharePreferencesPlugin
import com.pluto.plugins.rooms.db.PlutoRoomsDBWatcher
import com.pluto.plugins.rooms.db.PlutoRoomsDatabasePlugin
import fr.bipi.treessence.file.FileLoggerTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import app.siphondsp.model.room.AppBlocklistDatabase
import app.siphondsp.model.room.AppBlocklistRepository
import app.siphondsp.receiver.MasterVolumeReceiver
import app.siphondsp.session.dump.DumpManager
import app.siphondsp.utils.RoutingObserver
import app.siphondsp.utils.notifications.Notifications
import app.siphondsp.utils.preferences.Preferences
import app.siphondsp.utils.sdkAbove
import app.siphondsp.utils.storage.Cache
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext.startKoin
import org.koin.dsl.module
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.io.File


open class MainApplication : Application() {
    init {
        sdkAbove(Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    private val prefs: Preferences.App by inject()

    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }

    /* Rootless: Media projection auth token */
    var mediaProjectionStartIntent: Intent? = null

    override fun onCreate() {
        instance = this
        // A fresh process after a head-unit reset shows up in the audio health log, so a missing
        // "PIPELINE START" after this line means the engine was never started.
        app.siphondsp.service.AudioHealthLog.record(this, "APP PROCESS START")

        Timber.plant(DebugTree())

        if(BuildConfig.DEBUG) {
            Timber.plant(PlutoTimberTree())
            enableDebugTools()
        }

        // Clean up
        Cache.cleanup(this)

        try {
            Timber.plant(
                FileLoggerTree.Builder()
                    .withFileName("application.log")
                    .withDirName(this.cacheDir.absolutePath)
                    .withMinPriority(Log.VERBOSE)
                    .withSizeLimit(2 * 1000000)
                    .withFileLimit(1)
                    .appendToFile(false)
                    .build()
            )
        }
        catch (ex: Exception) {
            // Log file creation may fail
            Timber.e(ex)
        }

        Timber.i("====> Application starting up")

        val dumpFile = File(filesDir, "dump.txt")
        if(dumpFile.exists()) {
            dumpFile.delete()
        }

        Notifications.createChannels(this)

        val appModule = module {
            single { RoutingObserver(androidContext()) }
            single { DumpManager(androidContext()) }
            single { Preferences(androidContext()).App() }
            single { Preferences(androidContext()).Var() }
        }

        startKoin {
            androidLogger()
            androidContext(this@MainApplication)
            modules(appModule)
        }

        /**
         * Fix for existing users: reset mode selection to AudioService dump once.
         *
         * Reason: AudioPolicyService dumping (previously the default mode) cannot distinguish
         *         between input and output streams and causes false compat alerts when attaching
         *         to a input SID.
         */
        if(!prefs.get<Boolean>(R.string.key_reset_proc_mode_fix_applied)) {
            Timber.i("Applying service dump mode fix once")

            prefs.set(R.string.key_session_detection_method, DumpManager.Method.AudioService.value.toString())
            prefs.set(R.string.key_reset_proc_mode_fix_applied, true)
        }

        // Every screen is the fixed dark dashboard: the configuration says night, for the theme
        // attributes the graphs read and for the system dialogs.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // The DSP master volume from Tasker, for as long as this process runs: see MasterVolumeReceiver.
        // Never unregistered on purpose: it lives exactly as long as the process. Main process only,
        // so a component moved to its own process later can't make one press step twice.
        if (getProcessName() == packageName) {
            ContextCompat.registerReceiver(
                this, MasterVolumeReceiver(), IntentFilter(MasterVolumeReceiver.ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        super.onCreate()
    }

    override fun onLowMemory() {
        Timber.w("onLowMemory: Running low on memory")
        super.onLowMemory()
    }

    private fun enableDebugTools() {
        // Setup strict mode with death penalty
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .apply {
                    detectCustomSlowCalls()
                    detectNetwork()
                    detectResourceMismatches()
                    penaltyLog()
                    penaltyDeath()
                }
                .build()
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .apply {
                    detectLeakedRegistrationObjects()
                    detectCleartextNetwork()
                    detectActivityLeaks()
                    detectLeakedClosableObjects()
                    detectLeakedSqlLiteObjects()
                    detectContentUriWithoutPermission()
                    penaltyLog()
                    // penaltyDeath()
                }
                .build()
        )

        Pluto.Installer(this)
            .addPlugin(PlutoExceptionsPlugin())
            .addPlugin(PlutoLoggerPlugin())
            .addPlugin(PlutoSharePreferencesPlugin())
            .addPlugin(PlutoRoomsDatabasePlugin())
            .install()
        Pluto.showNotch(true)

        PlutoExceptions.setANRHandler { thread, exception ->
            Timber.e(exception, "unhandled ANR handled on thread: %s", thread.name)
        }

        PlutoRoomsDBWatcher.watch("blocked_apps.db", AppBlocklistDatabase::class.java)
    }

    companion object {
        lateinit var instance: MainApplication
            private set
    }
}
