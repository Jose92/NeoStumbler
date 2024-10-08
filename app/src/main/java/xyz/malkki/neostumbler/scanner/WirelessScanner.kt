package xyz.malkki.neostumbler.scanner

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import xyz.malkki.neostumbler.common.LocationWithSource
import xyz.malkki.neostumbler.domain.BluetoothBeacon
import xyz.malkki.neostumbler.domain.CellTower
import xyz.malkki.neostumbler.domain.WifiAccessPoint
import xyz.malkki.neostumbler.extensions.elapsedRealtimeMillisCompat
import xyz.malkki.neostumbler.scanner.data.ReportData
import xyz.malkki.neostumbler.scanner.movement.ConstantMovementDetector
import xyz.malkki.neostumbler.scanner.movement.MovementDetector
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

//Maximum accuracy for locations, used for filtering bad locations
private const val LOCATION_MAX_ACCURACY = 200

//Maximum age for locations
private val LOCATION_MAX_AGE = 20.seconds

//Maximum age for beacons
private val BEACON_MAX_AGE = 20.seconds

/**
 * @param timeSource Time source used in the data, defaults to [SystemClock.elapsedRealtime]
 */
class WirelessScanner(
    private val locationSource: () -> Flow<LocationWithSource>,
    private val cellInfoSource: () -> Flow<List<CellTower>>,
    private val wifiAccessPointSource: () -> Flow<List<WifiAccessPoint>>,
    private val bluetoothBeaconSource: () -> Flow<List<BluetoothBeacon>>,
    private val movementDetector: MovementDetector = ConstantMovementDetector,
    private val timeSource: () -> Long = SystemClock::elapsedRealtime
) {
    fun createReports(): Flow<ReportData> = channelFlow {
        val mutex = Mutex()

        val wifiAccessPointByMacAddr = mutableMapOf<String, WifiAccessPoint>()
        val bluetoothBeaconsByMacAddr = mutableMapOf<String, BluetoothBeacon>()
        val cellTowersByKey = mutableMapOf<String, CellTower>()

        val isMovingFlow = movementDetector
            .getIsMovingFlow()
            .onEach {
                if (it) {
                    Timber.i("Moving started, resuming scanning")
                } else {
                    Timber.i("Moving stopped, pausing scanning")
                }
            }
            .shareIn(
                scope = this,
                started = SharingStarted.Eagerly,
                replay = 1
            )

        launch(Dispatchers.Default) {
            isMovingFlow
                .flatMapLatest { isMoving ->
                    if (isMoving) {
                        wifiAccessPointSource.invoke()
                    } else {
                        emptyFlow()
                    }
                }
                .map {
                    it.filterHiddenNetworks()
                }
                .collect { wifiAccessPoints ->
                    mutex.withLock {
                        wifiAccessPoints.forEach { wifiAccessPoint ->
                            wifiAccessPointByMacAddr[wifiAccessPoint.macAddress] = wifiAccessPoint
                        }
                    }
                }
        }

        launch(Dispatchers.Default) {
            isMovingFlow
                .flatMapLatest { isMoving ->
                    if (isMoving) {
                        bluetoothBeaconSource.invoke()
                    } else {
                        emptyFlow()
                    }
                }
                .map { bluetoothBeacons ->
                    val now = timeSource.invoke()

                    bluetoothBeacons.filter {
                        //Beacon library seems to sometimes return very old results -> filter them
                        (now - it.timestamp).milliseconds <= BEACON_MAX_AGE
                    }
                }
                .collect { bluetoothBeacons ->
                    mutex.withLock {
                        bluetoothBeacons.forEach { bluetoothBeacon ->
                            bluetoothBeaconsByMacAddr[bluetoothBeacon.macAddress] = bluetoothBeacon
                        }
                    }
                }
        }

        launch(Dispatchers.Default) {
            isMovingFlow
                .flatMapLatest { isMoving ->
                    if (isMoving) {
                        cellInfoSource.invoke()
                    } else {
                        emptyFlow()
                    }
                }
                .collect { cellTowers ->
                    mutex.withLock {
                        cellTowers.forEach { cellTower ->
                            cellTowersByKey[cellTower.key] = cellTower
                        }
                    }
                }
        }

        isMovingFlow
            .flatMapLatest { isMoving ->
                if (isMoving) {
                    locationSource.invoke()
                } else {
                    emptyFlow()
                }
            }
            .filter {
                it.location.hasAccuracy() && it.location.accuracy <= LOCATION_MAX_ACCURACY
            }
            .filter {
                val age = (timeSource.invoke() - it.location.elapsedRealtimeMillisCompat).milliseconds

                age <= LOCATION_MAX_AGE
            }
            //Collect locations in pairs so that we can choose the best one based on timestamp
            .runningFold(Pair<LocationWithSource?, LocationWithSource?>(null, null)) { pair, newLocation ->
                pair.second to newLocation
            }
            .filter {
                it.second != null
            }
            .flatMapConcat { (location1, location2) ->
                val (cells, wifis, bluetooths) = mutex.withLock {
                    val cells = cellTowersByKey.values.toList()
                    cellTowersByKey.clear()

                    val wifis = wifiAccessPointByMacAddr.values.toList()
                    wifiAccessPointByMacAddr.clear()

                    val bluetooths = bluetoothBeaconsByMacAddr.values.toList()
                    bluetoothBeaconsByMacAddr.clear()

                    Triple(cells, wifis, bluetooths)
                }

                val (location1cells, location2cells) = cells.partition {
                    location1 != null &&
                            abs(it.timestamp - location1.location.elapsedRealtimeMillisCompat) < abs(it.timestamp - location2!!.location.elapsedRealtimeMillisCompat)
                }

                val (location1wifis, location2wifis) = wifis.partition {
                    location1 != null &&
                        abs(it.timestamp - location1.location.elapsedRealtimeMillisCompat) < abs(it.timestamp - location2!!.location.elapsedRealtimeMillisCompat)
                }

                val (location1bluetooths, location2bluetooths) = bluetooths.partition {
                    location1 != null &&
                        abs(it.timestamp - location1.location.elapsedRealtimeMillisCompat) < abs(it.timestamp - location2!!.location.elapsedRealtimeMillisCompat)
                }

                listOfNotNull(
                    location1?.let {
                        ReportData(
                            location = it,
                            cellTowers = location1cells,
                            wifiAccessPoints = location1wifis
                                .takeIf { it.size >= 2 } ?: emptyList(),
                            bluetoothBeacons = location1bluetooths
                        )
                    },
                    ReportData(
                        location = location2!!,
                        cellTowers = location2cells,
                        wifiAccessPoints = location2wifis
                            .takeIf { it.size >= 2 } ?: emptyList(),
                        bluetoothBeacons = location2bluetooths
                    )
                ).asFlow()
            }
            .filter {
                it.bluetoothBeacons.isNotEmpty() || it.cellTowers.isNotEmpty() || it.wifiAccessPoints.isNotEmpty()
            }
            .collect(::send)
    }

    private val CellTower.key: String
        get() = listOf(mobileCountryCode, mobileNetworkCode, locationAreaCode, cellId, primaryScramblingCode).joinToString("/")

    /**
     * Filters Wi-Fi networks that should not be sent to geolocation services, i.e.
     * hidden networks with empty SSID or those with SSID ending in "_nomap"
     *
     * @return Filtered list of scan results
     */
    private fun List<WifiAccessPoint>.filterHiddenNetworks(): List<WifiAccessPoint> = filter { wifiAccessPoint ->
        val ssid = wifiAccessPoint.ssid

        !ssid.isNullOrBlank() && !ssid.endsWith("_nomap")
    }
}