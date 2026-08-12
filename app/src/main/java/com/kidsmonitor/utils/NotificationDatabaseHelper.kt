package com.kidsmonitor.utils

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class NotificationItem(
    val id: Long = 0,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: String
)

class NotificationDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {

    companion object {
        private const val DATABASE_NAME = "kids_notifications.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NOTIFICATIONS = "notifications"

        private const val COLUMN_ID = "id"
        private const val COLUMN_PACKAGE_NAME = "package_name"
        private const val COLUMN_APP_NAME = "app_name"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_TEXT = "text"
        private const val COLUMN_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_NOTIFICATIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_PACKAGE_NAME TEXT,
                $COLUMN_APP_NAME TEXT,
                $COLUMN_TITLE TEXT,
                $COLUMN_TEXT TEXT,
                $COLUMN_TIMESTAMP TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NOTIFICATIONS")
        onCreate(db)
    }

    fun insertNotification(item: NotificationItem): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_PACKAGE_NAME, item.packageName)
            put(COLUMN_APP_NAME, item.appName)
            put(COLUMN_TITLE, item.title)
            put(COLUMN_TEXT, item.text)
            put(COLUMN_TIMESTAMP, item.timestamp)
        }
        val id = db.insert(TABLE_NOTIFICATIONS, null, values)
        db.close()
        return id
    }

    fun getNotifications(limit: Int = 50, offset: Int = 0): List<NotificationItem> {
        val list = mutableListOf<NotificationItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NOTIFICATIONS,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_ID DESC",
            "$offset, $limit"
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(COLUMN_ID)
            val pkgIdx = it.getColumnIndex(COLUMN_PACKAGE_NAME)
            val appIdx = it.getColumnIndex(COLUMN_APP_NAME)
            val titleIdx = it.getColumnIndex(COLUMN_TITLE)
            val textIdx = it.getColumnIndex(COLUMN_TEXT)
            val timeIdx = it.getColumnIndex(COLUMN_TIMESTAMP)

            while (it.moveToNext()) {
                val id = if (idIdx != -1) it.getLong(idIdx) else 0L
                val pkg = if (pkgIdx != -1) it.getString(pkgIdx) ?: "" else ""
                val app = if (appIdx != -1) it.getString(appIdx) ?: "" else ""
                val title = if (titleIdx != -1) it.getString(titleIdx) ?: "" else ""
                val text = if (textIdx != -1) it.getString(textIdx) ?: "" else ""
                val timestamp = if (timeIdx != -1) it.getString(timeIdx) ?: "" else ""

                list.add(NotificationItem(id, pkg, app, title, text, timestamp))
            }
        }
        db.close()
        return list
    }
}
