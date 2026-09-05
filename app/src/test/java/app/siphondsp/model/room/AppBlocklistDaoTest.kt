package app.siphondsp.model.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.fragment.resolveBlockedAppIcons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression cover for the shared-`android:sharedUserId` bug: `ApplicationInfo.uid` is not unique
 * per app, so it can't be the blocklist primary key nor the icon-cache key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppBlocklistDaoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppBlocklistDatabase

    private val radio = BlockedApp(uid = SHARED_UID, packageName = "com.oem.radio", appName = "Radio")
    private val nav = BlockedApp(uid = SHARED_UID, packageName = "com.oem.nav", appName = "Nav")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppBlocklistDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(MIGRATION_DB)
    }

    /** The repro: two apps that share a uid must both survive as their own row. */
    @Test
    fun sharedUid_differentPackages_persistAsSeparateRows() = runBlocking {
        db.appBlocklistDao().insertAll(radio, nav)

        val all = db.appBlocklistDao().getAll().first().sortedBy { it.packageName }

        assertEquals(2, all.size)
        assertEquals(listOf("com.oem.nav", "com.oem.radio"), all.map { it.packageName })
        assertTrue("both rows keep the shared uid", all.all { it.uid == SHARED_UID })
    }

    /** Each shared-uid app resolves its OWN icon -- the cache is keyed by packageName. */
    @Test
    fun resolveBlockedAppIcons_givesEachSharedUidAppItsOwnIcon() {
        val icons = mapOf(
            "com.oem.radio" to ColorDrawable(0xFF0000FF.toInt()),
            "com.oem.nav" to ColorDrawable(0xFF00FF00.toInt()),
        )
        val cache = hashMapOf<String, Drawable>()

        resolveBlockedAppIcons(listOf(radio, nav), cache) { pkg -> icons[pkg] }

        assertEquals(icons["com.oem.radio"], radio.appIcon)
        assertEquals(icons["com.oem.nav"], nav.appIcon)
        assertNotSame(radio.appIcon, nav.appIcon)
    }

    /**
     * v1 (uid PK) -> v2 (package_name PK): rows are carried across, NULL-package rows dropped, and
     * the migrated schema then accepts two apps sharing a uid. Room validates the post-migration
     * schema when it opens, so a wrong CREATE TABLE in the migration would throw here.
     */
    @Test
    fun migrate1To2_carriesData_dropsNullPackage_andAcceptsSharedUid() = runBlocking {
        context.deleteDatabase(MIGRATION_DB)
        val path = context.getDatabasePath(MIGRATION_DB).apply { parentFile?.mkdirs() }

        SQLiteDatabase.openOrCreateDatabase(path, null).use { v1 ->
            v1.execSQL(
                "CREATE TABLE `BlockedApp` (`uid` INTEGER NOT NULL, `package_name` TEXT, " +
                    "`app_name` TEXT, PRIMARY KEY(`uid`))"
            )
            v1.execSQL("INSERT INTO `BlockedApp` VALUES (1000, 'com.oem.radio', 'Radio')")
            v1.execSQL("INSERT INTO `BlockedApp` VALUES (1001, 'com.other.app', 'Other')")
            v1.execSQL("INSERT INTO `BlockedApp` VALUES (1002, NULL, 'Ghost')")
            v1.version = 1
        }

        val migrated = Room.databaseBuilder(context, AppBlocklistDatabase::class.java, MIGRATION_DB)
            .addMigrations(AppBlocklistDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = migrated.appBlocklistDao()
            val rows = dao.getAll().first().associateBy { it.packageName }

            assertEquals("NULL package_name row must be dropped", 2, rows.size)
            assertEquals(1000, rows.getValue("com.oem.radio").uid)
            assertEquals(1001, rows.getValue("com.other.app").uid)
            assertNull(rows["Ghost"])

            dao.insertAll(BlockedApp(1000, "com.oem.nav", "Nav"))
            assertEquals(3, dao.getAll().first().size)
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val SHARED_UID = 10123
        const val MIGRATION_DB = "blocklist-migration-test.db"
    }
}
