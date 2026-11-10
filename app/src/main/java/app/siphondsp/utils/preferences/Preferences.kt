package app.siphondsp.utils.preferences

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.siphondsp.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.reflect.KClass
import androidx.datastore.preferences.core.Preferences as StoredPreferences

/**
 * The app's settings ([App]) and its runtime flags ([Var]), each kept in a Preferences DataStore
 * (`files/datastore/<namespace>.preferences_pb`). The first launch after the move copies the old
 * SharedPreferences file of the same name in and deletes it.
 */
class Preferences(val context: Context) {

    inner class App : AbstractPreferences(context) {
        override fun namespace() = Constants.PREF_APP
    }

    inner class Var : AbstractPreferences(context) {
        override fun namespace() = Constants.PREF_VAR
    }

    /**
     * Synchronous reads and writes over a DataStore, as SharedPreferences had: an in-memory copy,
     * loaded once, answers every read; a write changes it at once and goes to disk in order
     * behind it (or before returning, for `async = false`). This process is the store's only
     * writer, so the copy never falls behind the file. Change listeners run on the main thread.
     */
    abstract class AbstractPreferences(val context: Context) {
        abstract fun namespace(): String

        private val store: NamespaceStore by lazy { NamespaceStore.forName(context.applicationContext, namespace()) }

        private val defaultCache: HashMap<String, Any> = hashMapOf()

        /**
         * @remarks This function takes a StringRes pointing to a preference key.
         *          There MUST be a default value with the same key name in defaults.xml,
         *          otherwise an exception will be thrown when loading the default value.
         *
         * @return Returns the current value of the preference or the default value if none is set
         */
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> get(@StringRes nameRes: Int, default: T? = null, type: KClass<T>): T {
            val key = context.getString(nameRes)
            val stored = store.read(storedKey(key, type))
            return stored ?: (default ?: getDefault(nameRes, type))
        }

        inline fun <reified T : Any> get(@StringRes nameRes: Int): T {
            return get<T>(nameRes, null, T::class)
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> getDefault(@StringRes nameRes: Int, type: KClass<T>): T {
            val key = context.getString(nameRes)
            return if(defaultCache.containsKey(key)) {
                defaultCache[key] as T
            }
            else {
                @SuppressLint("DiscouragedApi")
                val defaultRes = context.resources.getIdentifier(
                    "default_$key",
                    when(type)
                    {
                        Boolean::class -> "bool"
                        String::class -> "string"
                        Int::class -> "integer"
                        Long::class -> "integer"
                        Float::class -> "integer"
                        else -> throw IllegalArgumentException("Unknown type ${type.qualifiedName}")
                    },
                    context.packageName
                )

                if(defaultRes == 0) {
                    throw IllegalStateException("Preference key '$key' has no default set")
                }

                (when(type) {
                    Boolean::class -> context.resources.getBoolean(defaultRes)
                    String::class -> context.resources.getString(defaultRes)
                    Int::class -> context.resources.getInteger(defaultRes)
                    Long::class -> context.resources.getInteger(defaultRes).toLong()
                    Float::class -> context.resources.getInteger(defaultRes).toFloat()
                    else -> throw IllegalArgumentException("Unknown type")
                } as T).also {
                    defaultCache[key] = it as Any
                }
            }
        }

        inline fun <reified T : Any> getDefault(@StringRes nameRes: Int): T {
            return getDefault(nameRes, T::class)
        }

        fun <T : Any> reset(@StringRes nameRes: Int, async: Boolean = true, type: KClass<T>) {
            set(nameRes, getDefault(nameRes, type), async, type)
        }

        inline fun <reified T : Any> reset(@StringRes nameRes: Int, async: Boolean = true) {
            return reset(nameRes, async, T::class)
        }

        fun <T : Any> set(@StringRes nameRes: Int, value: T, async: Boolean = true, type: KClass<T>) {
            val key = context.getString(nameRes)
            store.write(storedKey(key, type), value, key, async)
        }

        inline fun <reified T : Any> set(@StringRes nameRes: Int, value: T, async: Boolean = true) {
            set(nameRes, value, async, T::class)
        }

        /**
         * [listener] gets the changed key (its SharedPreferences argument is always null). Held
         * strongly, unlike SharedPreferences' listeners: unregister it.
         */
        open fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            listener?.let(store.listeners::add)
        }

        open fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            listener?.let(store.listeners::remove)
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> storedKey(key: String, type: KClass<T>): StoredPreferences.Key<T> = when (type) {
            Boolean::class -> booleanPreferencesKey(key)
            String::class -> stringPreferencesKey(key)
            Int::class -> intPreferencesKey(key)
            Long::class -> longPreferencesKey(key)
            Float::class -> floatPreferencesKey(key)
            else -> throw IllegalArgumentException("Unknown type ${type.qualifiedName}")
        } as StoredPreferences.Key<T>
    }
}

/** One namespace's DataStore and its in-memory copy; one per file per process, as DataStore requires. */
internal class NamespaceStore(context: Context, name: String) {
    // One writer at a time, in call order: DataStore applies the edits in the order they arrive.
    @Suppress("OPT_IN_USAGE")
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val dataStore: DataStore<StoredPreferences> = PreferenceDataStoreFactory.create(
        migrations = listOf(SharedPreferencesMigration(context, name)),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { context.preferencesDataStoreFile(name) },
    )

    // Loaded on first use (the migration runs then), like SharedPreferences' first read.
    @Volatile
    private var snapshot: StoredPreferences = runBlocking { dataStore.data.first() }
    private val lock = Any()

    val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun <T : Any> read(key: StoredPreferences.Key<T>): T? = snapshot[key]

    fun <T : Any> write(key: StoredPreferences.Key<T>, value: T, name: String, async: Boolean) {
        synchronized(lock) {
            // As SharedPreferences: writing the value already there changes nothing and tells no one.
            if (snapshot[key] == value) return
            snapshot = snapshot.toMutablePreferences().apply { this[key] = value }.toPreferences()
        }
        // Through the one writer either way, so a synchronous write can't overtake a queued one.
        val job = writeScope.launch {
            try {
                dataStore.edit { it[key] = value }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save setting $name")
            }
        }
        if (!async) runBlocking { job.join() }
        mainHandler.post { listeners.forEach { it.onSharedPreferenceChanged(null, name) } }
    }

    companion object {
        private val stores = HashMap<String, NamespaceStore>()

        /** Tests only: forget the stores, so the next use opens the (new) test app's files. */
        @VisibleForTesting
        fun clearForTest() = synchronized(stores) { stores.clear() }

        fun forName(context: Context, name: String): NamespaceStore = synchronized(stores) {
            stores.getOrPut(name) { NamespaceStore(context, name) }
        }
    }
}
