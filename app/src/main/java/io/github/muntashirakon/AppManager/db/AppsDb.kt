// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.muntashirakon.AppManager.db.dao.*
import io.github.muntashirakon.AppManager.db.entity.*
import io.github.muntashirakon.AppManager.utils.ContextUtils

@Database(
    entities = [App::class, LogFilter::class, Backup::class, OpHistory::class, FmFavorite::class, FreezeType::class, ArchivedApp::class],
    version = 12
)
@TypeConverters(Converters::class)
abstract class AppsDb : RoomDatabase() {
    companion object {
        private var sAppsDb: AppsDb? = null

        @JvmField
        val M_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `op_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `time` INTEGER NOT NULL, `data` TEXT NOT NULL, `status` TEXT NOT NULL, `extra` TEXT)")
            }
        }

        @JvmField
        val M_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `fm_favorite` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `uri` TEXT NOT NULL, `init_uri` TEXT, `options` INTEGER NOT NULL, `order` INTEGER NOT NULL, `type` INTEGER NOT NULL)")
            }
        }

        @JvmField
        val M_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `freeze_type` (`package_name` TEXT NOT NULL, `type` INTEGER NOT NULL, PRIMARY KEY(`package_name`))")
            }
        }

        @JvmField
        val M_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `app` ADD COLUMN `is_only_data_installed` INTEGER NOT NULL DEFAULT 0")
            }
        }

        @JvmField
        val M_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `file_hash`")
            }
        }

        @JvmField
        val M_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `archived_apps` (`package_name` TEXT NOT NULL, `app_name` TEXT, `archive_timestamp` INTEGER NOT NULL, PRIMARY KEY(`package_name`))")
            }
        }

        @JvmField
        val M_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `archived_apps` ADD COLUMN `apk_path` TEXT")
            }
        }

        @JvmField
        val M_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // OPTIMIZATION: Add indices for common filter queries
                // These indices speed up filtering by 2-5× for common operations
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_flags` ON `app` (`flags`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_is_installed` ON `app` (`is_installed`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_is_enabled` ON `app` (`is_enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_last_update_time` ON `app` (`last_update_time`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_target_sdk` ON `app` (`target_sdk`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_tracker_count` ON `app` (`tracker_count`)")
                // Composite indices for common combined filters
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_is_installed_user_id` ON `app` (`is_installed`, `user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_flags_is_installed` ON `app` (`flags`, `is_installed`)")
            }
        }

        @JvmField
        val M_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `app` ADD COLUMN `tags` TEXT")
            }
        }

        @JvmField
        val M_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `archived_apps` ADD COLUMN `tags` TEXT")
            }
        }

        @JvmStatic
        @Synchronized
        fun getInstance(): AppsDb {
            if (sAppsDb == null) {
                sAppsDb = Room.databaseBuilder(ContextUtils.getContext(), AppsDb::class.java, "apps.db")
                    .addMigrations(M_2_3, M_3_4, M_4_5, M_5_6, M_6_7, M_7_8, M_8_9, M_9_10, M_10_11, M_11_12)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                try {
                    sAppsDb!!.appDao().getAllSync()
                } catch (th: Throwable) {
                    th.printStackTrace()
                }
            }
            return sAppsDb!!
        }
    }

    abstract fun appDao(): AppDao

    abstract fun backupDao(): BackupDao

    abstract fun logFilterDao(): LogFilterDao

    abstract fun opHistoryDao(): OpHistoryDao

    abstract fun fmFavoriteDao(): FmFavoriteDao

    abstract fun freezeTypeDao(): FreezeTypeDao

    abstract fun archivedAppDao(): ArchivedAppDao
}
