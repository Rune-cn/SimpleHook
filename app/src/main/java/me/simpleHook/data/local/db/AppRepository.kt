package me.simpleHook.data.local.db

import android.content.Context
import androidx.lifecycle.LiveData
import me.simpleHook.data.local.db.entity.AppConfig
import me.simpleHook.data.local.db.entity.CollectionEntity
import me.simpleHook.data.local.db.entity.ExtensionConfigEntity

class AppRepository(context: Context) {
    private val appConfigDao = AppDatabase.getDatabase(context).getAppConfigDao()

    private val extConfigDao = AppDatabase.getDatabase(context).getExtensionConfigDao()

    private val collectionDao = AppDatabase.getDatabase(context).getCollectionDao()

    suspend fun insertConfigs(vararg appConfig: AppConfig) {
        appConfigDao.insertConfigs(*appConfig)
    }

    suspend fun updateConfigs(vararg appConfig: AppConfig) {
        appConfigDao.updateConfigs(*appConfig)
    }

    suspend fun deleteConfigs(vararg appConfig: AppConfig) {
        appConfigDao.deleteConfigs(*appConfig)

    }

    suspend fun getEnabledCustomCountByPackageName(packageName: String): Int {
        return appConfigDao.getEnabledCountByPackageName(packageName)
    }

    suspend fun getEnabledPackageNames(): Set<String> {
        return appConfigDao.getEnabledPackageNames().toSet()
    }

    fun deleteAllConfigs() {
        appConfigDao.deleteAllConfigs()
    }

    fun getAllConfigs() = appConfigDao.queryAll()
    fun getConfigs() = appConfigDao.getAll()

    fun getAllPackageNames() = appConfigDao.getAllPackageNames()

    fun getAllConfigList() = appConfigDao.getAll()

    suspend fun getConfigsByPackage(packageName: String): List<AppConfig> =
        appConfigDao.getConfigsByPackageName(packageName)

    suspend fun getLatestConfigByPackage(packageName: String): AppConfig? =
        appConfigDao.getLatestConfigByPackageName(packageName)

    fun getExtConfigs() = extConfigDao.getExtConfigs()

    suspend fun getExtConfigByPackage(packageName: String): ExtensionConfigEntity? =
        extConfigDao.getExtensionConfigByPackageName(packageName)

    // --- Blocking variants -------------------------------------------------------------
    // The MCP transport serves requests from blocking socket threads; these thin wrappers
    // bridge to the suspending Room DAOs with runBlocking on an IO dispatcher. They exist
    // only for that caller and must not be used from the UI thread.

    fun updateConfigsSync(vararg appConfig: AppConfig) {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            appConfigDao.updateConfigs(*appConfig)
        }
    }

    fun deleteConfigsSync(vararg appConfig: AppConfig) {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            appConfigDao.deleteConfigs(*appConfig)
        }
    }

    fun insertExtConfigsSync(vararg config: ExtensionConfigEntity) {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            extConfigDao.insertExtConfigs(*config)
        }
    }

    fun updateExtConfigsSync(vararg config: ExtensionConfigEntity) {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            extConfigDao.updateExtConfigs(*config)
        }
    }

    // collection

    fun insertCollections(vararg collectionEntity: CollectionEntity) {
        collectionDao.insertCollections(*collectionEntity)
    }

    fun updateCollections(vararg collectionEntity: CollectionEntity) {
        collectionDao.updateCollections(*collectionEntity)
    }

    fun deleteCollections(vararg collectionEntity: CollectionEntity) {
        collectionDao.deleteCollections(*collectionEntity)
    }

    fun deleteAllCollections() {
        collectionDao.deleteAllCollections()
    }

    fun getAllCollections(): LiveData<List<CollectionEntity>> {
        return collectionDao.queryAll()
    }

    fun getCollections(): List<CollectionEntity> {
        return collectionDao.getAll()
    }
}
