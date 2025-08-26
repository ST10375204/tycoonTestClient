import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import vcmsa.projects.tycoontestapp.GameController
import java.io.IOException
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper

class GameViewModel : ViewModel() {

    // OkHttpClient for SSE + HTTP
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE should never timeout
        .cache(null) // disable caching
        .build()

    private var eventSource: EventSource? = null
    private var savedSessionId: Int? = null

    // --- LiveData ---
    private val _roundMessage = MutableLiveData<String?>()
    val roundMessage: LiveData<String?> = _roundMessage

    private val _playerId = MutableLiveData<String?>()
    val playerId: LiveData<String?> = _playerId

    private val _hand = MutableLiveData<List<String>>(emptyList())
    val hand: LiveData<List<String>> = _hand

    private val _pot = MutableLiveData<List<List<String>>>(emptyList())
    val pot: LiveData<List<List<String>>> = _pot

    private val _currentTurn = MutableLiveData<String?>()
    val currentTurn: LiveData<String?> = _currentTurn

    private val _turnOrder = MutableLiveData<List<String>>(emptyList())
    val turnOrder: LiveData<List<String>> = _turnOrder

    private val _counts = MutableLiveData<Map<String, Int>>(emptyMap())
    val counts: LiveData<Map<String, Int>> = _counts

    private val _lastMessage = MutableLiveData<String>()
    val lastMessage: LiveData<String> = _lastMessage

    private val _log = MutableLiveData<String>()
    val log: LiveData<String> = _log

    // --- SSE ---
    fun connectSse(sessionId: Int) {
        savedSessionId = sessionId
        disconnectSse()
        log("▶ Connecting SSE to $sessionId")

        val request = Request.Builder()
            .url("https://tycoontest.onrender.com/sse/gameRoom?id=$sessionId")
            .build()

        eventSource = EventSources.createFactory(client)
            .newEventSource(request, GameEventListener())
    }

    fun disconnectSse() {
        eventSource?.cancel()
        eventSource = null
        log("⏹ SSE disconnected")
    }

    // --- Actions ---
    fun sendPlay(sessionId: Int, hand: List<String>, remaining: Int) {
        val pid = _playerId.value ?: return
        log("Sending play: $hand")

        val json = buildPlayRequestJson(sessionId, pid, hand, remaining)
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://tycoontest.onrender.com/roundControl/play")
            .post(body)
            .build()

        client.newCall(req).enqueue(simpleCallback("Play"))
    }

    fun tryPlay(sessionId: Int, selected: List<String>, controller: GameController): Boolean {
        val potState = _pot.value ?: emptyList()
        if (selected.isEmpty()) {
            log("Blocked: empty play. Use Pass instead.")
            return false
        }
        if (!controller.isValidPlayAgainstPot(selected, potState)) {
            log("Blocked: invalid play: $selected vs $potState")
            return false
        }
        val remaining = (_hand.value?.size ?: 0) - selected.size
        sendPlay(sessionId, selected, remaining)
        return true
    }

    fun sendPass(sessionId: Int) {
        val pid = _playerId.value ?: return
        val json = """
            {
                "SessionId": $sessionId,
                "PlayerId": "$pid",
                "HandPlayed": [],
                "HandSize": ${_hand.value?.size ?: 0},
                "PlayType": false
            }
        """.trimIndent()

        client.newCall(
            Request.Builder()
                .url("https://tycoontest.onrender.com/roundControl/play")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
        ).enqueue(simpleCallback("Pass"))
    }

    fun exchangeRequest(sessionId: Int, cardsToGive: List<String>) {
        val pid = _playerId.value ?: return
        val currentHand = _hand.value ?: emptyList()
        log("Sending exchange: give=$cardsToGive, hand=$currentHand")

        val json = buildExchangeRequestJson(sessionId, pid, cardsToGive, currentHand)
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://tycoontest.onrender.com/roundControl/exchange")
            .post(body)
            .build()

        client.newCall(req).enqueue(simpleCallback("Exchange"))
    }

    fun sendMessage(sessionId: Int, msg: String) {
        val url = "https://tycoontest.onrender.com/sse/sendMessage?data=$msg&id=$sessionId"
        val req = Request.Builder()
            .url(url)
            .post("".toRequestBody(null))
            .build()

        client.newCall(req).enqueue(simpleCallback("SendMessage"))
    }

    // --- SSE Listener ---
    private inner class GameEventListener : EventSourceListener() {
        override fun onOpen(es: EventSource, response: Response) {
            log("SSE opened")
        }

        override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
            try {
                val obj = JSONObject(data)
                when (obj.getString("type")) {
                    "yourId" -> obj.optString("id")?.takeIf { it.isNotEmpty() }?.let {
                        _playerId.postValue(it)
                        log("Assigned playerId=$it")
                    }
                    "message" -> {
                        val from = obj.optString("from", "unknown")
                        val text = obj.optString("text", "")
                        val formatted = "[$from]: $text"
                        _lastMessage.postValue(formatted)
                        log("Message received: $formatted")
                    }
                    "round_start" -> handleRoundStart(obj)
                    "play_update" -> handlePlayUpdate(obj)
                    "exchange_result" -> handleExchangeResult(obj)
                    "exchange_complete" -> log("Exchange completed globally between ${obj.optJSONArray("pair")}")
                }
            } catch (e: Exception) {
                log("JSON parse error: ${e.message}")
            }
        }

        override fun onClosed(es: EventSource) {
            log("SSE closed")
        }

        override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
            log("SSE error: ${t?.message}")
            disconnectSse() // do not autoreconnect automatically
            savedSessionId?.let { sid ->
                Handler(Looper.getMainLooper()).postDelayed({ connectSse(sid) }, 1500L)
            }
        }

        private fun handleRoundStart(obj: JSONObject) {
            val roundNum = obj.optInt("round", -1)
            _hand.postValue(jsonArrayToList(obj.getJSONArray("hand")))
            _pot.postValue(emptyList())
            _currentTurn.postValue(obj.optString("nextPlayer"))
            _turnOrder.postValue(jsonArrayToList(obj.getJSONArray("turnOrder")))
            _counts.postValue(parseCounts(obj.optJSONObject("remainingCards") ?: JSONObject()))

            if (roundNum != -1) {
                _log.postValue("Round $roundNum started")
                _roundMessage.postValue("Round $roundNum")

                if (roundNum in 2..3) {
                    log("No cards selected for exchange")
                    _log.postValue("Exchange phase: please choose cards to give.")
                    _roundMessage.postValue("Select cards to exchange")
                }
            } else {
                _roundMessage.postValue("New Round")
            }
        }

        private fun handlePlayUpdate(obj: JSONObject) {
            _currentTurn.postValue(obj.optString("nextPlayer", _currentTurn.value))
            obj.optJSONArray("pot")?.let { potJson ->
                val potList = (0 until potJson.length()).map { i ->
                    jsonArrayToList(potJson.getJSONArray(i))
                }
                _pot.postValue(potList)
            }
            _counts.postValue(parseCounts(obj.optJSONObject("remainingCards") ?: JSONObject()))
            log("Play update. Next=${_currentTurn.value}")
        }

        private fun handleExchangeResult(obj: JSONObject) {
            val newHand = jsonArrayToList(obj.getJSONArray("hand"))
            _hand.postValue(newHand)
            _roundMessage.postValue("Exchange complete")
            log("Exchange finished. Hand updated: $newHand")
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectSse()
    }

    // --- Helpers ---
    private fun log(msg: String) {
        _log.postValue(msg)
    }

    private fun jsonArrayToList(arr: JSONArray) = List(arr.length()) { arr.getString(it) }

    private fun parseCounts(obj: JSONObject): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optInt(k, 0)
        }
        return map
    }

    private fun buildExchangeRequestJson(
        sessionId: Int,
        playerId: String,
        cardsToGive: List<String>,
        cardsInHand: List<String>
    ): String {
        val giveArray = cardsToGive.joinToString(",", "[", "]") { "\"$it\"" }
        val handArray = cardsInHand.joinToString(",", "[", "]") { "\"$it\"" }
        return """{"sessionId":$sessionId,"playerId":"$playerId","cardsToGive":$giveArray,"cardsInHand":$handArray}"""
    }

    private fun buildPlayRequestJson(
        sessionId: Int,
        playerId: String,
        hand: List<String>,
        remaining: Int
    ): String {
        val handArray = hand.joinToString(",", "[", "]") { "\"$it\"" }
        return """{"SessionId":$sessionId,"PlayerId":"$playerId","HandPlayed":$handArray,"HandSize":$remaining,"PlayType":${hand.isNotEmpty()}}"""
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

    fun updateHand(newHand: List<String>) {
        _hand.postValue(newHand)
    }
}
