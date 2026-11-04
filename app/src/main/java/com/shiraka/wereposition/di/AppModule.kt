package com.shiraka.wereposition.di

import com.shiraka.wereposition.data.repository.LocationRepository
import com.shiraka.wereposition.data.repository.SettingsRepository
import com.shiraka.wereposition.utils.ConfigManager
import com.shiraka.wereposition.utils.LSPosedManager
import com.shiraka.wereposition.utils.RootManager
import com.shiraka.wereposition.utils.SettingsManager
import com.shiraka.wereposition.viewmodel.MainViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { RootManager() }
    single { ConfigManager(get()) }
    single { LSPosedManager() }
    single { SettingsManager(androidContext()) }
    single { com.shiraka.wereposition.utils.EnvironmentScanner(androidContext()) }

    single { com.shiraka.wereposition.utils.WigleClient() }
    single { com.shiraka.wereposition.utils.OpenCellIdClient() }
    single { com.shiraka.wereposition.data.repository.WifiRepository(get()) }

    single { LocationRepository(get(), get(), get(), get(), get()) }
    single { SettingsRepository(get()) }

    single { com.shiraka.wereposition.data.db.AppDatabase.getDatabase(androidContext()) }
    single { get<com.shiraka.wereposition.data.db.AppDatabase>().environmentDao() }
    single { get<com.shiraka.wereposition.data.db.AppDatabase>().savedRouteDao() }

    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get(), androidContext()) }
}
