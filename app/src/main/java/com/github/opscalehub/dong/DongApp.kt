package com.github.opscalehub.dong

import android.app.Application
import com.github.opscalehub.dong.data.BackupRepository
import com.github.opscalehub.dong.data.ReportRepository
import com.github.opscalehub.dong.data.Repos
import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.update.Updater

/** Manual dependency container: one database, one set of repositories for the process. */
class AppContainer(app: Application) {
    val db: DongDatabase = DongDatabase.build(app)
    val repos = Repos(db)
    val backup = BackupRepository(repos, BuildConfig.VERSION_NAME)
    val reports = ReportRepository(repos)
    val updater = Updater(app, BuildConfig.VERSION_CODE, BuildConfig.UPDATE_URLS.split(','), autoCheckAllowed = BuildConfig.UPDATES_ENABLED && (!BuildConfig.DEBUG || BuildConfig.UPDATE_AUTO_DEBUG))
}

class DongApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.updater.onAppStart()
    }
}
