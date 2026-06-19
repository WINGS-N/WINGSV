package be.mygod.vpnhotspot.room

import android.net.MacAddress
import java.net.InetAddress

data class TrafficRecord(
    var id: Long? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val mac: MacAddress,
    val ip: InetAddress,
    val upstream: String? = null,
    val downstream: String,
    var sentPackets: Long = 0,
    var sentBytes: Long = 0,
    var receivedPackets: Long = 0,
    var receivedBytes: Long = 0,
    val previousId: Long? = null,
) {
    class Dao {
        private var nextId = 1L
        private val records = LinkedHashMap<Long, TrafficRecord>()

        fun insert(value: TrafficRecord) {
            check(value.id == null)
            value.id = nextId++
            records[value.id!!] = value
        }
    }
}

data class ClientStats(
    val timestamp: Long = 0,
    val count: Long = 0,
    val sentPackets: Long = 0,
    val sentBytes: Long = 0,
    val receivedPackets: Long = 0,
    val receivedBytes: Long = 0,
)
