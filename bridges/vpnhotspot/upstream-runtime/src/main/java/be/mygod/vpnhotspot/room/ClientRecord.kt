package be.mygod.vpnhotspot.room

import android.net.MacAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

data class ClientRecord(
    val mac: MacAddress,
    var nickname: CharSequence = "",
    var blocked: Boolean = false,
    var macLookupPending: Boolean = true,
) {
    class Dao {
        private val records = LinkedHashMap<MacAddress, ClientRecord>()
        private val blockedMacs = MutableStateFlow<List<MacAddress>>(emptyList())

        fun lookupOrDefaultBlocking(mac: MacAddress) = synchronized(records) {
            records[mac] ?: ClientRecord(mac)
        }

        suspend fun lookupOrDefault(mac: MacAddress) = lookupOrDefaultBlocking(mac)

        fun lookupOrDefaultFlow(mac: MacAddress): Flow<ClientRecord> =
            blockedMacs.asStateFlow().map { lookupOrDefaultBlocking(mac) }

        suspend fun update(value: ClientRecord) {
            synchronized(records) {
                records[value.mac] = value
                blockedMacs.value = records.values.filter { it.blocked }.map { it.mac }
            }
        }

        suspend fun upsert(mac: MacAddress, operation: suspend ClientRecord.() -> Unit) {
            val record = lookupOrDefaultBlocking(mac)
            operation(record)
            update(record)
        }

        fun observeBlockedMacs(): Flow<List<MacAddress>> = blockedMacs.asStateFlow()
    }
}
