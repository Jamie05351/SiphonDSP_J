package app.siphondsp.model.room

import android.graphics.drawable.Drawable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

// Primary key is packageName, not uid: apps sharing an android:sharedUserId report the same uid,
// so a uid primary key silently merged distinct blocked apps on REPLACE insert. uid stays as a
// plain column (runtime audio-session exclusion is still uid-based).
@Entity
data class BlockedApp(
    val uid: Int,
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_name") val appName: String?
) {
    @Ignore var appIcon: Drawable? = null
}
