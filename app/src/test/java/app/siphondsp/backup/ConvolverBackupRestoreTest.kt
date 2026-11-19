package app.siphondsp.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarOutputStream
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConvolverBackupRestoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun restore(dirty: Boolean, withIr: Boolean = false) {
        val archive = temporaryFolder.newFile("backup.tar.gz")
        TarOutputStream(GZIPOutputStream(archive.outputStream())).use { tar ->
            fun entry(name: String, content: String) {
                val source = temporaryFolder.newFile()
                source.writeText(content)
                tar.putNextEntry(TarEntry(source, name))
                tar.write(source.readBytes())
            }
            entry("metadata", "min_version_code=0\nis_backup=true")
            if (withIr) entry("convolver/ir.wav", "restored")
        }
        BackupManager(context).restoreBackup(Uri.fromFile(archive), dirty)
    }

    private fun currentIrs(): Pair<File, File> {
        val root = File(context.filesDir, "convolver")
        root.deleteRecursively()
        val stereo = File(root, "ir.wav").apply { parentFile!!.mkdirs(); writeText("later stereo") }
        val band = File(root, "bands/low/ir.wav").apply { parentFile!!.mkdirs(); writeText("later band") }
        return stereo to band
    }

    @Test fun cleanRestoreOfEmptyIrSetRemovesLaterStereoAndBandIrs() {
        val (stereo, band) = currentIrs()
        restore(dirty = false)
        assertFalse(stereo.exists())
        assertFalse(band.exists())
    }

    @Test fun cleanRestoreReplacesTheWholeIrSet() {
        val (stereo, band) = currentIrs()
        restore(dirty = false, withIr = true)
        assertEquals("restored", stereo.readText())
        assertFalse(band.exists())
    }

    @Test fun dirtyRestoreOfEmptyIrSetRetainsCurrentIrs() {
        val (stereo, band) = currentIrs()
        restore(dirty = true)
        assertEquals("later stereo", stereo.readText())
        assertEquals("later band", band.readText())
    }
}
