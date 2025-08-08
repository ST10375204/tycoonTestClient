package vcmsa.projects.tycoontestapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

class GameView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs), Runnable {

    private var thread: Thread? = null
    private var running = false
    private val surfaceHolder: SurfaceHolder = holder
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val cardWidth = 120
    private val cardHeight = 180

    // Consistent spacing/layout constants
    private val cardSpacing = 60
    private val cardStartX = 50
    private val cardBaseY = 220

    private var playerCardCodes = mutableListOf<String>()
    private val playerHand = mutableListOf<Bitmap>()

    private var potCardCodes = mutableListOf<String>()
    private val potCards = mutableListOf<Bitmap>()

    private val selectedOrder = mutableListOf<Int>()

    private val cardBack: Bitmap = generateCardBack(cardWidth, cardHeight)

    interface SelectionListener {
        fun onSelectionChanged(selectedCodes: List<String>)
    }
    var selectionListener: SelectionListener? = null

    fun setPlayerHandFromCodes(codes: List<String>) {
        playerCardCodes = codes.toMutableList()
        playerHand.clear()
        playerHand.addAll(playerCardCodes.map { generateCardImage(it, cardWidth, cardHeight) })
        selectedOrder.clear()
        requestRedraw()
        selectionListener?.onSelectionChanged(getSelectedCardCodes())
    }

    fun setPotFromCodes(nestedCodes: List<List<String>>) {
        potCardCodes = nestedCodes.flatten().toMutableList()
        potCards.clear()
        potCards.addAll(potCardCodes.map { generateCardImage(it, cardWidth, cardHeight) })
        requestRedraw()
    }

    fun clearPot() {
        potCardCodes.clear()
        potCards.clear()
        requestRedraw()
    }

    fun getSelectedCardCodes(): List<String> {
        return selectedOrder.mapNotNull { idx -> playerCardCodes.getOrNull(idx) }
    }

    fun clearSelection() {
        selectedOrder.clear()
        selectionListener?.onSelectionChanged(getSelectedCardCodes())
        requestRedraw()
    }

    private fun requestRedraw() {
        if (!surfaceHolder.surface.isValid) return
        val canvas = surfaceHolder.lockCanvas()
        try {
            drawGame(canvas)
        } finally {
            surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }

    override fun run() {
        while (running) {
            if (!surfaceHolder.surface.isValid) continue
            val canvas = surfaceHolder.lockCanvas()
            drawGame(canvas)
            surfaceHolder.unlockCanvasAndPost(canvas)
            try {
                Thread.sleep(16)
            } catch (_: InterruptedException) {}
        }
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#006400"))

        // Draw pot
        val potStartX = (width / 2) - (potCards.size * cardSpacing / 2)
        val potY = (height / 2) - 80
        for (i in potCards.indices) {
            val x = potStartX + i * cardSpacing
            canvas.drawBitmap(potCards[i], x.toFloat(), potY.toFloat(), paint)
        }

        // Draw player hand
        val handY = height - cardBaseY
        for (i in playerHand.indices) {
            val x = cardStartX + i * cardSpacing
            val drawY = if (selectedOrder.contains(i)) handY - 30 else handY
            canvas.drawBitmap(playerHand[i], x.toFloat(), drawY.toFloat(), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val touchX = event.x
            val touchY = event.y
            val handY = height - cardBaseY

            // Check from top card to bottom for overlap
            for (i in playerHand.indices.reversed()) {
                val x = cardStartX + i * cardSpacing
                val y = if (selectedOrder.contains(i)) handY - 30 else handY
                val rect = Rect(x, y, x + cardWidth, y + cardHeight)
                if (rect.contains(touchX.toInt(), touchY.toInt())) {
                    toggleSelection(i)
                    break
                }
            }
        }
        return true
    }

    private fun toggleSelection(index: Int) {
        if (index !in playerCardCodes.indices) return
        if (selectedOrder.contains(index)) {
            selectedOrder.remove(index)
        } else {
            selectedOrder.add(index)
        }

        selectionListener?.onSelectionChanged(getSelectedCardCodes())
        requestRedraw()
    }

    fun startGame() {
        if (running) return
        running = true
        thread = Thread(this)
        thread?.start()
    }

    fun stopGame() {
        running = false
        try {
            thread?.join()
        } catch (_: InterruptedException) {}
    }

    private fun generateCardImage(cardCode: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.style = Paint.Style.FILL

        if (cardCode.equals("Joker", true) || cardCode == "RJ" || cardCode == "BJ") {
            paint.color = if (cardCode == "RJ") Color.RED else Color.BLACK
            paint.textSize = 36f
            canvas.drawText("JOKER", width / 6f, height / 2f, paint)
            return bitmap
        }

        val rank = if (cardCode.length > 1) cardCode.dropLast(1) else cardCode
        val suitChar = cardCode.lastOrNull() ?: '?'
        val suitSymbol = when (suitChar) {
            'S' -> "♠"
            'H' -> "♥"
            'D' -> "♦"
            'C' -> "♣"
            else -> "?"
        }

        paint.color = if (suitChar == 'H' || suitChar == 'D') Color.RED else Color.BLACK
        paint.textSize = 28f
        canvas.drawText(rank, 10f, 36f, paint)
        canvas.drawText(suitSymbol, 10f, 72f, paint)

        paint.textSize = 56f
        canvas.drawText(suitSymbol, width / 2f - 20f, height / 2f + 20f, paint)

        return bitmap
    }

    private fun generateCardBack(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.BLUE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.color = Color.WHITE
        paint.strokeWidth = 6f
        canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.drawLine(width.toFloat(), 0f, 0f, height.toFloat(), paint)

        return bitmap
    }
}
