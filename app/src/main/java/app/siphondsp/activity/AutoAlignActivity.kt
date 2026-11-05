package app.siphondsp.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import app.siphondsp.compose.screens.AutoAlignScreen

/** Hosts [AutoAlignScreen]: REW measurements in, the left/right all-pass alignment out. */
class AutoAlignActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AutoAlignScreen() }
    }
}
