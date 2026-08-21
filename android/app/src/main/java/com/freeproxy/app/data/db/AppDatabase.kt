package com.freeproxy.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.freeproxy.app.data.model.AnonymityLevel
import com.freeproxy.app.data.model.ProxyInfo
import com.freeproxy.app.data.model.ProxyType
import com.freeproxy.app.data.model.SpeedLevel

class Converters {
    @TypeConverter fun fromProxyType(v: ProxyType) = v.name
    @TypeConverter fun toProxyType(s: String) = runCatching { ProxyType.valueOf(s) }.getOrDefault(ProxyType.UNKNOWN)

    @TypeConverter fun fromAnonymity(v: AnonymityLevel) = v.name
    @TypeConverter fun toAnonymity(s: String) = runCatching { AnonymityLevel.valueOf(s) }.getOrDefault(AnonymityLevel.UNKNOWN)

    @TypeConverter fun fromSpeedLevel(v: SpeedLevel) = v.name
    @TypeConverter fun toSpeedLevel(s: String) = runCatching { SpeedLevel.valueOf(s) }.getOrDefault(SpeedLevel.UNKNOWN)
}

@Database(
    entities = [ProxyInfo::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun proxyDao(): ProxyDao

    companion object {
        /** v1 -> v2：为 proxies 表追加 6 个 L1-L4 验证字段 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLite 不支持 ADD COLUMN 带 DEFAULT（部分版本支持，但为兼容先不加 DEFAULT，
                // 实际数据由 Kotlin data class 默认值兜底；Room 在读取时会自动用默认值）
                runCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN validGoogle INTEGER NOT NULL DEFAULT 0")
                }.recoverCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN validGoogle INTEGER NOT NULL")
                }
                runCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN validYoutube INTEGER NOT NULL DEFAULT 0")
                }.recoverCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN validYoutube INTEGER NOT NULL")
                }
                runCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN validFacebook INTEGER NOT NULL DEFAULT 0")
                }.recoverCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN validFacebook INTEGER NOT NULL")
                }
                runCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN lastSitesOk TEXT NOT NULL DEFAULT ''")
                }.recoverCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN lastSitesOk TEXT NOT NULL")
                }
                // failReason 可为空（无 NOT NULL）
                runCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN failReason TEXT")
                }
                runCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN httpsTunnel INTEGER NOT NULL DEFAULT 1")
                }.recoverCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN httpsTunnel INTEGER NOT NULL")
                }
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                AppDatabase::class.java,
                "freeproxy.db"
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration() // 兜底：版本不匹配时清库重建，绝不 crash
                .build().also { INSTANCE = it }
        }
    }
}
