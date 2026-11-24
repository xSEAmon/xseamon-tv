package ru.freetv.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction

@Entity(tableName = "epg_sources")
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val gzip: Boolean = false,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val lastFetchedAt: Long = 0,
    val lastError: String? = null
)

@Entity(
    tableName = "epg_channels",
    primaryKeys = ["sourceId", "xmltvId"],
    indices = [Index("normalizedName")]
)
data class EpgChannelEntity(
    val sourceId: Long,
    val xmltvId: String,
    val displayName: String,
    val normalizedName: String
)

@Entity(
    tableName = "epg_programmes",
    indices = [Index("channelKey", "start"), Index("sourceId", "start")]
)
data class EpgProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val channelKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String? = null,
    val category: String? = null
)

@Entity(tableName = "recent")
data class RecentEntity(
    @PrimaryKey val channelKey: String,
    val watchedAt: Long,
    val positionMs: Long = 0
)

@Dao
interface EpgDao {

    @Query("SELECT * FROM epg_sources ORDER BY priority, id")
    suspend fun sources(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_sources WHERE enabled = 1 ORDER BY priority, id")
    suspend fun enabledSources(): List<EpgSourceEntity>

    @Insert
    suspend fun insertSource(source: EpgSourceEntity): Long

    @Update
    suspend fun updateSource(source: EpgSourceEntity)

    @Delete
    suspend fun deleteSource(source: EpgSourceEntity)

    @Query("DELETE FROM epg_programmes WHERE sourceId = :sourceId")
    suspend fun clearProgrammes(sourceId: Long)

    @Query("DELETE FROM epg_channels WHERE sourceId = :sourceId")
    suspend fun clearChannels(sourceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(items: List<EpgProgrammeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(items: List<EpgChannelEntity>)

    @Query("SELECT * FROM epg_programmes WHERE channelKey IN (:keys) AND stop > :from AND start < :to ORDER BY start")
    suspend fun programmes(keys: List<String>, from: Long, to: Long): List<EpgProgrammeEntity>

    @Query("SELECT * FROM recent ORDER BY watchedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun recent(limit: Int, offset: Int): List<RecentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecent(item: RecentEntity)

    @Query("DELETE FROM recent WHERE channelKey = :key")
    suspend fun deleteRecent(key: String)

    @Query("DELETE FROM recent")
    suspend fun clearRecent()
}

@Database(
    entities = [
        EpgSourceEntity::class,
        EpgChannelEntity::class,
        EpgProgrammeEntity::class,
        RecentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun epgDao(): EpgDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "antontv.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
