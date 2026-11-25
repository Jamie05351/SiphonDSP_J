package app.siphondsp.compose.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.compose.state.PeqStateHolder
import app.siphondsp.fragment.PeqDialogs
import app.siphondsp.fragment.PeqScope
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the same edit actions used by the node inspector and the full-page table. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeqFilterActionsTest {
    @get:Rule val files = TemporaryFolder()

    private fun fixture(): Pair<Context, PeqStateHolder> {
        val directory = files.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir() = directory
        }
        val state = BmwPeqState.empty().copy(enabled = true)
        state.fullRangeBands.add(ParametricEqBand(648.0, -3.5, 1.4))
        state.fullRangeBands.add(ParametricEqBand(1100.0, -3.0, 1.15))
        state.lowBandBands.add(ParametricEqBand(70.0, 4.0, 0.8))
        assertTrue(state.persist(context))
        return context to PeqStateHolder(context)
    }

    @Test
    fun gainPreviewChangesOnlyTheSelectedFilterAndSavesOnRelease() {
        val (context, holder) = fixture()
        val identity = holder.visibleBands[0].uuid
        val neighbour = holder.visibleBands[1]
        val actions = PeqFilterActions(holder, 0, PeqDialogs(context))
        actions.stepGain(1, 2)
        assertEquals(-2.5, holder.visibleBands[0].gain, 0.0)
        assertEquals(-3.5, BmwPeqState.load(context).fullRangeBands[0].gain, 0.0)
        assertEquals(identity, holder.visibleBands[0].uuid)
        assertEquals(neighbour, holder.visibleBands[1])
        actions.commit()
        assertEquals(-2.5, BmwPeqState.load(context).fullRangeBands[0].gain, 0.0)
    }

    @Test
    fun frequencyAndQControlsDoNotChangeGainOrOtherBanks() {
        val (context, holder) = fixture()
        val actions = PeqFilterActions(holder, 0, PeqDialogs(context))
        val originalFrequency = holder.visibleBands[0].frequency
        actions.stepFrequency(1, 1)
        assertTrue(holder.visibleBands[0].frequency > originalFrequency)
        assertEquals(-3.5, holder.visibleBands[0].gain, 0.0)
        assertEquals(1.4, holder.visibleBands[0].q, 0.0)
        actions.stepQ(-1, 2)
        assertEquals(1.2, holder.visibleBands[0].q, 0.00001)
        assertEquals(70.0, holder.peqState.lowBandBands[0].frequency, 0.0)
        actions.commit()
        assertEquals(1.2, BmwPeqState.load(context).fullRangeBands[0].q, 0.00001)
    }

    @Test
    fun steppersClampAndBankNumberingMatchesGraphColours() {
        val (context, holder) = fixture()
        val actions = PeqFilterActions(holder, 0, PeqDialogs(context))
        actions.stepGain(1, 1000)
        assertEquals(30.0, holder.visibleBands[0].gain, 0.0)
        actions.stepQ(-1, 1000)
        assertEquals(0.1, holder.visibleBands[0].q, 0.0)
        actions.commit()
        holder.selectedScope = PeqScope.LOW
        assertEquals(2, holder.filterNumberOffset)
        assertNotEquals(peqFilterColor(0), peqFilterColor(holder.filterNumberOffset))
        val lowActions = PeqFilterActions(holder, 0, PeqDialogs(context))
        lowActions.stepGain(-1, 1)
        lowActions.commit()
        assertEquals(3.5, BmwPeqState.load(context).lowBandBands[0].gain, 0.0)
        assertEquals(30.0, BmwPeqState.load(context).fullRangeBands[0].gain, 0.0)
    }
}
