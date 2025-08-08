package vcmsa.projects.tycoontestapp

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
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
    private var eventSource: EventSource? = null
    private lateinit var rules: GameController

    private var playerId: String? = null
    private var playerHand: List<String> = emptyList()
    private var potState: List<List<String>> = emptyList()
    private var currentTurn: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.debugTextView.movementMethod = ScrollingMovementMethod()
        binding.debugTextView2.movementMethod = ScrollingMovementMethod()

        rules = GameController()

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        binding.gameView.selectionListener = object : GameView.SelectionListener {
            override fun onSelectionChanged(selectedCodes: List<String>) {
                runOnUiThread {
                    println("Selected: ${selectedCodes.joinToString(",")}")
                    binding.messageInput2.setText(selectedCodes.joinToString(","))
                }
            }
        }

        binding.connectButton.setOnClickListener {
            val session = binding.roomIdInput.text.toString().toIntOrNull()
            if (session == null) println("⚠️ Invalid session ID")
            else connectSse(session)
        }

        binding.postButton.setOnClickListener {
            binding.roomIdInput.text.toString().toIntOrNull()?.let { sessionId ->
                sendMessage(sessionId, binding.messageInput.text.toString())
            }
        }

        binding.sendPlayButton.setOnClickListener {
            val session = binding.roomIdInput.text.toString().toIntOrNull()
            if (session == null) {
                println("⚠️ Need session")
                return@setOnClickListener
            }

            if (playerId == null) {
                println("⚠️ Waiting for your playerId from server...")
                return@setOnClickListener
            }

            val selectedFromView = binding.gameView.getSelectedCardCodes()
            val handToSend = selectedFromView

            if (handToSend.isEmpty()) {
                println("⚠️ No cards selected to play")
                sendPlayManualJson(session, handToSend, playerHand.size, playerId!!)
                binding.sendPlayButton.visibility = View.INVISIBLE
                return@setOnClickListener
            }

            val isValid = rules.isValidPlayAgainstPot(handToSend, potState)
            if (isValid) {
                sendPlayManualJson(session, handToSend, playerHand.size - handToSend.size, playerId!!)
                playerHand = playerHand.filterNot { it in handToSend }
                binding.gameView.setPlayerHandFromCodes(playerHand)
                binding.sendPlayButton.visibility = View.INVISIBLE
            } else {
                println("Denied by controller")
            }

            binding.gameView.clearSelection()
        }
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
        super.onDestroy()
        eventSource?.cancel()
        binding.gameView.stopGame()
    }

    private fun sendPlayManualJson(sessionId: Int, hand: List<String>, remaining: Int, playerId: String) {
        val play = PlayRequest(sessionId, playerId, hand, remaining, hand.isNotEmpty())
        val json = buildPlayRequestJson(play)
        println("→ Sending JSON: $json")

        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://tycoontest.onrender.com/roundControl/play")
            .post(body)
            .build()

        client.newCall(request).enqueue(simpleCallback("Play"))
    }

    private fun sendMessage(sessionId: Int, message: String) {
        val url = "https://tycoontest.onrender.com/sse/test?data=$message&id=$sessionId"
        val request = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()
        client.newCall(request).enqueue(simpleCallback("Sending message"))
    }

    private fun connectSse(sessionId: Int) {
        eventSource?.cancel()
        playerId = null
        playerHand = emptyList()
        potState = emptyList()
        binding.debugTextView2.text = ""
        println("▶ Connecting SSE...")

        val request = Request.Builder()
            .url("https://tycoontest.onrender.com/sse/gameRoom?id=$sessionId")
            .build()

        eventSource = EventSources.createFactory(client)
            .newEventSource(request, object : EventSourceListener() {
                override fun onOpen(es: EventSource, response: Response) {
                    println("✓ SSE opened")
                }

                override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                    val trimmed = data.trim()

                    when {
                        trimmed.startsWith("yourId:") -> {
                            playerId = trimmed.removePrefix("yourId:").trim()
                            println("Assigned playerId: $playerId")

                            // Don't try to check turnId here yet because it might be null
                            runOnUiThread {
                                if (currentTurn != null && playerId == currentTurn) {
                                    println("It's your turn (late match)! Showing Play button.")
                                    binding.sendPlayButton.visibility = View.VISIBLE
                                } else {
                                    println("⏳ Waiting for nextPlayer info...")
                                    binding.sendPlayButton.visibility = View.INVISIBLE
                                }
                            }
                        }


                        trimmed.startsWith("nextPlayer:") -> {
                            currentTurn = trimmed.removePrefix("nextPlayer:").trim()
                            println("Received nextPlayer: $currentTurn")

                            runOnUiThread {
                                val myId = playerId?.trim()
                                val turnId = currentTurn?.trim()
                                println("Comparing myId=[$myId] to turnId=[$turnId]")

                                if (!myId.isNullOrBlank() && myId == turnId) {
                                    println("It's your turn! Showing Play button.")
                                    binding.sendPlayButton.visibility = View.VISIBLE
                                } else {
                                    println("Not your turn. Hiding Play button.")
                                    binding.sendPlayButton.visibility = View.INVISIBLE
                                }
                            }
                        }

                        trimmed.startsWith("hand:") -> {
                            val codes = trimmed.removePrefix("hand:").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            playerHand = codes
                            runOnUiThread {
                                binding.gameView.setPlayerHandFromCodes(codes)
                                println("Received hand: ${codes.joinToString(",")}")
                            }
                        }

                        trimmed.startsWith("pot:") -> {
                            val codes = trimmed.removePrefix("pot:").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            val nested = listOf(codes)
                            potState = nested
                            runOnUiThread {
                                binding.gameView.setPotFromCodes(nested)
                                println("Received pot: ${codes.joinToString(",")}")
                            }
                        }

                        trimmed.startsWith("{") || trimmed.startsWith("[") -> {
                            println(trimmed)
                            try {
                                val obj = org.json.JSONObject(trimmed)
                                if (obj.has("hand")) {
                                    val arr = obj.getJSONArray("hand")
                                    val codes = (0 until arr.length()).map { arr.getString(it) }
                                    playerHand = codes
                                    runOnUiThread {
                                        binding.gameView.setPlayerHandFromCodes(codes)
                                        println("Received hand (json): ${codes.joinToString(",")}")
                                    }
                                }

                                if (obj.has("nextPlayer")) {
                                    currentTurn = obj.getString("nextPlayer")
                                    runOnUiThread {
                                        val myId = playerId?.trim()
                                        val turnId = currentTurn?.trim()
                                        println("🔍 Comparing myId=[$myId] to turnId=[$turnId]")

                                        if (!myId.isNullOrBlank() && myId == turnId) {
                                            println("✅ It's your turn! Showing Play button.")
                                            binding.sendPlayButton.visibility = View.VISIBLE
                                        } else {
                                            println("⛔ Not your turn. Hiding Play button.")
                                            binding.sendPlayButton.visibility = View.INVISIBLE
                                        }
                                    }
                                }


                                if (obj.has("pot")) {
                                    val parr = obj.getJSONArray("pot")
                                    val nestedCodes = mutableListOf<List<String>>()

                                    for (i in 0 until parr.length()) {
                                        val group = parr.getJSONArray(i)
                                        val groupList = mutableListOf<String>()
                                        for (j in 0 until group.length()) {
                                            groupList.add(group.getString(j))
                                        }
                                        nestedCodes.add(groupList)
                                    }

                                    potState = nestedCodes
                                    runOnUiThread {
                                        binding.gameView.setPotFromCodes(nestedCodes)
                                        println("Received pot (json): ${nestedCodes.flatten().joinToString(",")}")
                                    }
                                }
                            } catch (e: Exception) {
                                println("JSON parse failed: ${e.message}")
                            }
                        }

                        else -> println(trimmed)
                    }
                }

                override fun onClosed(es: EventSource) {
                    println("SSE closed")
                }

                override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                    println("SSE error: ${t?.message}")
                }
            })
    }

    private fun simpleCallback(tag: String) = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            println("$tag failed: ${e.message}")
        }

        override fun onResponse(call: Call, response: Response) {
            println("$tag: HTTP ${response.code}")
            response.close()
        }
    }

    private fun buildPlayRequestJson(p: PlayRequest): String {
        val handArray = p.HandPlayed.joinToString(",", "[", "]") { "\"${escapeJson(it)}\"" }
        val playerEsc = escapeJson(p.PlayerId)
        return """{"SessionId":${p.SessionId},"PlayerId":"$playerEsc","HandPlayed":$handArray,"HandSize":${p.HandSize},"PlayType":${p.PlayType}}"""
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
