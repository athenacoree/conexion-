package com.example.conexion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "conexion.db"
        private const val DATABASE_VERSION = 1
        private const val TAG = "DatabaseHelper"

        // Profile Table
        private const val TABLE_PROFILE = "profile"
        private const val COL_PROFILE_ID = "id"
        private const val COL_PROFILE_NAME = "name"
        private const val COL_PROFILE_PHONE = "phone"
        private const val COL_PROFILE_AVATAR = "avatar"

        // Peers Table
        private const val TABLE_PEERS = "peers"
        private const val COL_PEER_ID = "id"
        private const val COL_PEER_NAME = "name"
        private const val COL_PEER_TOKEN = "token"
        private const val COL_PEER_PHONE = "phone"
        private const val COL_PEER_AVATAR = "avatar"
        private const val COL_PEER_LAST_SEEN = "last_seen"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createProfileTable = """
            CREATE TABLE $TABLE_PROFILE (
                $COL_PROFILE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PROFILE_NAME TEXT,
                $COL_PROFILE_PHONE TEXT,
                $COL_PROFILE_AVATAR INTEGER
            )
        """.trimIndent()

        val createPeersTable = """
            CREATE TABLE $TABLE_PEERS (
                $COL_PEER_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PEER_NAME TEXT,
                $COL_PEER_TOKEN TEXT UNIQUE,
                $COL_PEER_PHONE TEXT,
                $COL_PEER_AVATAR INTEGER,
                $COL_PEER_LAST_SEEN INTEGER
            )
        """.trimIndent()

        db.execSQL(createProfileTable)
        db.execSQL(createPeersTable)
        Log.d(TAG, "Database tables created.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PROFILE")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PEERS")
        onCreate(db)
    }

    // --- Profile Operations ---
    fun saveProfile(name: String, phone: String, avatar: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PROFILE_NAME, name)
            put(COL_PROFILE_PHONE, phone)
            put(COL_PROFILE_AVATAR, avatar)
        }
        val rows = db.update(TABLE_PROFILE, values, "$COL_PROFILE_ID = 1", null)
        if (rows == 0) {
            values.put(COL_PROFILE_ID, 1)
            db.insert(TABLE_PROFILE, null, values)
        }
        Log.d(TAG, "Profile saved in DB: $name, $phone, avatar: $avatar")
    }

    fun getProfile(): Triple<String, String, Int>? {
        val db = readableDatabase
        val cursor = db.query(TABLE_PROFILE, null, "$COL_PROFILE_ID = 1", null, null, null, null)
        cursor.use {
            if (it.moveToFirst()) {
                val name = it.getString(it.getColumnIndexOrThrow(COL_PROFILE_NAME)) ?: "Mi Dispositivo"
                val phone = it.getString(it.getColumnIndexOrThrow(COL_PROFILE_PHONE)) ?: ""
                val avatar = it.getInt(it.getColumnIndexOrThrow(COL_PROFILE_AVATAR))
                return Triple(name, phone, avatar)
            }
        }
        return null
    }

    // --- Peers Operations ---
    fun saveOrUpdatePeer(name: String, token: String, phone: String, avatar: Int) {
        if (token.isEmpty()) return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PEER_NAME, name)
            put(COL_PEER_TOKEN, token)
            put(COL_PEER_PHONE, phone)
            put(COL_PEER_AVATAR, avatar)
            put(COL_PEER_LAST_SEEN, System.currentTimeMillis())
        }
        val rows = db.update(TABLE_PEERS, values, "$COL_PEER_TOKEN = ?", arrayOf(token))
        if (rows == 0) {
            db.insertWithOnConflict(TABLE_PEERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        Log.d(TAG, "Peer saved/updated in DB: $name, token: $token, phone: $phone")
    }

    fun getRecentPeers(limit: Int = 10): List<DbPeer> {
        val list = mutableListOf<DbPeer>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PEERS,
            null,
            null,
            null,
            null,
            null,
            "$COL_PEER_LAST_SEEN DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow(COL_PEER_NAME)) ?: "Dispositivo"
                val token = it.getString(it.getColumnIndexOrThrow(COL_PEER_TOKEN)) ?: ""
                val phone = it.getString(it.getColumnIndexOrThrow(COL_PEER_PHONE)) ?: ""
                val avatar = it.getInt(it.getColumnIndexOrThrow(COL_PEER_AVATAR))
                val lastSeen = it.getLong(it.getColumnIndexOrThrow(COL_PEER_LAST_SEEN))
                list.add(DbPeer(name, token, phone, avatar, lastSeen))
            }
        }
        return list
    }
}

data class DbPeer(
    val name: String,
    val token: String,
    val phone: String,
    val avatar: Int,
    val lastSeen: Long
)
