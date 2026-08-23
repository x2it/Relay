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
import com.freeproxy.app.data.model.ProxySourceEntity
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
    entities = [ProxyInfo::class, ProxySourceEntity::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun proxyDao(): ProxyDao
    abstract fun sourceDao(): SourceDao

    companion object {
        /** v3 -> v4：proxies 表追加加密节点字段（nodeName / configJson） */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN nodeName TEXT")
                }
                runCatching {
                    db.execSQL("ALTER TABLE proxies ADD COLUMN configJson TEXT")
                }
            }
        }

        /** v4 -> v5：proxy_sources 表追加抓取状态字段（lastStatus/lastCount/lastFetchedAt） */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE proxy_sources ADD COLUMN lastStatus TEXT NOT NULL DEFAULT 'N'")
                db.execSQL("ALTER TABLE proxy_sources ADD COLUMN lastCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE proxy_sources ADD COLUMN lastFetchedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3：新增 proxy_sources 表（内置 + 自定义数据源统一入库） */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `proxy_sources` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`format` TEXT NOT NULL, " +
                        "`schemeHint` TEXT, " +
                        "`timeoutMs` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`mirrors` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_proxy_sources_url` " +
                        "ON `proxy_sources` (`url`)"
                )
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration() // 兜底：版本不匹配时清库重建，绝不 crash
                .build().also { INSTANCE = it }
        }
    }
}
