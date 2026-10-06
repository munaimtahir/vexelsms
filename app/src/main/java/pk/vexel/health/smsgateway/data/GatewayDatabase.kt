package pk.vexel.health.smsgateway.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.ForeignKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import java.util.UUID

@Entity(tableName = "gateway_work", indices = [Index(value = ["attemptId"], unique = true)])
data class GatewayWork(
    @PrimaryKey val attemptId: String,
    val messageId: String,
    val state: String,
    val encryptedPayload: String,
    val updatedAt: Long
)

@Entity(tableName = "gateway_events", indices = [Index(value = ["eventId"], unique = true), Index(value = ["synced", "createdAt"])])
data class GatewayEvent(
    @PrimaryKey val eventId: String = UUID.randomUUID().toString(),
    val attemptId: String,
    val eventType: String,
    val encryptedPayload: String,
    val synced: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "gateway_callbacks", primaryKeys = ["attemptId", "kind", "partIndex"])
data class GatewayCallback(val attemptId: String, val kind: String, val partIndex: Int, val resultCode: Int)

@Dao
abstract class GatewayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insertCallback(callback: GatewayCallback): Long
    @Query("SELECT COUNT(*) FROM gateway_callbacks WHERE attemptId = :attempt AND kind = :kind") abstract fun callbackCount(attempt: String, kind: String): Int
    @Query("SELECT COUNT(*) FROM gateway_callbacks WHERE attemptId = :attempt AND kind = :kind AND resultCode != -1") abstract fun callbackFailures(attempt: String, kind: String): Int
    @Query("SELECT COUNT(*) FROM gateway_callbacks WHERE attemptId = :attempt AND kind = :kind AND resultCode = -1") abstract fun callbackSuccesses(attempt: String, kind: String): Int
    @Query("SELECT * FROM gateway_work WHERE attemptId = :id") abstract fun work(id: String): GatewayWork?
    @Query("SELECT * FROM gateway_work WHERE state IN ('CLAIMED','STARTING','PHYSICAL','INVOKING') ORDER BY updatedAt LIMIT 1") abstract fun currentWork(): GatewayWork?
    @Query("SELECT * FROM gateway_work WHERE state = 'CLAIMED' ORDER BY updatedAt LIMIT 1") abstract fun nextClaimed(): GatewayWork?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insertWork(work: GatewayWork): Long
    @Query("UPDATE gateway_work SET state = :state, updatedAt = :now WHERE attemptId = :id") abstract fun setState(id: String, state: String, now: Long = System.currentTimeMillis())
    @Query("SELECT * FROM gateway_events WHERE synced = 0 ORDER BY createdAt LIMIT 100") abstract fun pendingEvents(): List<GatewayEvent>
    @Query("SELECT * FROM gateway_events WHERE attemptId = :attempt AND eventType = :type LIMIT 1") abstract fun event(attempt: String, type: String): GatewayEvent?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insertEvent(event: GatewayEvent): Long
    @Query("UPDATE gateway_events SET synced = 1 WHERE eventId = :id") abstract fun markSynced(id: String)
    @Query("DELETE FROM gateway_events WHERE attemptId = :attempt") abstract fun deleteAttemptEvents(attempt: String)
    @Query("DELETE FROM gateway_work WHERE attemptId = :attempt") abstract fun deleteWork(attempt: String)
    @Query("SELECT COUNT(*) FROM gateway_events WHERE attemptId = :attempt AND eventType = :type AND synced = 0") abstract fun hasPending(attempt: String, type: String): Int
    @Query("SELECT * FROM gateway_work WHERE state = 'PHYSICAL' ORDER BY updatedAt LIMIT 1") abstract fun physicalWork(): GatewayWork?

    @Transaction open fun persistClaim(work: GatewayWork): Boolean = insertWork(work) != -1L
    @Transaction open fun recordCallback(callback: GatewayCallback): Triple<Int, Int, Int> {
        insertCallback(callback)
        return Triple(
            callbackCount(callback.attemptId, callback.kind),
            callbackFailures(callback.attemptId, callback.kind),
            callbackSuccesses(callback.attemptId, callback.kind),
        )
    }
    @Transaction open fun beginTransmission(work: GatewayWork, started: GatewayEvent) {
        if (event(work.attemptId, "SEND_STARTED") == null) insertEvent(started)
        setState(work.attemptId, "STARTING")
    }
    @Transaction open fun markInvoking(attempt: String) = setState(attempt, "INVOKING")
    @Transaction open fun discardBeforeSend(attempt: String) {
        deleteAttemptEvents(attempt)
        deleteWork(attempt)
    }
    @Transaction open fun recordCallback(work: GatewayWork, nextState: String, callback: GatewayEvent) {
        insertEvent(callback)
        setState(work.attemptId, nextState)
    }
}

@Database(entities = [GatewayWork::class, GatewayEvent::class, GatewayCallback::class], version = 1, exportSchema = true)
abstract class GatewayDatabase : RoomDatabase() {
    abstract fun gatewayDao(): GatewayDao
    companion object {
        @Volatile private var instance: GatewayDatabase? = null
        fun get(context: Context): GatewayDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context, GatewayDatabase::class.java, "gateway-work.db").build().also { instance = it }
        }
    }
}
