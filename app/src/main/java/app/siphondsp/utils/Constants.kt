package app.siphondsp.utils

import app.siphondsp.BuildConfig

object Constants {
    // App-relevant preference namespaces
    const val PREF_APP = "application"
    const val PREF_VAR = "variable"

    // DSP-relevant preference namespaces
    const val PREF_CONVOLVER = "dsp_convolver"
    const val PREF_PEQ = "dsp_parametriceq"
    const val PREF_LIVEPROG = "dsp_liveprog"
    const val PREF_OUTPUT = "dsp_output_control"

    // Default string values
    const val DEFAULT_CONVOLVER_ADVIMP = "-80;-100;0;0;0;0"
    const val DEFAULT_PEQ = "PEQ: "

    // Intent actions
    const val ACTION_PREFERENCES_UPDATED = BuildConfig.APPLICATION_ID + ".action.preferences.UPDATED"
    const val ACTION_SAMPLE_RATE_UPDATED = BuildConfig.APPLICATION_ID + ".action.sample_rate.UPDATED"
    const val ACTION_PRESET_LOADED = BuildConfig.APPLICATION_ID + ".action.preset.LOADED"
    const val ACTION_GRAPHIC_EQ_CHANGED = BuildConfig.APPLICATION_ID + ".action.preferences.graphiceq.CHANGED"
    const val ACTION_PARAMETRIC_EQ_CHANGED = BuildConfig.APPLICATION_ID + ".action.preferences.parametriceq.CHANGED"
    const val ACTION_SESSION_CHANGED = BuildConfig.APPLICATION_ID + ".action.session.CHANGED"
    const val ACTION_SERVICE_STARTED = BuildConfig.APPLICATION_ID + ".action.service.STARTED"
    const val ACTION_SERVICE_STOPPED = BuildConfig.APPLICATION_ID + ".action.service.STOPPED"
    const val ACTION_SERVICE_RELOAD_LIVEPROG = BuildConfig.APPLICATION_ID + ".action.service.RELOAD_LIVEPROG"
    const val ACTION_SERVICE_HARD_REBOOT_CORE = BuildConfig.APPLICATION_ID + ".action.service.HARD_REBOOT_CORE"
    const val ACTION_SERVICE_SOFT_REBOOT_CORE = BuildConfig.APPLICATION_ID + ".action.service.SOFT_REBOOT_CORE"
    const val ACTION_PROCESSOR_MESSAGE = BuildConfig.APPLICATION_ID + ".action.service.PROCESSOR_MESSAGE"
    const val ACTION_DISCARD_AUTHORIZATION = BuildConfig.APPLICATION_ID + ".action.service.DISCARD_AUTHORIZATION"
    const val ACTION_REPORT_SAMPLE_RATE = BuildConfig.APPLICATION_ID + ".action.service.REPORT_SAMPLE_RATE"
    const val ACTION_BACKUP_RESTORED = BuildConfig.APPLICATION_ID + ".action.backup.RESTORED"
    const val ACTION_NATIVE_BMW_DSP_UPDATED = BuildConfig.APPLICATION_ID + ".action.native_bmw_dsp.UPDATED"
    const val EXTRA_NATIVE_BMW_DSP_VALUES = "native_bmw_dsp_values"

    // Intent extras
    const val EXTRA_SAMPLE_RATE = BuildConfig.APPLICATION_ID + ".extra.service.SAMPLE_RATE"
}