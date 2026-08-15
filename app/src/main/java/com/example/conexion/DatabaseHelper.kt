package com.example.conexion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.UUID

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "conexion.db"
        private const val DATABASE_VERSION = 3
        private const val TAG = "DatabaseHelper"

        // Starred Devices Table
        private const val TABLE_STARRED = "starred_devices"
        private const val COL_STAR_DEVICE_ID = "device_id"
        private const val COL_STAR_TIMESTAMP = "timestamp"

        // Profile Table
        private const val TABLE_PROFILE = "profile"
        private const val COL_PROFILE_ID = "id"
        private const val COL_PROFILE_DEVICE_ID = "device_id"
        private const val COL_PROFILE_NAME = "name"
        private const val COL_PROFILE_PHONE = "phone"
        private const val COL_PROFILE_AVATAR = "avatar"

        // Peers Table
        private const val TABLE_PEERS = "peers"
        private const val COL_PEER_ID = "id"
        private const val COL_PEER_DEVICE_ID = "device_id"
        private const val COL_PEER_NAME = "name"
        private const val COL_PEER_TOKEN = "token"
        private const val COL_PEER_PHONE = "phone"
        private const val COL_PEER_AVATAR = "avatar"
        private const val COL_PEER_LAST_SEEN = "last_seen"

        // Chats Table
        private const val TABLE_CHATS = "chats"
        private const val COL_CHAT_ID = "id"
        private const val COL_CHAT_PEER_DEVICE_ID = "peer_device_id"
        private const val COL_CHAT_SENDER_NAME = "sender_name"
        private const val COL_CHAT_MESSAGE = "message"
        private const val COL_CHAT_IS_ME = "is_me"
        private const val COL_CHAT_TIMESTAMP = "timestamp"

        // Transfer History Table
        private const val TABLE_TRANSFERS = "transfer_history"
        private const val COL_XFER_ID = "id"
        private const val COL_XFER_FILE_NAME = "file_name"
        private const val COL_XFER_FILE_SIZE = "file_size"
        private const val COL_XFER_PEER_NAME = "peer_name"
        private const val COL_XFER_IS_INCOMING = "is_incoming"
        private const val COL_XFER_TIMESTAMP = "timestamp"
        private const val COL_XFER_STATUS = "status"

        // Statuses / Stories Table
        private const val TABLE_STATUSES = "p2p_statuses"
        private const val COL_STATUS_ID = "id"
        private const val COL_STATUS_PEER_DEVICE_ID = "peer_device_id"
        private const val COL_STATUS_AUTHOR_NAME = "author_name"
        private const val COL_STATUS_MEDIA_TYPE = "media_type" // TEXT, PHOTO, VIDEO, AUDIO
        private const val COL_STATUS_CONTENT = "content"
        private const val COL_STATUS_CREATED_TIME = "created_timestamp"
        private const val COL_STATUS_REACTIONS = "reactions_json"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createProfileTable = """
            CREATE TABLE $TABLE_PROFILE (
                $COL_PROFILE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PROFILE_DEVICE_ID TEXT UNIQUE,
                $COL_PROFILE_NAME TEXT,
                $COL_PROFILE_PHONE TEXT,
                $COL_PROFILE_AVATAR INTEGER
            )
        """.trimIndent()

        val createPeersTable = """
            CREATE TABLE $TABLE_PEERS (
                $COL_PEER_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PEER_DEVICE_ID TEXT UNIQUE,
                $COL_PEER_NAME TEXT,
                $COL_PEER_TOKEN TEXT,
                $COL_PEER_PHONE TEXT,
                $COL_PEER_AVATAR INTEGER,
                $COL_PEER_LAST_SEEN INTEGER
            )
        """.trimIndent()

        val createChatsTable = """
            CREATE TABLE $TABLE_CHATS (
                $COL_CHAT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CHAT_PEER_DEVICE_ID TEXT,
                $COL_CHAT_SENDER_NAME TEXT,
                $COL_CHAT_MESSAGE TEXT,
                $COL_CHAT_IS_ME INTEGER,
                $COL_CHAT_TIMESTAMP INTEGER
            )
        """.trimIndent()

        val createTransfersTable = """
            CREATE TABLE $TABLE_TRANSFERS (
                $COL_XFER_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_XFER_FILE_NAME TEXT,
                $COL_XFER_FILE_SIZE INTEGER,
                $COL_XFER_PEER_NAME TEXT,
                $COL_XFER_IS_INCOMING INTEGER,
                $COL_XFER_TIMESTAMP INTEGER,
                $COL_XFER_STATUS TEXT
            )
        """.trimIndent()

        val createStarredTable = """
            CREATE TABLE $TABLE_STARRED (
                $COL_STAR_DEVICE_ID TEXT PRIMARY KEY,
                $COL_STAR_TIMESTAMP INTEGER
            )
        """.trimIndent()

        val createStatusesTable = """
            CREATE TABLE $TABLE_STATUSES (
                $COL_STATUS_ID TEXT PRIMARY KEY,
                $COL_STATUS_PEER_DEVICE_ID TEXT,
                $COL_STATUS_AUTHOR_NAME TEXT,
                $COL_STATUS_MEDIA_TYPE TEXT,
                $COL_STATUS_CONTENT TEXT,
                $COL_STATUS_CREATED_TIME INTEGER,
                $COL_STATUS_REACTIONS TEXT
            )
        """.trimIndent()

        db.execSQL(createProfileTable)
        db.execSQL(createPeersTable)
        db.execSQL(createChatsTable)
        db.execSQL(createTransfersTable)
        db.execSQL(createStarredTable)
        db.execSQL(createStatusesTable)
        Log.d(TAG, "Database tables created.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_STARRED (
                    $COL_STAR_DEVICE_ID TEXT PRIMARY KEY,
                    $COL_STAR_TIMESTAMP INTEGER
                )
            """.trimIndent())
        }
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_STATUSES (
                $COL_STATUS_ID TEXT PRIMARY KEY,
                $COL_STATUS_PEER_DEVICE_ID TEXT,
                $COL_STATUS_AUTHOR_NAME TEXT,
                $COL_STATUS_MEDIA_TYPE TEXT,
                $COL_STATUS_CONTENT TEXT,
                $COL_STATUS_CREATED_TIME INTEGER,
                $COL_STATUS_REACTIONS TEXT
            )
        """.trimIndent())
    }

    // --- Statuses / Stories Operations ---
    fun saveOrUpdateStatus(
        id: String,
        peerDeviceId: String,
        authorName: String,
        mediaType: String,
        content: String,
        createdTimestamp: Long,
        reactionsJson: String = "{}"
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATUS_ID, id)
            put(COL_STATUS_PEER_DEVICE_ID, peerDeviceId)
            put(COL_STATUS_AUTHOR_NAME, authorName)
            put(COL_STATUS_MEDIA_TYPE, mediaType)
            put(COL_STATUS_CONTENT, content)
            put(COL_STATUS_CREATED_TIME, createdTimestamp)
            put(COL_STATUS_REACTIONS, reactionsJson)
        }
        db.insertWithOnConflict(TABLE_STATUSES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        purgeExpiredStatuses()
    }

    fun purgeExpiredStatuses() {
        val db = writableDatabase
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L // 24 hours
        db.delete(TABLE_STATUSES, "$COL_STATUS_CREATED_TIME < ?", arrayOf(cutoff.toString()))
    }

    fun getActiveStatuses(): List<DbStatus> {
        purgeExpiredStatuses()
        val list = mutableListOf<DbStatus>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STATUSES,
            null,
            null,
            null,
            null, null,
            "$COL_STATUS_CREATED_TIME DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(it.getColumnIndexOrThrow(COL_STATUS_ID)) ?: ""
                val peerDevId = it.getString(it.getColumnIndexOrThrow(COL_STATUS_PEER_DEVICE_ID)) ?: ""
                val author = it.getString(it.getColumnIndexOrThrow(COL_STATUS_AUTHOR_NAME)) ?: "Usuario"
                val type = it.getString(it.getColumnIndexOrThrow(COL_STATUS_MEDIA_TYPE)) ?: "TEXT"
                val content = it.getString(it.getColumnIndexOrThrow(COL_STATUS_CONTENT)) ?: ""
                val created = it.getLong(it.getColumnIndexOrThrow(COL_STATUS_CREATED_TIME))
                val rx = it.getString(it.getColumnIndexOrThrow(COL_STATUS_REACTIONS)) ?: "{}"
                list.add(DbStatus(id, peerDevId, author, type, content, created, rx))
            }
        }
        return list
    }

    fun addStatusReaction(statusId: String, emoji: String): String {
        val db = writableDatabase
        val cursor = db.query(TABLE_STATUSES, arrayOf(COL_STATUS_REACTIONS), "$COL_STATUS_ID = ?", arrayOf(statusId), null, null, null)
        var rxJson = "{}"
        cursor.use {
            if (it.moveToFirst()) {
                rxJson = it.getString(0) ?: "{}"
            }
        }
        val map = mutableMapOf<String, Int>()
        try {
            val json = org.json.JSONObject(rxJson)
            json.keys().forEach { k -> map[k] = json.getInt(k) }
        } catch (e: Exception) {}
        map[emoji] = (map[emoji] ?: 0) + 1

        val newJson = org.json.JSONObject(map as Map<*, *>).toString()
        val cv = ContentValues().apply { put(COL_STATUS_REACTIONS, newJson) }
        db.update(TABLE_STATUSES, cv, "$COL_STATUS_ID = ?", arrayOf(statusId))
        return newJson
    }

    // --- Starred Devices Operations ---
    fun toggleStarDevice(deviceId: String): Boolean {
        if (deviceId.isEmpty()) return false
        val db = writableDatabase
        val isStarred = isDeviceStarred(deviceId)
        if (isStarred) {
            db.delete(TABLE_STARRED, "$COL_STAR_DEVICE_ID = ?", arrayOf(deviceId))
            return false
        } else {
            val values = ContentValues().apply {
                put(COL_STAR_DEVICE_ID, deviceId)
                put(COL_STAR_TIMESTAMP, System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_STARRED, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            return true
        }
    }

    fun isDeviceStarred(deviceId: String): Boolean {
        if (deviceId.isEmpty()) return false
        val db = readableDatabase
        val cursor = db.query(TABLE_STARRED, arrayOf(COL_STAR_DEVICE_ID), "$COL_STAR_DEVICE_ID = ?", arrayOf(deviceId), null, null, null)
        cursor.use {
            return it.count > 0
        }
    }

    fun getStarredDeviceIds(): Set<String> {
        val set = mutableSetOf<String>()
        val db = readableDatabase
        val cursor = db.query(TABLE_STARRED, arrayOf(COL_STAR_DEVICE_ID), null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                if (!id.isNullOrEmpty()) set.add(id)
            }
        }
        return set
    }

    // --- My Persistent Device Identity Operations ---
    fun getOrCreateMyDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("conexion_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("my_persistent_device_id", null)
        if (!existing.isNullOrEmpty()) {
            return existing
        }
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("my_persistent_device_id", newId).apply()
        return newId
    }

    // --- Profile Operations ---
    fun saveProfile(name: String, phone: String, avatar: Int, deviceId: String = "") {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PROFILE_DEVICE_ID, deviceId)
            put(COL_PROFILE_NAME, name)
            put(COL_PROFILE_PHONE, phone)
            put(COL_PROFILE_AVATAR, avatar)
        }
        val rows = db.update(TABLE_PROFILE, values, "$COL_PROFILE_ID = 1", null)
        if (rows == 0) {
            values.put(COL_PROFILE_ID, 1)
            db.insert(TABLE_PROFILE, null, values)
        }
        Log.d(TAG, "Profile saved in DB: $name, deviceId: $deviceId, phone: $phone, avatar: $avatar")
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
    fun saveOrUpdatePeer(name: String, token: String, phone: String, avatar: Int, deviceId: String = "") {
        val idToUse = if (deviceId.isNotEmpty()) deviceId else if (token.isNotEmpty()) token else return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_PEER_DEVICE_ID, idToUse)
            put(COL_PEER_NAME, name)
            put(COL_PEER_TOKEN, token)
            put(COL_PEER_PHONE, phone)
            put(COL_PEER_AVATAR, avatar)
            put(COL_PEER_LAST_SEEN, System.currentTimeMillis())
        }
        val rows = db.update(TABLE_PEERS, values, "$COL_PEER_DEVICE_ID = ?", arrayOf(idToUse))
        if (rows == 0) {
            db.insertWithOnConflict(TABLE_PEERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
        Log.d(TAG, "Peer saved/updated in DB: name=$name, deviceId=$idToUse, token=$token")
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
                val devId = it.getString(it.getColumnIndexOrThrow(COL_PEER_DEVICE_ID)) ?: ""
                val name = it.getString(it.getColumnIndexOrThrow(COL_PEER_NAME)) ?: "Dispositivo"
                val token = it.getString(it.getColumnIndexOrThrow(COL_PEER_TOKEN)) ?: ""
                val phone = it.getString(it.getColumnIndexOrThrow(COL_PEER_PHONE)) ?: ""
                val avatar = it.getInt(it.getColumnIndexOrThrow(COL_PEER_AVATAR))
                val lastSeen = it.getLong(it.getColumnIndexOrThrow(COL_PEER_LAST_SEEN))
                list.add(DbPeer(devId, name, token, phone, avatar, lastSeen))
            }
        }
        return list
    }

    fun getPeerByDeviceId(deviceId: String): DbPeer? {
        if (deviceId.isEmpty()) return null
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PEERS,
            null,
            "$COL_PEER_DEVICE_ID = ?",
            arrayOf(deviceId),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val devId = it.getString(it.getColumnIndexOrThrow(COL_PEER_DEVICE_ID)) ?: ""
                val name = it.getString(it.getColumnIndexOrThrow(COL_PEER_NAME)) ?: "Dispositivo"
                val token = it.getString(it.getColumnIndexOrThrow(COL_PEER_TOKEN)) ?: ""
                val phone = it.getString(it.getColumnIndexOrThrow(COL_PEER_PHONE)) ?: ""
                val avatar = it.getInt(it.getColumnIndexOrThrow(COL_PEER_AVATAR))
                val lastSeen = it.getLong(it.getColumnIndexOrThrow(COL_PEER_LAST_SEEN))
                return DbPeer(devId, name, token, phone, avatar, lastSeen)
            }
        }
        return null
    }

    // --- Chat Operations ---
    fun saveChatMessage(peerDeviceId: String, senderName: String, message: String, isMe: Boolean): DbChatMessage {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_CHAT_PEER_DEVICE_ID, peerDeviceId)
            put(COL_CHAT_SENDER_NAME, senderName)
            put(COL_CHAT_MESSAGE, message)
            put(COL_CHAT_IS_ME, if (isMe) 1 else 0)
            put(COL_CHAT_TIMESTAMP, now)
        }
        val id = db.insert(TABLE_CHATS, null, values)
        return DbChatMessage(id, peerDeviceId, senderName, message, isMe, now)
    }

    fun getChatMessages(peerDeviceId: String): List<DbChatMessage> {
        val list = mutableListOf<DbChatMessage>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_CHATS,
            null,
            "$COL_CHAT_PEER_DEVICE_ID = ?",
            arrayOf(peerDeviceId),
            null, null,
            "$COL_CHAT_TIMESTAMP ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_CHAT_ID))
                val peerId = it.getString(it.getColumnIndexOrThrow(COL_CHAT_PEER_DEVICE_ID)) ?: ""
                val sender = it.getString(it.getColumnIndexOrThrow(COL_CHAT_SENDER_NAME)) ?: ""
                val msg = it.getString(it.getColumnIndexOrThrow(COL_CHAT_MESSAGE)) ?: ""
                val isMe = it.getInt(it.getColumnIndexOrThrow(COL_CHAT_IS_ME)) == 1
                val time = it.getLong(it.getColumnIndexOrThrow(COL_CHAT_TIMESTAMP))
                list.add(DbChatMessage(id, peerId, sender, msg, isMe, time))
            }
        }
        return list
    }

    fun getAllChatConversations(): List<DbChatThread> {
        val list = mutableListOf<DbChatThread>()
        val db = readableDatabase
        val query = """
            SELECT c.$COL_CHAT_PEER_DEVICE_ID, c.$COL_CHAT_SENDER_NAME, c.$COL_CHAT_MESSAGE, MAX(c.$COL_CHAT_TIMESTAMP) as max_time
            FROM $TABLE_CHATS c
            GROUP BY c.$COL_CHAT_PEER_DEVICE_ID
            ORDER BY max_time DESC
        """.trimIndent()

        val cursor = db.rawQuery(query, null)
        cursor.use {
            while (it.moveToNext()) {
                val peerDeviceId = it.getString(0) ?: ""
                val senderName = it.getString(1) ?: "Usuario"
                val lastMsg = it.getString(2) ?: ""
                val timestamp = it.getLong(3)

                val peer = getPeerByDeviceId(peerDeviceId)
                val peerName = peer?.name ?: senderName
                val avatar = peer?.avatar ?: 0

                list.add(DbChatThread(peerDeviceId, peerName, avatar, lastMsg, timestamp))
            }
        }
        return list
    }

    // --- File Transfer History Operations ---
    fun saveTransferRecord(fileName: String, fileSize: Long, peerName: String, isIncoming: Boolean, status: String): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_XFER_FILE_NAME, fileName)
            put(COL_XFER_FILE_SIZE, fileSize)
            put(COL_XFER_PEER_NAME, peerName)
            put(COL_XFER_IS_INCOMING, if (isIncoming) 1 else 0)
            put(COL_XFER_TIMESTAMP, System.currentTimeMillis())
            put(COL_XFER_STATUS, status)
        }
        return db.insert(TABLE_TRANSFERS, null, values)
    }

    fun getTransferHistory(limit: Int = 30): List<DbTransferRecord> {
        val list = mutableListOf<DbTransferRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRANSFERS,
            null, null, null, null, null,
            "$COL_XFER_TIMESTAMP DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_XFER_ID))
                val fileName = it.getString(it.getColumnIndexOrThrow(COL_XFER_FILE_NAME)) ?: ""
                val fileSize = it.getLong(it.getColumnIndexOrThrow(COL_XFER_FILE_SIZE))
                val peerName = it.getString(it.getColumnIndexOrThrow(COL_XFER_PEER_NAME)) ?: ""
                val isIncoming = it.getInt(it.getColumnIndexOrThrow(COL_XFER_IS_INCOMING)) == 1
                val timestamp = it.getLong(it.getColumnIndexOrThrow(COL_XFER_TIMESTAMP))
                val status = it.getString(it.getColumnIndexOrThrow(COL_XFER_STATUS)) ?: "Completado"
                list.add(DbTransferRecord(id, fileName, fileSize, peerName, isIncoming, timestamp, status))
            }
        }
        return list
    }
}

data class DbPeer(
    val deviceId: String,
    val name: String,
    val token: String,
    val phone: String,
    val avatar: Int,
    val lastSeen: Long
)

data class DbChatMessage(
    val id: Long,
    val peerDeviceId: String,
    val senderName: String,
    val message: String,
    val isMe: Boolean,
    val timestamp: Long
)

data class DbChatThread(
    val peerDeviceId: String,
    val peerName: String,
    val avatarIndex: Int,
    val lastMessage: String,
    val timestamp: Long
)

data class DbTransferRecord(
    val id: Long,
    val fileName: String,
    val fileSize: Long,
    val peerName: String,
    val isIncoming: Boolean,
    val timestamp: Long,
    val status: String
)

data class DbStatus(
    val id: String,
    val peerDeviceId: String,
    val authorName: String,
    val mediaType: String,
    val content: String,
    val createdTimestamp: Long,
    val reactionsJson: String
)
