package vcmsa.projects.tycoontestapp

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import vcmsa.projects.tycoontestapp.databinding.ActivityMainBinding
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class PlayRequest(
    val SessionId: Int,
    val PlayerId: String,
    val HandPlayed: List<String>,
    val HandSize: Int,
    val PlayType: Boolean = true
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: OkHttpClient
    private var rules = GameController { log(it) }

    private var eventSource: EventSource? = null

    // Game state
    private var playerId: String? = null
    private var playerHand: List<String> = emptyList()
    private var potState: List<List<String>> = emptyList()
    private var currentTurn: String? = null
    private var turnOrder: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Make debug log scrollable
        binding.txtLog.movementMethod = ScrollingMovementMethod()
        binding.txtMessages.movementMethod = ScrollingMovementMethod()

        // Catch crashes and print stacktrace to txtLog
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            log("CRASH in thread ${thread.name}:\n$sw")
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }

        setupUI()
        setupNetworkClient()
    }

    override fun onResume() {
        super.onResume()
        binding.gameView.startGame()
    }

    override fun onPause() {
        super.onPause()
        binding.gameView.stopGame()
    }

    override fun onDestroy() {
        eventSource?.cancel()
        binding.gameView.stopGame()
        super.onDestroy()
    }

    /* --------------------
       Setup Helpers
    -------------------- */
    private fun setupUI() {
        rules = GameController()

        binding.gameView.selectionListener = object : GameView.SelectionListener {
            override fun onSelectionChanged(selectedCodes: List<String>) {
                log("Selected: ${selectedCodes.joinToString(",")}")
                
            }
        }

        binding.btnConnect.setOnClickListener {
            binding.edtRoomId.text.toString().toIntOrNull()?.let {
                connectSse(it)
            } ?: log("⚠️ Invalid session ID")
        }

        binding.btnSendMessage.setOnClickListener {
            binding.edtRoomId.text.toString().toIntOrNull()?.let { sessionId ->
                sendMessage(sessionId, binding.edtMessage.text.toString())
            }
        }
        binding.btnPass.setOnClickListener(){
            val selectedFromView = binding.gameView.getSelectedCardCodes()
            if (selectedFromView.isEmpty()) {

                SendPlay( binding.edtRoomId.text.toString().toInt(), selectedFromView, playerHand.size, playerId!!)
            }else{
                log("⚠️ No cards selected to play")
            }
        }

        binding.btnPlay.setOnClickListener {
            val selectedFromView = binding.gameView.getSelectedCardCodes()
            if (selectedFromView.isEmpty()) {
                log("⚠️ No cards selected to play")
            } else {
                 handlePlayButtonClick()
            }
        }

        binding.btnPanel.setOnClickListener {
            val isOpen = binding.sidePanel.translationX == 0f
            val targetX = if (isOpen) -binding.sidePanel.width.toFloat() else 0f

            binding.sidePanel.animate()
                .translationX(targetX)
                .setDuration(300)
                .start()
        }


    }

    private fun setupNetworkClient() {
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE requires no timeout
            .build()
    }

    /* --------------------
       Button Actions
    -------------------- */
    private fun handlePlayButtonClick() {
        if (binding.edtRoomId.text.toString().toInt() == null) {
            log("⚠️ Need session")
            return
        }
        if (playerId == null) {
            log("⚠️ Waiting for your playerId from server...")
            return
        }
        val selectedFromView = binding.gameView.getSelectedCardCodes()
            if (rules.isValidPlayAgainstPot(selectedFromView, potState)) {
                SendPlay(binding.edtRoomId.text.toString().toInt(), selectedFromView, playerHand.size - selectedFromView.size, playerId!!)
                playerHand = playerHand.filterNot { it in selectedFromView }
                binding.gameView.setPlayerHandFromCodes(playerHand)
                binding.btnPlay.visibility = View.INVISIBLE
                binding.btnPass.visibility = View.INVISIBLE
            } else {
                log("Denied by controller")
            }

        binding.gameView.clearSelection()
    }

    /* --------------------
       SSE Connection
    -------------------- */
    private fun connectSse(sessionId: Int) {
        eventSource?.cancel()
        resetGameState()
        log("▶ Connecting SSE to session $sessionId...")

        val request = Request.Builder()
            .url("https://tycoontest.onrender.com/sse/gameRoom?id=$sessionId")
            .build()

        eventSource = EventSources.createFactory(client)
            .newEventSource(request, GameEventListener())
    }

    private fun resetGameState() {
        playerId = null
        playerHand = emptyList()
        potState = emptyList()
        currentTurn = null
        turnOrder = emptyList()
        binding.txtLog.text = ""
        binding.txtMessages.text = ""
        log("Game state reset")
    }

    /* --------------------
       Network Calls
    -------------------- */
    private fun SendPlay(sessionId: Int, hand: List<String>, remaining: Int, playerId: String) {
        log("Sending play: session=$sessionId, hand=$hand, remaining=$remaining, playerId=$playerId")
        val play = PlayRequest(sessionId, playerId, hand, remaining, hand.isNotEmpty())
        val body = buildPlayRequestJson(play).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://tycoontest.onrender.com/roundControl/play")
            .post(body)
            .build()

        client.newCall(request).enqueue(simpleCallback("Play"))
    }

    private fun sendMessage(sessionId: Int, message: String) {
        log("Sending message: session=$sessionId, message=$message")
        val url = "https://tycoontest.onrender.com/sse/sendMessage?data=$message&id=$sessionId"
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()
        client.newCall(request).enqueue(simpleCallback("SendMessage"))
    }

    private fun simpleCallback(tag: String) = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            log("$tag failed: ${e.message}")
        }
        override fun onResponse(call: Call, response: Response) {
            log("$tag: HTTP ${response.code}")
            response.close()
        }
    }

    /* --------------------
       SSE Listener
    -------------------- */
    inner class GameEventListener : EventSourceListener() {
        override fun onOpen(es: EventSource, response: Response) {
            log("✓ SSE opened")
        }

        override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
            log("SSE raw: ${data.trim()}")
            val trimmed = data.trim()

            var updateHand: List<String>? = null
            var updatePot: List<List<String>>? = null
            var updateTurn: Boolean? = null
            var countsMap: Map<String, Int>? = null

            try {
                when {
                    trimmed.startsWith("yourId:") -> {
                        playerId = trimmed.removePrefix("yourId:").trim()
                        updateTurn = (currentTurn == playerId)
                        log("Received yourId: $playerId")
                    }
                    trimmed.startsWith("nextPlayer:") -> {
                        currentTurn = trimmed.removePrefix("nextPlayer:").trim()
                        updateTurn = (playerId?.trim() == currentTurn?.trim())
                        log("Next player: $currentTurn")
                    }
                    trimmed.startsWith("hand:") -> {
                        val codes = parseSimpleList(trimmed.removePrefix("hand:"))
                        if (codes != playerHand) {
                            playerHand = codes
                            updateHand = codes
                            log("Updated hand: $codes")
                        }
                    }
                    trimmed.startsWith("message:") -> {
                        appendToDebugView("message: ${trimmed.removePrefix("message:").trim()}")
                    }
                    trimmed.startsWith("pot:") -> {
                        val codes = parseSimpleList(trimmed.removePrefix("pot:"))
                        updatePot = listOf(codes)
                        potState = updatePot
                        log("Updated pot: $codes")
                    }
                    trimmed.startsWith("{") || trimmed.startsWith("[") -> {
                        val obj = JSONObject(trimmed)

                        // turnOrder if present
                        if (obj.has("turnOrder")) {
                            val ja = obj.getJSONArray("turnOrder")
                            val list = (0 until ja.length()).map { i -> ja.getString(i) }
                            turnOrder = list
                            log("Turn order: $turnOrder")
                        }

                        // hand (private)
                        if (obj.has("hand")) {
                            val codes = jsonArrayToList(obj.getJSONArray("hand"))
                            if (codes != playerHand) {
                                playerHand = codes
                                updateHand = codes
                                log("Updated hand from JSON: $codes")
                            }
                        }

                        // nextPlayer
                        if (obj.has("nextPlayer")) {
                            currentTurn = obj.getString("nextPlayer")
                            updateTurn = (playerId?.trim() == currentTurn?.trim())
                            log("Next player from JSON: $currentTurn")
                        }

                        // pot (nested arrays)
                        if (obj.has("pot")) {
                            val potArr = obj.getJSONArray("pot")
                            val nested = (0 until potArr.length()).map { i ->
                                jsonArrayToList(potArr.getJSONArray(i))
                            }
                            updatePot = nested
                            potState = nested
                            log("Updated pot from JSON: $nested")
                        }

                        // remainingCards / handSizes mapping playerId -> count
                        val tmpCounts = mutableMapOf<String, Int>()
                        if (obj.has("remainingCards")) {
                            val rc = obj.getJSONObject("remainingCards")
                            val keys = rc.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val v = rc.getInt(k)
                                tmpCounts[k] = v
                            }
                        } else if (obj.has("handSizes")) {
                            val hs = obj.getJSONObject("handSizes")
                            val keys = hs.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val v = hs.getInt(k)
                                tmpCounts[k] = v
                            }
                        }

                        if (tmpCounts.isNotEmpty()) {
                            countsMap = tmpCounts.toMap()
                            log("Received remainingCards/handSizes: $countsMap")
                        }
                    }
                    else -> log(trimmed)
                }
            } catch (e: Exception) {
                log("JSON parse failed: ${e.message}")
            }

            // Apply UI updates on main thread
            runOnUiThread {
                updateHand?.let {
                    binding.gameView.setPlayerHandFromCodes(it)
                }
                updatePot?.let {
                    binding.gameView.setPotFromCodes(it)
                }
                updateTurn?.let {
                    binding.btnPlay.visibility = if (it) View.VISIBLE else View.INVISIBLE
                    binding.btnPass.visibility = if (it) View.VISIBLE else View.INVISIBLE
                }

                // If we have counts, update the GameView seating and print each user
                countsMap?.let { cmap ->
                    // Ensure turnOrder is at least a guess (if server didn't send turnOrder yet, we'll pass empty)
                    binding.gameView.setPlayersFromTurnOrder(turnOrder, playerId, cmap)

                    // Also show textual listing for debugging
                    val sb = StringBuilder()
                    sb.append("Players counts:\n")
                    val ordering = if (turnOrder.isNotEmpty()) turnOrder else cmap.keys.toList()
                    for (id in ordering) {
                        val c = cmap[id] ?: 0
                        sb.append("${id.take(8)}: $c\n")
                    }
                    binding.txtMessages.append(sb.toString() + "\n")
                }
            }
        }

        override fun onClosed(es: EventSource) {
            log("SSE closed")
        }

        override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
            log("SSE error: ${t?.message}")
        }
    }

    /* --------------------
       Utility
    -------------------- */
    private fun parseSimpleList(raw: String) =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun jsonArrayToList(arr: org.json.JSONArray) =
        List(arr.length()) { arr.getString(it) }

    private fun appendToDebugView(line: String) { runOnUiThread { binding.txtMessages.append(line + "\n\n") } }

    private fun buildPlayRequestJson(p: PlayRequest): String {
        val handArray = p.HandPlayed.joinToString(",", "[", "]") { "\"${escapeJson(it)}\"" }
        return """{"SessionId":${p.SessionId},"PlayerId":"${escapeJson(p.PlayerId)}","HandPlayed":$handArray,"HandSize":${p.HandSize},"PlayType":${p.PlayType}}"""
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\u000C", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun log(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "[$timestamp] $msg"
        println(line)
        runOnUiThread {
            binding.txtLog.append(line + "\n")
        }
    }
}
