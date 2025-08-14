package vcmsa.projects.tycoontestapp

import android.content.Context
import android.graphics.*
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class GameView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs), Runnable {

    // --- Render thread state ---
    @Volatile private var running = false
    private var thread: Thread? = null
    private val needsRedraw = AtomicBoolean(false)

    // --- Surface / paint ---
    private val surfaceHolder: SurfaceHolder = holder
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- Layout constants ---
    private val cardWidth = 140    // increased from 120
    private val cardHeight = 210   // increased from 180
    private val cardSpacing = 70   // wider spacing so cards don't overlap too much
    private val cardStartX = 40    // nudge start x a little left
    private val cardBaseY = 240    // push hand a bit up so larger cards fit

    // --- Model/state (thread-safe lists to avoid CME) ---
    private var playerCardCodes = CopyOnWriteArrayList<String>()
    private var playerHand = CopyOnWriteArrayList<Bitmap>()

    private var potCardCodes = CopyOnWriteArrayList<String>()
    private var potCards = CopyOnWriteArrayList<Bitmap>()

    private val selectedOrder = CopyOnWriteArrayList<Int>()

    // --- player layout state ---
    // orderedPlayers[0] is local player (bottom). Others follow clockwise.
    private var orderedPlayers: List<String> = emptyList()
    private var playerCounts: Map<String, Int> = emptyMap()
    private var localPlayerId: String? = null

    // --- Background / table bitmap cache ---
    @Volatile private var tableBitmap: Bitmap? = null
    private var tableBitmapWidth = 0
    private var tableBitmapHeight = 0

    // --- Simple card art cache (avoids regenerating bitmaps constantly) ---
    private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt() // 1/8 of mem in KB
    private val cardBitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            // do not recycle here; safe-recycling handled in clearAndRecycleBitmaps()
        }
    }

    interface SelectionListener {
        fun onSelectionChanged(selectedCodes: List<String>)
    }
    var selectionListener: SelectionListener? = null

    init {
        surfaceHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (running && thread?.isAlive != true) startRenderThread()
                needsRedraw.set(true)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // invalidate cached table so we re-scale to new dimensions
                tableBitmap = null
                tableBitmapWidth = 0
                tableBitmapHeight = 0
                needsRedraw.set(true)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                stopRenderThread()
            }
        })
    }

    // ---------- New API: provide turn order + local id + counts ----------
    fun setPlayersFromTurnOrder(turnOrder: List<String>, localId: String?, counts: Map<String, Int>) {
        if (localId == null) {
            orderedPlayers = turnOrder.toList()
        } else {
            val idx = turnOrder.indexOf(localId)
            orderedPlayers = if (idx >= 0) {
                val rotated = turnOrder.drop(idx) + turnOrder.take(idx)
                if (rotated.isNotEmpty() && rotated[0] == localId) rotated else listOf(localId) + rotated.filter { it != localId }
            } else {
                listOf(localId) + turnOrder.filter { it != localId }
            }
        }
        localPlayerId = localId
        playerCounts = counts.toMap()
        needsRedraw.set(true)
    }

    // ---------- Existing public API ----------
    fun setPlayerHandFromCodes(codes: List<String>) {
        playerCardCodes.clear()
        playerCardCodes.addAll(codes)

        playerHand.clear()
        for (code in playerCardCodes) {
            playerHand.add(getOrCreateCardBitmap(code))
        }

        selectedOrder.clear()
        needsRedraw.set(true)
        selectionListener?.onSelectionChanged(getSelectedCardCodes())
    }

    fun setPotFromCodes(nestedCodes: List<List<String>>) {
        potCardCodes.clear()
        potCardCodes.addAll(nestedCodes.flatten())

        potCards.clear()
        for (code in potCardCodes) {
            potCards.add(getOrCreateCardBitmap(code))
        }

        needsRedraw.set(true)
    }

    fun clearPot() {
        potCardCodes.clear()
        potCards.clear()
        needsRedraw.set(true)
    }

    fun getSelectedCardCodes(): List<String> =
        selectedOrder.mapNotNull { idx -> playerCardCodes.getOrNull(idx) }

    fun clearSelection() {
        selectedOrder.clear()
        selectionListener?.onSelectionChanged(getSelectedCardCodes())
        needsRedraw.set(true)
    }

    // ---------- Input ----------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val touchX = event.x.toInt()
            val touchY = event.y.toInt()
            val handY = height - cardBaseY

            // iterate topmost first (rightmost)
            for (i in playerHand.indices.reversed()) {
                val x = cardStartX + i * cardSpacing
                val y = if (selectedOrder.contains(i)) handY - 30 else handY
                val rect = Rect(x, y, x + cardWidth, y + cardHeight)
                if (rect.contains(touchX, touchY)) {
                    toggleSelection(i)
                    break
                }
            }
        }
        return true
    }

    private fun toggleSelection(index: Int) {
        if (index !in playerCardCodes.indices) return

        if (!selectedOrder.remove(index)) {
            selectedOrder.add(index)
        }
        selectionListener?.onSelectionChanged(getSelectedCardCodes())
        needsRedraw.set(true)
    }

    // ---------- Render loop ----------
    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        while (running) {
            if (!surfaceHolder.surface.isValid) {
                sleepQuiet(16)
                continue
            }

            if (needsRedraw.getAndSet(false)) {
                val canvas = try {
                    surfaceHolder.lockCanvas()
                } catch (_: Throwable) {
                    null
                }
                if (canvas != null) {
                    try {
                        drawGame(canvas)
                    } catch (_: Throwable) {
                        // swallow to keep loop alive
                    } finally {
                        try {
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (_: Throwable) { /* ignore */ }
                    }
                }
            }

            // Idle cap
            sleepQuiet(16)
        }
    }

    private fun drawGame(canvas: Canvas) {
        // draw table background if available, else fallback to green
        if (tableBitmap == null || tableBitmapWidth != width || tableBitmapHeight != height) {
            tableBitmap = loadDrawableBitmapFromAny(listOf("table", "table_bg", "felt_table", "table_image"), width, height)
            tableBitmapWidth = width
            tableBitmapHeight = height
        }

        tableBitmap?.let { tb ->
            val src = Rect(0, 0, tb.width, tb.height)
            val dst = Rect(0, 0, width, height)
            paint.alpha = 255
            canvas.drawBitmap(tb, src, dst, paint)
        } ?: run {
            canvas.drawColor(Color.parseColor("#006400"))
        }

        // Pot - draw plate + tossed pile
        drawPotStyled(canvas)

        // Opponents
        drawPlayersAroundTable(canvas)

        // Player hand (local)
        val handY = height - cardBaseY
        paint.alpha = 255
        for (i in playerHand.indices) {
            playerHand.getOrNull(i)?.let { bmp ->
                val x = cardStartX + i * cardSpacing
                val drawY = if (selectedOrder.contains(i)) handY - 30 else handY
                canvas.drawBitmap(bmp, x.toFloat(), drawY.toFloat(), paint)
            }
        }
    }

    // ---------- Pot styling ----------
    private fun drawPotStyled(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rimW = min(width, height) * 0.52f
        val rimH = rimW * 0.58f

        val rimRect = RectF(cx - rimW/2f, cy - rimH/2f, cx + rimW/2f, cy + rimH/2f)
        paint.color = Color.argb(230, 25, 25, 25)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawOval(rimRect, paint)

        val innerRect = RectF(rimRect.left + 8f, rimRect.top + 8f, rimRect.right - 8f, rimRect.bottom - 8f)
        paint.color = Color.argb(170, 60, 60, 60)
        paint.alpha = 255
        canvas.drawOval(innerRect, paint)

        if (potCards.isEmpty()) return

        val scale = 0.9f
        val drawW = cardWidth * scale
        val drawH = cardHeight * scale

        val grouped = potCardCodes.withIndex().groupBy({ it.value }, { it.index })

        for ((origIndex, code) in potCardCodes.withIndex()) {
            val bmp = potCards.getOrNull(origIndex) ?: continue

            val jitter = stableJitter(code, origIndex)
            val angle = jitter.first
            val dx = jitter.second.first * 0.6f
            val dy = jitter.second.second * 0.6f

            val duplicates = grouped[code] ?: listOf()
            val posInGroup = duplicates.indexOf(origIndex).coerceAtLeast(0)
            val dupOffsetX = posInGroup * 8f
            val dupOffsetY = -posInGroup * 6f
            val gx = cx + dx + dupOffsetX
            val gy = cy + dy + dupOffsetY

            canvas.save()
            canvas.translate(gx, gy)
            canvas.rotate(angle)
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = RectF(-drawW/2f, -drawH/2f, drawW/2f, drawH/2f)
            paint.alpha = 255
            canvas.drawBitmap(bmp, src, dst, paint)
            canvas.restore()
        }

        paint.style = Paint.Style.FILL
    }

    // deterministic jitter generator
    private fun stableJitter(code: String, index: Int): Pair<Float, Pair<Float, Float>> {
        val GOLDEN_UL = 0x9E3779B97F4A7C15UL
        val LCG_MULT_UL = 6364136223846793005UL
        val LCG_ADD_UL  = 1442695040888963407UL

        var state = (code.hashCode().toLong().toULong() xor (index.toLong().toULong() * GOLDEN_UL))
        state = (state * LCG_MULT_UL + LCG_ADD_UL)

        val angle = ((state % 31UL).toInt().toFloat() / 31f) * 30f - 15f
        val dx = (((state / 31UL) % 21UL).toInt().toFloat() / 21f) * 60f - 30f
        val dy = (((state / 31UL / 21UL) % 21UL).toInt().toFloat() / 21f) * 30f - 15f

        return Pair(angle, Pair(dx, dy))
    }

    // ---------- Opponent card-back drawing aligned to side ----------
    private fun drawPlayersAroundTable(canvas: Canvas) {
        if (orderedPlayers.isEmpty()) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 120f

        val nonLocalPlayers = orderedPlayers.filter { it != localPlayerId }
        val totalNonLocal = nonLocalPlayers.size
        if (totalNonLocal == 0) return

        val backBmp = getOrCreateCardBackBitmap()
        val scale = 0.75f
        val backW = backBmp.width.toFloat() * scale
        val backH = backBmp.height.toFloat() * scale
        val backSpacing = backW * 0.52f

        for (k in nonLocalPlayers.indices) {
            val playerId = nonLocalPlayers[k]
            val angleDeg = 90f + (k + 1) * (360f / (totalNonLocal + 1))
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val px = cx + (radius * cos(angleRad)).toFloat()
            val py = cy + (radius * sin(angleRad)).toFloat()

            val count = playerCounts[playerId] ?: 0
            val showCount = count.coerceAtMost(12)

            val normalized = ((angleDeg % 360) + 360) % 360
            val orientationAngle = when {
                normalized in 45f..135f -> 0f
                normalized in 225f..315f -> 180f
                normalized in 135f..225f -> -90f
                else -> 90f
            }

            canvas.save()
            canvas.translate(px, py)
            canvas.rotate(orientationAngle)

            val totalWidth = ((showCount - 1).coerceAtLeast(0)) * backSpacing + backW
            val startX = -totalWidth / 2f
            val startY = -backH / 2f

            paint.alpha = 255
            val src = Rect(0, 0, backBmp.width, backBmp.height)
            for (j in 0 until showCount) {
                val x = startX + j * backSpacing
                val depthJitter = sin((j + playerId.hashCode()).toFloat()) * 3f
                val dst = RectF(x, startY + depthJitter, x + backW, startY + depthJitter + backH)
                canvas.drawBitmap(backBmp, src, dst, paint)
            }

            paint.color = Color.LTGRAY
            paint.textSize = 16f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(playerId.take(6), 0f, backH / 2f + 18f, paint)

            canvas.restore()
        }
    }

    // ---------- Lifecycle from Activity ----------
    fun startGame() {
        if (running) return
        running = true
        needsRedraw.set(true)
        if (surfaceHolder.surface.isValid) startRenderThread()
    }

    fun stopGame() {
        running = false
        stopRenderThread()
        clearAndRecycleBitmaps()
    }

    // ---------- Internals ----------
    private fun startRenderThread() {
        if (thread?.isAlive == true) return
        thread = Thread(this, "GameViewRender").apply { start() }
    }

    private fun stopRenderThread() {
        thread?.let {
            try {
                it.interrupt()
                it.join(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun getOrCreateCardBitmap(cardCode: String): Bitmap {
        cardBitmapCache.get(cardCode)?.let { return it }

        val desiredW = cardWidth
        val desiredH = cardHeight

        val candidates = mutableListOf<String>()
        candidates.add(normalizeCardResName(cardCode))
        candidates.add("j" + cardCode.toLowerCase(Locale.US).replace("[^a-z0-9]".toRegex(), ""))
        val alt = cardCode
            .replace("♠", "s")
            .replace("♣", "c")
            .replace("♥", "h")
            .replace("♦", "d")
        candidates.add("j" + alt.toLowerCase(Locale.US).replace("[^a-z0-9]".toRegex(), ""))

        // common joker filename variants (user mentioned rj/nj)
        candidates.add("jrj")
        candidates.add("jbj")
        candidates.add("jrj".toLowerCase(Locale.US))
        candidates.add("jr")
        candidates.add("rj")
        candidates.add("nj")
        candidates.add("j_rj")
        candidates.add("j_nj")

        var bmp: Bitmap? = null
        for (name in candidates) {
            bmp = loadDrawableBitmap(name, desiredW, desiredH)
            if (bmp != null) break
        }

        if (bmp == null) {
            bmp = generateCardImage(cardCode, desiredW, desiredH)
        }

        cardBitmapCache.put(cardCode, bmp)
        return bmp
    }

    private fun getOrCreateCardBackBitmap(): Bitmap {
        val key = "__CARD_BACK__"
        cardBitmapCache.get(key)?.let { return it }

        // try a few drawable names
        val backCandidates = listOf("card_back", "jback", "back", "j_back", "cardback")
        var bmp: Bitmap? = null
        for (n in backCandidates) {
            bmp = loadDrawableBitmap(n, (cardWidth * 0.75f).toInt(), (cardHeight * 0.75f).toInt())
            if (bmp != null) break
        }

        if (bmp == null) {
            // fallback to generated back (similar look to previous code)
            val scale = 0.55f
            val w = (cardWidth * scale).toInt()
            val h = (cardHeight * scale).toInt()
            val gen = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(gen)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            val rectF = RectF(0f, 0f, w.toFloat(), h.toFloat())
            p.color = Color.rgb(18, 77, 126)
            canvas.drawRoundRect(rectF, 8f, 8f, p)

            p.color = Color.argb(40, 255, 255, 255)
            p.strokeWidth = 6f
            for (i in -h until w step 18) {
                canvas.drawLine(i.toFloat(), 0f, (i + h).toFloat(), h.toFloat(), p)
            }

            p.style = Paint.Style.STROKE
            p.color = Color.BLACK
            p.strokeWidth = 3f
            canvas.drawRoundRect(rectF, 8f, 8f, p)
            p.style = Paint.Style.FILL

            p.color = Color.WHITE
            p.textSize = h * 0.18f
            p.textAlign = Paint.Align.CENTER
            canvas.drawText("♠", w / 2f, h / 2f + p.textSize / 3f, p)

            bmp = gen
        }

        cardBitmapCache.put(key, bmp)
        return bmp
    }

    private fun clearAndRecycleBitmaps() {
        fun recycleList(bitmaps: CopyOnWriteArrayList<Bitmap>) {
            for (b in bitmaps) {
                if (!b.isRecycled) b.recycle()
            }
            bitmaps.clear()
        }
        recycleList(playerHand)
        recycleList(potCards)
        synchronized(cardBitmapCache) {
            cardBitmapCache.evictAll()
        }
    }

    // ---------- Card art ----------
    private fun generateCardImage(cardCode: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Base
        p.color = Color.WHITE
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

        // Border
        p.color = Color.BLACK
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.style = Paint.Style.FILL

        // Jokers
        if (cardCode.equals("Joker", true) || cardCode.equals("RJ", true) || cardCode.equals("NJ", true) || cardCode.equals("BJ", true)) {
            p.color = if (cardCode.equals("RJ", true)) Color.RED else Color.BLACK
            p.textSize = 36f
            canvas.drawText("JOKER", width / 6f, height / 2f, p)
            return bitmap
        }

        val rank = if (cardCode.length > 1) cardCode.dropLast(1) else cardCode
        val suitChar = cardCode.lastOrNull() ?: '?'
        val suitSymbol = when (suitChar.toUpperCase()) {
            'S' -> "♠"
            'H' -> "♥"
            'D' -> "♦"
            'C' -> "♣"
            else -> "?"
        }

        p.color = if (suitChar.toUpperCase() == 'H' || suitChar.toUpperCase() == 'D') Color.RED else Color.BLACK
        p.textSize = 28f
        canvas.drawText(rank, 10f, 36f, p)
        canvas.drawText(suitSymbol, 10f, 72f, p)

        p.textSize = 56f
        canvas.drawText(suitSymbol, width / 2f - 20f, height / 2f + 20f, p)

        return bitmap
    }

    // ---------- Resource loading helpers ----------
    private fun normalizeCardResName(cardCode: String): String {
        var s = cardCode.trim().toLowerCase(Locale.US)
        s = s.replace("♠", "s")
            .replace("♣", "c")
            .replace("♥", "h")
            .replace("♦", "d")
        s = s.replace("[^a-z0-9]".toRegex(), "")
        return "j$s"
    }

    private fun loadDrawableBitmap(resName: String, w: Int, h: Int): Bitmap? {
        if (resName.isBlank()) return null
        val id = resources.getIdentifier(resName, "drawable", context.packageName)
        if (id == 0) return null
        // decode with bounds first to avoid memory surprises (optional)
        val src = BitmapFactory.decodeResource(resources, id) ?: return null
        return if (src.width == w && src.height == h) {
            src
        } else {
            Bitmap.createScaledBitmap(src, w, h, true)
        }
    }

    private fun loadDrawableBitmapFromAny(names: List<String>, w: Int, h: Int): Bitmap? {
        for (n in names) {
            val b = loadDrawableBitmap(n, w, h)
            if (b != null) return b
        }
        return null
    }

}
