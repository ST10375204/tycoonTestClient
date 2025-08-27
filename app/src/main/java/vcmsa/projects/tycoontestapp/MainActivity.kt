package vcmsa.projects.tycoontestapp

import GameViewModel
import android.os.Bundle
import android.os.CountDownTimer
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vcmsa.projects.tycoontestapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: GameViewModel by viewModels() // survives rotation
    private var rules = GameController { log(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.txtLog.movementMethod = ScrollingMovementMethod()
        binding.txtMessages.movementMethod = ScrollingMovementMethod()

        setupUI()
        setupObservers()
    }

    override fun onResume() {
        super.onResume()
        binding.gameView.startGame()
    }

    override fun onPause() {
        super.onPause()
        binding.gameView.stopGame()
    }

    /* --------------------
       UI Setup
    -------------------- */
    private fun setupUI() {
        rules = GameController()

        binding.gameView.selectionListener = object : GameView.SelectionListener {
            override fun onSelectionChanged(selectedCodes: List<String>) {
                log("Selected: ${selectedCodes.joinToString(",")}")
            }
        }

        binding.btnConnect.setOnClickListener {
            binding.edtRoomId.text.toString().toIntOrNull()?.let { sessionId ->
                viewModel.connectSse(sessionId)
            } ?: log("⚠️ Invalid session ID")
        }

        binding.btnSendMessage.setOnClickListener {
            val msg = binding.edtMessage.text.toString()
            binding.edtRoomId.text.toString().toIntOrNull()?.let { sessionId ->
                viewModel.sendMessage(sessionId, msg)
            }
        }

        binding.btnPass.setOnClickListener {
            handlePassButtonClick()
        }

        binding.btnPlay.setOnClickListener {
            val selected = binding.gameView.getSelectedCardCodes()
            if (selected.isEmpty()) {
                log("⚠️ No cards selected to play")
            } else {
                handlePlayButtonClick(selected)
            }
        }

        binding.btnPanel.setOnClickListener {
            val isOpen = binding.sidePanel.translationX == 0f
            val targetX = if (isOpen) -binding.sidePanel.width.toFloat() else 0f
            binding.sidePanel.animate().translationX(targetX).setDuration(300).start()
        }
    }

    /* --------------------
       Observers
    -------------------- */
    private fun setupObservers() {
        viewModel.hand.observe(this) { hand ->
            val sortedHand = rules.sortHand(hand)
            // Update the GameView with the sorted hand
            binding.gameView.setPlayerHandFromCodes(sortedHand)

            // Update ViewModel only if sorting changed the hand
            if (hand != sortedHand) {
                viewModel.updateHand(sortedHand)
            }
        }

        viewModel.pot.observe(this) { binding.gameView.setPotFromCodes(it) }
        viewModel.turnOrder.observe(this) { order ->
            val pid = viewModel.playerId.value
            val counts = viewModel.counts.value ?: emptyMap()
            binding.gameView.setPlayersFromTurnOrder(order, pid, counts)
        }

        viewModel.counts.observe(this) { counts ->
            val pid = viewModel.playerId.value
            val order = viewModel.turnOrder.value ?: emptyList()
            binding.gameView.setPlayersFromTurnOrder(order, pid, counts)
        }

        viewModel.currentTurn.observe(this) { updateTurnUI() }
        viewModel.log.observe(this) { msg -> binding.txtLog.append(msg + "\n") }
        viewModel.lastMessage.observe(this) { msg ->  binding.txtMessages.text = msg }

        viewModel.counts.observe(this) { counts ->
            val pid = viewModel.playerId.value
            val order = viewModel.turnOrder.value ?: emptyList()
            binding.gameView.setPlayersFromTurnOrder(order, pid, counts)

            counts.forEach { (playerId, count) ->
                if (playerId != pid) {
                    binding.gameView.setOtherPlayerCount(playerId, count)
                }
            }
        }


        viewModel.roundMessage.observe(this) { msg ->
            when (msg) {
                "Select cards to exchange" -> {
                    binding.btnPlay.text = "Exchange"
                    binding.btnPass.visibility = View.GONE
                    binding.btnPlay.visibility = View.VISIBLE

                    val sessionId = binding.edtRoomId.text.toString().toIntOrNull()

                    if (sessionId != null) {
                        // Start timer
                        startExchangeTimer(20000L) {
                            // Timer finished -> force exchange
                            val hand = viewModel.hand.value ?: emptyList()
                            val forcedSelection = hand.take(2) // pick first 2 cards
                            if (forcedSelection.isNotEmpty()) {
                                log("Timer expired: forcing exchange with ${forcedSelection.joinToString(",")}")
                                viewModel.exchangeRequest(sessionId, forcedSelection)
                                binding.btnPlay.visibility = View.INVISIBLE
                            }
                        }
                    }

                    binding.btnPlay.setOnClickListener {
                        val selected = binding.gameView.getSelectedCardCodes()
                        if (selected.isEmpty()) {
                            log("⚠️ No cards selected for exchange")
                            return@setOnClickListener
                        }
                        exchangeTimerJob?.cancel()
                        log("Exchange completed early by user!") //kill timer
                        val sessionId = binding.edtRoomId.text.toString().toIntOrNull()
                        if (sessionId != null) {
                            viewModel.exchangeRequest(sessionId, selected)
                            binding.btnPlay.visibility = View.INVISIBLE
                        }
                    }
                }

                "Exchange complete" -> {
                    // Restore default Play button behavior
                    binding.btnPlay.text = "Play"
                    binding.btnPlay.setOnClickListener {
                        val selected = binding.gameView.getSelectedCardCodes()
                        if (selected.isEmpty()) {
                            log("⚠️ No cards selected to play")
                        } else {
                            handlePlayButtonClick(selected)
                        }
                    }
                    updateTurnUI() // re-apply visibility rules
                }
            }
        }


    }

    /* --------------------
       Actions
    -------------------- */


    private fun handlePlayButtonClick(selectedFromView: List<String>) {
        val sessionId = binding.edtRoomId.text.toString().toIntOrNull()
        if (sessionId == null) {
            log("⚠️ Need session")
            return
        }
        val pid = viewModel.playerId.value
        if (pid == null) {
            log("⚠️ Waiting for your playerId from server...")
            return
        }

        val potState = viewModel.pot.value ?: emptyList()
        if (rules.isValidPlayAgainstPot(selectedFromView, potState)) {
            val currentHand = viewModel.hand.value ?: emptyList()
            val remaining = currentHand.size - selectedFromView.size

            // Send play to server
            viewModel.tryPlay(sessionId, selectedFromView, rules)

            // Remove played cards locally
            val newHand = currentHand.toMutableList().apply {
                removeAll(selectedFromView.toSet())
            }
            viewModel.updateHand(newHand)
            binding.gameView.setPlayerHandFromCodes(newHand)

            // Hide buttons after play
            binding.btnPlay.visibility = View.INVISIBLE
            binding.btnPass.visibility = View.INVISIBLE
        } else {
            log("Denied by controller")
        }
        binding.gameView.clearSelection()
    }

    private fun handlePassButtonClick(){
        val sessionId = binding.edtRoomId.text.toString().toIntOrNull()
        if (sessionId == null) {
            log("⚠️ Need session")
            return
        }
        viewModel.sendPass(sessionId)
    }

    /* --------------------
       UI Helpers
    -------------------- */
    private fun updateTurnUI() {
        val myId = viewModel.playerId.value
        val current = viewModel.currentTurn.value
        val isMyTurn = (myId != null && myId == current)
        binding.btnPlay.visibility = if (isMyTurn) View.VISIBLE else View.INVISIBLE
        binding.btnPass.visibility = if (isMyTurn) View.VISIBLE else View.INVISIBLE
    }

    private fun log(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "[$timestamp] $msg"
        println(line)
        binding.txtLog.append(line + "\n")
    }


    /* --------------------
        Game Helpers
    -------------------- */
    private var exchangeTimerJob: Job? = null

    private fun startExchangeTimer(durationMillis: Long = 20000L, onTimeout: () -> Unit) {
        exchangeTimerJob?.cancel() // cancel previous timer if any
        exchangeTimerJob = lifecycleScope.launch {
            val tickInterval = 1000L
            var elapsed = 0L
            while (elapsed < durationMillis) {
                delay(tickInterval)
                elapsed += tickInterval
                val secondsLeft = (durationMillis - elapsed) / 1000
                binding.txtLog.append("Time left: $secondsLeft\n")
            }
            onTimeout() // when timer completes naturally
        }
    }


}