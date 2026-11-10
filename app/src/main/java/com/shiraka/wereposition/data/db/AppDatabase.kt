package com.shiraka.wereposition.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
@Database(
    entities = [
        LocationRecord::class,
        LocationConnectedWifi::class,
        WifiDevice::class, LocationWifi::class,
        BluetoothDevice::class, LocationBluetooth::class,
        CellDevice::class, LocationCell::class,
        SavedRouteEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun environmentDao(): EnvironmentDao
    abstract fun savedRouteDao(): SavedRouteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "environment_database"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
