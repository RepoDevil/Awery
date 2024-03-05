package ani.awery.connections.discord

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ani.awery.MainActivity
import com.mrboomdev.awery.R
import ani.awery.connections.discord.serializers.Presence
import ani.awery.connections.discord.serializers.User
import ani.awery.isOnline
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.OutputStreamWriter

class DiscordService : Service() {
    private var heartbeat: Int = 0
    private var sequence: Int? = null
    private var sessionId: String = ""
    private var resume = false
    private lateinit var logFile: File
    private lateinit var webSocket: WebSocket
    private lateinit var heartbeatThread: Thread
    private lateinit var client: OkHttpClient
    private lateinit var wakeLock: PowerManager.WakeLock
    var presenceStore = ""
    val json = Json {
        encodeDefaults = true
        allowStructuredMapKeys = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    var log = ""

    override fun onCreate() {
        super.onCreate()

        log("Service onCreate()")
        val powerManager = baseContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "discordRPC:backgroundPresence"
        )
        wakeLock.acquire()
        log("WakeLock Acquired")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "discordPresence",
                "Discord Presence Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent =
            PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "discordPresence")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Discord Presence")
            .setContentText("Running in the background")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        startForeground(1, builder.build())
        log("Foreground service started, notification shown")
        client = OkHttpClient()
        client.newWebSocket(
            Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build(),
            DiscordWebSocketListener()
        )
        client.dispatcher.executorService.shutdown()
        SERVICE_RUNNING = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("Service onStartCommand()")
        if (intent != null) {
            if (intent.hasExtra("presence")) {
                log("Service onStartCommand() setPresence")
                val lPresence = intent.getStringExtra("presence")
                if (this::webSocket.isInitialized) webSocket.send(lPresence!!)
                presenceStore = lPresence!!
            } else {
                log("Service onStartCommand() no presence")
                DiscordServiceRunningSingleton.running = false
                //kill the client
                client = OkHttpClient()
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        log("Service Destroyed")
        if (DiscordServiceRunningSingleton.running) {
            log("Accidental Service Destruction, restarting service")
            val intent = Intent(baseContext, DiscordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                baseContext.startForegroundService(intent)
            } else {
                baseContext.startService(intent)
            }
        } else {
            if (this::webSocket.isInitialized)
                setPresence(
                    json.encodeToString(
                        Presence.Response(
                            3,
                            Presence(status = "offline")
                        )
                    )
                )
            wakeLock.release()
        }
        SERVICE_RUNNING = false
        client = OkHttpClient()
        if (this::webSocket.isInitialized) webSocket.close(1000, "Closed by user")
        super.onDestroy()
        //saveLogToFile()
    }

    fun saveProfile(response: String) {
        val sharedPref = baseContext.getSharedPreferences(
            "aweryprefs",
            Context.MODE_PRIVATE
        )
        val user = json.decodeFromString<User.Response>(response).d.user
        log("User data: $user")
        with(sharedPref.edit()) {
            putString("discord_username", user.username)
            putString("discord_id", user.id)
            putString("discord_avatar", user.avatar)
            apply()
        }

    }

    override fun onBind(p0: Intent?): IBinder? = null

    inner class DiscordWebSocketListener : WebSocketListener() {

        var retryAttempts = 0
        val maxRetryAttempts = 10
        override fun onOpen(webSocket: WebSocket, response: Response) {
            super.onOpen(webSocket, response)
            this@DiscordService.webSocket = webSocket
            log("WebSocket: Opened")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            super.onMessage(webSocket, text)
            val json = JsonParser.parseString(text).asJsonObject
            log("WebSocket: Received op code ${json.get("op")}")
            when (json.get("op").asInt) {
                0 -> {
                    if (json.has("s")) {
                        log("WebSocket: Sequence ${json.get("s")} Received")
                        sequence = json.get("s").asInt
                    }
                    if (json.get("t").asString != "READY") return
                    saveProfile(text)
                    log(text)
                    sessionId = json.get("d").asJsonObject.get("session_id").asString
                    log("WebSocket: SessionID ${json.get("d").asJsonObject.get("session_id")} Received")
                    if (presenceStore.isNotEmpty()) setPresence(presenceStore)
                    sendBroadcast(Intent("ServiceToConnectButton"))
                }

                1 -> {
                    log("WebSocket: Received Heartbeat request, sending heartbeat")
                    heartbeatThread.interrupt()
                    heartbeatSend(webSocket, sequence)
                    heartbeatThread = Thread(HeartbeatRunnable())
                    heartbeatThread.start()
                }

                7 -> {
                    resume = true
                    log("WebSocket: Requested to Restart, restarting")
                    webSocket.close(1000, "Requested to Restart by the server")
                    client = OkHttpClient()
                    client.newWebSocket(
                        Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json")
                            .build(),
                        DiscordWebSocketListener()
                    )
                    client.dispatcher.executorService.shutdown()
                }

                9 -> {
                    log("WebSocket: Invalid Session, restarting")
                    webSocket.close(1000, "Invalid Session")
                    Thread.sleep(5000)
                    client = OkHttpClient()
                    client.newWebSocket(
                        Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json")
                            .build(),
                        DiscordWebSocketListener()
                    )
                    client.dispatcher.executorService.shutdown()
                }

                10 -> {
                    heartbeat = json.get("d").asJsonObject.get("heartbeat_interval").asInt
                    heartbeatThread = Thread(HeartbeatRunnable())
                    heartbeatThread.start()
                    if (resume) {
                        log("WebSocket: Resuming because server requested")
                        resume()
                        resume = false
                    } else {
                        identify(webSocket, baseContext)
                        log("WebSocket: Identified")
                    }
                }

                11 -> {
                    log("WebSocket: Heartbeat ACKed")
                    heartbeatThread = Thread(HeartbeatRunnable())
                    heartbeatThread.start()
                }
            }
        }

        fun identify(webSocket: WebSocket, context: Context) {
            val properties = JsonObject()
            properties.addProperty("os", "linux")
            properties.addProperty("browser", "unknown")
            properties.addProperty("device", "unknown")
            val d = JsonObject()
            d.addProperty("token", getToken(context))
            d.addProperty("intents", 0)
            d.add("properties", properties)
            val payload = JsonObject()
            payload.addProperty("op", 2)
            payload.add("d", d)
            webSocket.send(payload.toString())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            super.onFailure(webSocket, t, response)
            if (!isOnline(baseContext)) {
                log("WebSocket: Error, onFailure() reason: No Internet")
                errorNotification("Could not set the presence", "No Internet")
                return
            } else {
                retryAttempts++
                if (retryAttempts >= maxRetryAttempts) {
                    log("WebSocket: Error, onFailure() reason: Max Retry Attempts")
                    errorNotification("Could not set the presence", "Max Retry Attempts")
                    return
                }
            }
            t.message?.let { Log.d("WebSocket", "onFailure() $it") }
            log("WebSocket: Error, onFailure() reason: ${t.message}")
            client = OkHttpClient()
            client.newWebSocket(
                Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build(),
                DiscordWebSocketListener()
            )
            client.dispatcher.executorService.shutdown()
            if (::heartbeatThread.isInitialized && !heartbeatThread.isInterrupted) {
                heartbeatThread.interrupt()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            super.onClosing(webSocket, code, reason)
            Log.d("WebSocket", "onClosing() $code $reason")
            if (::heartbeatThread.isInitialized && !heartbeatThread.isInterrupted) {
                heartbeatThread.interrupt()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            super.onClosed(webSocket, code, reason)
            Log.d("WebSocket", "onClosed() $code $reason")
            if (code >= 4000) {
                log("WebSocket: Error, code: $code reason: $reason")
                client = OkHttpClient()
                client.newWebSocket(
                    Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build(),
                    DiscordWebSocketListener()
                )
                client.dispatcher.executorService.shutdown()
                return
            }
        }
    }

    fun getToken(context: Context): String {
        val sharedPref = context.getSharedPreferences("aweryprefs", Context.MODE_PRIVATE)
        val token = sharedPref.getString(Discord.TOKEN, null)
        if (token == null) {
            log("WebSocket: Token not found")
            errorNotification("Could not set the presence", "token not found")
            return ""
        } else {
            return token
        }
    }

    fun heartbeatSend(webSocket: WebSocket, seq: Int?) {
        val json = JsonObject()
        json.addProperty("op", 1)
        json.addProperty("d", seq)
        webSocket.send(json.toString())
    }

    private fun errorNotification(title: String, text: String) {
        val intent = Intent(this@DiscordService, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent =
            PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this@DiscordService, "discordPresence")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            //TODO: Request permission
            return
        }
        notificationManager.notify(2, builder.build())
        log("Error Notified")
    }

    fun saveSimpleTestPresence() {
        val file = File(baseContext.cacheDir, "payload")
        //fill with test payload
        val payload = JsonObject()
        payload.addProperty("op", 3)
        payload.add("d", JsonObject().apply {
            addProperty("status", "online")
            addProperty("afk", false)
            add("activities", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "Test")
                    addProperty("type", 0)
                })
            })
        })
        file.writeText(payload.toString())
        log("WebSocket: Simple Test Presence Saved")
    }

    fun setPresence(String: String) {
        log("WebSocket: Sending Presence payload")
        log(String)
        webSocket.send(String)
    }

    fun log(string: String) {
        Log.d("WebSocket_Discord", string)
        //log += "${SimpleDateFormat("HH:mm:ss").format(Calendar.getInstance().time)} $string\n"
    }

    fun saveLogToFile() {
        val fileName = "log_${System.currentTimeMillis()}.txt"

        // ContentValues to store file metadata
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            }
        }

        // Inserting the file in the MediaStore
        val resolver = baseContext.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            val directory =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(directory, fileName)

            // Make sure the Downloads directory exists
            if (!directory.exists()) {
                directory.mkdirs()
            }

            // Use FileProvider to get the URI for the file
            val authority =
                "${baseContext.packageName}.provider" // Adjust with your app's package name
            Uri.fromFile(file)
        }

        // Writing to the file
        uri?.let {
            resolver.openOutputStream(it).use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(log)
                }
            }
        } ?: run {
            log("Error saving log file")
        }
    }

    fun resume() {
        log("Sending Resume payload")
        val d = JsonObject()
        d.addProperty("token", getToken(baseContext))
        d.addProperty("session_id", sessionId)
        d.addProperty("seq", sequence)
        val json = JsonObject()
        json.addProperty("op", 6)
        json.add("d", d)
        log(json.toString())
        webSocket.send(json.toString())
    }

    inner class HeartbeatRunnable : Runnable {
        override fun run() {
            try {
                Thread.sleep(heartbeat.toLong())
                heartbeatSend(webSocket, sequence)
                log("WebSocket: Heartbeat Sent")
            } catch (e: InterruptedException) {
            }
        }
    }

    companion object {
        var SERVICE_RUNNING = false
    }
}

object DiscordServiceRunningSingleton {
    var running = false

}