package app.siphondsp.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import app.siphondsp.compose.screens.BandAlignScreen

/** Hosts [BandAlignScreen]: REW measurements of each band in, the Low delay and polarity against Mid out. */
class BandAlignActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BandAlignScreen() }
    }
}
