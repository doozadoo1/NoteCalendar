package com.sztorm.notecalendar

import android.app.Application
import com.orm.SugarContext
import com.sztorm.notecalendar.platform.db.NoteDatabaseMigrator
import timber.log.Timber

class NoteCalendarApplication : Application() {
    private fun initDebugLogger() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    override fun onCreate() {
        super.onCreate()
        initDebugLogger()
        NoteDatabaseMigrator.migrate(this)
        SugarContext.init(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        SugarContext.terminate()
    }
}