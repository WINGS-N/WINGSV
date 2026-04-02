package be.mygod.vpnhotspot.room

import android.net.MacAddress

data class ClientRecord(
    val mac: MacAddress,
    var nickname: CharSequence = "",
    var blocked: Boolean = false,
    var macLookupPending: Boolean = true,
) {
    class Dao {
        private val records = LinkedHashMap<MacAddress, ClientRecord>()

        fun lookupOrDefaultBlocking(mac: MacAddress) = synchronized(records) {
            records[mac] ?: ClientRecord(mac)
        }

        suspend fun lookupOrDefault(mac: MacAddress) = lookupOrDefaultBlocking(mac)

        suspend fun update(value: ClientRecord) {
            synchronized(records) {
                records[value.mac] = value
            }
        }

        suspend fun upsert(mac: MacAddress, operation: suspend ClientRecord.() -> Unit) {
            val record = lookupOrDefaultBlocking(mac)
            operation(record)
            update(record)
        }
    }
}
