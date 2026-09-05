package app.siphondsp.model.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope

@Database(entities = [BlockedApp::class], version = 2)
abstract class AppBlocklistDatabase : RoomDatabase() {
    abstract fun appBlocklistDao(): AppBlocklistDao

    private class AppBlocklistDatabaseCallback(
        private val scope: CoroutineScope
    ) : Callback()

    companion object {
        // Singleton prevents multiple instances of database opening at the
        // same time.
        @Volatile
        private var INSTANCE: AppBlocklistDatabase? = null

        // v1 -> v2: move the primary key from uid to package_name. SQLite can't alter a primary
        // key in place, so rebuild the table and copy the rows across. Rows with a NULL
        // package_name are dropped (can't satisfy the new non-null PK, and were unusable anyway);
        // INSERT OR IGNORE guards against any pre-existing duplicate package_name.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `BlockedApp_new` " +
                        "(`uid` INTEGER NOT NULL, `package_name` TEXT NOT NULL, " +
                        "`app_name` TEXT, PRIMARY KEY(`package_name`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `BlockedApp_new` (`uid`, `package_name`, `app_name`) " +
                        "SELECT `uid`, `package_name`, `app_name` FROM `BlockedApp` " +
                        "WHERE `package_name` IS NOT NULL"
                )
                db.execSQL("DROP TABLE `BlockedApp`")
                db.execSQL("ALTER TABLE `BlockedApp_new` RENAME TO `BlockedApp`")
            }
        }

        fun getDatabase(context: Context, scope: CoroutineScope): AppBlocklistDatabase {
            // if the INSTANCE is not null, then return it,
            // if it is, then create the database
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                        context.applicationContext,
                        AppBlocklistDatabase::class.java,
                        "blocked_apps.db"
                    )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(AppBlocklistDatabaseCallback(scope))
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
