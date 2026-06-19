package be.mygod.vpnhotspot.room

class AppDatabase private constructor() {
    companion object {
        const val DB_NAME = "app.db"
        val instance by lazy { AppDatabase() }
    }

    val clientRecordDao = ClientRecord.Dao()
    val trafficRecordDao = TrafficRecord.Dao()
}
