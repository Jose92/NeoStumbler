package xyz.malkki.wifiscannerformls.db.entities

import androidx.room.TypeConverters
import xyz.malkki.wifiscannerformls.db.converters.InstantConverters
import java.time.Instant

@TypeConverters(InstantConverters::class)
data class ReportWithWifiAccessPointCount(
    val reportId: Int,
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
    val wifiAccessPointCount: Int
)
