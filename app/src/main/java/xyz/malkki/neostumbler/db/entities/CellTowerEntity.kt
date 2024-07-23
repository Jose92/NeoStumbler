package xyz.malkki.neostumbler.db.entities

import android.os.SystemClock
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import xyz.malkki.neostumbler.domain.CellTower
import java.time.Instant
import java.time.temporal.ChronoUnit

@Entity(
    foreignKeys = [ForeignKey(entity = Report::class, parentColumns = ["id"], childColumns = ["reportId"], onDelete = ForeignKey.CASCADE)]
)
data class CellTowerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long?,
    val radioType: String,
    val mobileCountryCode: Int?,
    val mobileNetworkCode: Int?,
    val cellId: Long?,
    val locationAreaCode: Int?,
    val asu: Int?,
    val primaryScramblingCode: Int?,
    val serving: Int?,
    val signalStrength: Int?,
    val timingAdvance: Int?,
    val age: Long,
    @ColumnInfo(index = true) val reportId: Long?
) {
    companion object {
        fun fromCellTower(cellTower: CellTower, reportTimestamp: Instant, reportId: Long): CellTowerEntity {
            //Report time is truncated to seconds -> age can be negative by some milliseconds
            val age = maxOf(0, Instant.now().minusMillis(SystemClock.elapsedRealtime() - cellTower.timestamp).until(reportTimestamp, ChronoUnit.MILLIS))

            return CellTowerEntity(
                id = null,
                radioType = cellTower.radioType.name.lowercase(),
                mobileCountryCode = cellTower.mobileCountryCode!!.toInt(),
                mobileNetworkCode = cellTower.mobileNetworkCode!!.toInt(),
                cellId = cellTower.cellId,
                locationAreaCode = cellTower.locationAreaCode,
                asu = cellTower.asu,
                primaryScramblingCode = cellTower.primaryScramblingCode,
                serving = cellTower.serving,
                signalStrength = cellTower.signalStrength,
                timingAdvance = cellTower.timingAdvance,
                age = age,
                reportId = reportId
            )
        }
    }
}
