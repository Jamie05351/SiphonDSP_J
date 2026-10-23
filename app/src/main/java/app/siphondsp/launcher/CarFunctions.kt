package app.siphondsp.launcher

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.widget.Toast
import androidx.annotation.StringRes
import app.siphondsp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * The head unit's own screens and car-side switches, reachable from the home screen so nothing
 * needs the factory launcher. Names are from the KSW M600 firmware (KswPLauncher 1.20); each one is
 * checked on the unit before it's offered ([isAvailable]), so a firmware that lacks one just
 * doesn't show it.
 *
 * Screens open as ordinary exported activities. The switches ([command]) go to the head unit's car
 * service, `wits_pms`, exactly as the factory launcher sends them (see [WitsPms]).
 */
enum class CarFunction(
    val id: String,
    @StringRes val label: Int,
    @StringRes val short: Int,
    val component: ComponentName? = null,
    val command: Int? = null,
    /** Shown greyed out and never run: it would fight J_DSP. */
    val blocked: Boolean = false,
) {
    /** KSW settings. It forwards to the right settings for the unit's UI theme; Factory is inside. */
    SETTINGS("ksw_settings", R.string.car_settings, R.string.car_settings_short, ComponentName(KSW, "$KSW.settings.SettingsActivity")),
    /** Switches the screen to the car's own iDrive (the same as holding MENU). */
    ORIGINAL_CAR("original_car", R.string.car_original, R.string.car_original_short, command = 601),
    DASHBOARD("ksw_dashboard", R.string.car_dashboard, R.string.car_dashboard_short, ComponentName(KSW, "$KSW.launcher.view.DashboardActivity")),
    /** The head unit's own EQ: listed so it's clearly accounted for, but J_DSP does the EQ. */
    EQ("ksw_eq", R.string.car_eq, R.string.car_eq_short, ComponentName("com.wits.csp.eq", "com.wits.csp.eq.view.StartActivity"), blocked = true),
    PHONE("ksw_bt", R.string.car_phone, R.string.car_phone_short, ComponentName("com.wits.ksw.bt", "com.wits.ksw.bt.StartActivity")),
    AUX("aux", R.string.car_aux, R.string.car_aux_short, command = 605),
    DVR("dvr", R.string.car_dvr, R.string.car_dvr_short, command = 609),
    FRONT_CAMERA("front_camera", R.string.car_front_camera, R.string.car_front_camera_short, command = 610),
    ;

    fun isAvailable(context: Context): Boolean = when {
        component != null -> try {
            val info = context.packageManager.getActivityInfo(component, 0)
            info.exported && info.isEnabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        else -> WitsPms.isPresent()
    }

    fun run(context: Context) {
        when {
            blocked -> return
            component != null -> Launch.component(context, component)
            command != null -> WitsPms.send(context, WitsPms.SYSTEM, command)
        }
    }

    companion object {
        fun byId(id: String): CarFunction? = entries.firstOrNull { it.id == id }
    }
}

private const val KSW = "com.wits.ksw"

/** Starts other apps from the home screen without ever crashing it (which would stop the audio). */
object Launch {
    /** Opens [component] as its app's main entry, in that app's own task. */
    fun component(context: Context, component: ComponentName) {
        start(context, Intent.makeMainActivity(component).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    /** Starts [intent] in its own task; a missing, disabled, restricted or broken target shows a message. */
    fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "Launch target missing")
        Toast.makeText(context, R.string.launcher_open_missing, Toast.LENGTH_SHORT).show()
        false
    } catch (e: SecurityException) {
        Timber.w(e, "Launch target restricted")
        Toast.makeText(context, R.string.launcher_open_restricted, Toast.LENGTH_SHORT).show()
        false
    } catch (e: RuntimeException) {
        // Anything else startActivity can throw (a stale or malformed target, a binder failure):
        // on the home screen an uncaught one would also stop the audio engine.
        Timber.w(e, "Launch failed")
        Toast.makeText(context, R.string.launcher_open_missing, Toast.LENGTH_SHORT).show()
        false
    }
}

/**
 * The KSW head unit's car service (`wits_pms`, in CenterService). Commands are a small JSON
 * object — the same `{command, subCommand, jsonArg}` the factory launcher builds with Gson — sent
 * through the service's first binder call, `sendCommand(String)`. The service checks no caller
 * permission. Calls run off the main thread; failures only log and show a message.
 */
object WitsPms {
    const val SYSTEM = 1
    private const val SERVICE = "wits_pms"
    private const val DESCRIPTOR = "com.wits.pms.IPowerManagerAppService"
    private const val TRANSACTION_SEND_COMMAND = IBinder.FIRST_CALL_TRANSACTION

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isPresent(): Boolean = binder() != null

    fun send(context: Context, command: Int, subCommand: Int, arg: String = "") {
        val app = context.applicationContext
        scope.launch {
            val binder = binder()
            if (binder == null) {
                // A slot pinned on a firmware that has the service, or before it has started.
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, R.string.launcher_open_missing, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val ok = try {
                val json = JSONObject()
                    .put("command", command)
                    .put("subCommand", subCommand)
                    .put("jsonArg", arg)
                    .toString()
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(json)
                    binder.transact(TRANSACTION_SEND_COMMAND, data, reply, 0)
                    reply.readException()
                    true
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            } catch (e: Exception) {
                Timber.w(e, "wits_pms command $command/$subCommand failed")
                false
            }
            if (!ok) withContext(Dispatchers.Main) {
                Toast.makeText(app, R.string.launcher_car_command_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** android.os.ServiceManager is hidden; MainApplication exempts hidden APIs for the app. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun binder(): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, SERVICE) as IBinder?
    } catch (e: Exception) {
        Timber.d(e, "No $SERVICE")
        null
    }
}
