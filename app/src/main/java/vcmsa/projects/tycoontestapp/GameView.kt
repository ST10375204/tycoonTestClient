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
    private val cardWidth = 140
    private val cardHeight = 210
    private val cardSpacing = 70
    private val cardBaseY = 240    // hand vertical offset from bottom

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
    private val cacheSize = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    private val cardBitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
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

    // ---------- Public API ----------
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

            val num = playerHand.size
            val startX = computeHandStartX(num)

            for (i in playerHand.indices.reversed()) {
                val x = startX + i * cardSpacing
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
        if (!selectedOrder.remove(index)) selectedOrder.add(index)
        selectionListener?.onSelectionChanged(getSelectedCardCodes())
        needsRedraw.set(true)
    }

    // ---------- Render loop ----------
    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        while (running) {
            if (!surfaceHolder.surface.isValid) { sleepQuiet(16); continue }
            if (needsRedraw.getAndSet(false)) {
                val canvas = try { surfaceHolder.lockCanvas() } catch (_: Throwable) { null }
                if (canvas != null) {
                    try { drawGame(canvas) } catch (_: Throwable) { /* keep loop alive */ }
                    finally {
                        try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Throwable) { /* ignore */ }
                    }
                }
            }
            sleepQuiet(16)
        }
    }

    private fun drawGame(canvas: Canvas) {
        if (tableBitmap == null || tableBitmapWidth != width || tableBitmapHeight != height) {
            tableBitmap = loadDrawableBitmapFromAny(listOf("table", "table_bg", "felt_table", "table_image"), width, height, preserveAspect = true)
            tableBitmapWidth = width
            tableBitmapHeight = height
        }
        tableBitmap?.let { tb ->
            val src = Rect(0, 0, tb.width, tb.height)
            val dst = Rect(0, 0, width, height)
            paint.alpha = 255
            canvas.drawBitmap(tb, src, dst, paint)
        } ?: canvas.drawColor(Color.parseColor("#006400"))

        drawPotStyled(canvas)
        drawPlayersAroundTable(canvas)

        val handY = height - cardBaseY
        paint.alpha = 255
        val startX = computeHandStartX(playerHand.size)
        for (i in playerHand.indices) {
            playerHand.getOrNull(i)?.let { bmp ->
                val x = startX + i * cardSpacing
                val drawY = if (selectedOrder.contains(i)) handY - 30 else handY
                canvas.drawBitmap(bmp, x.toFloat(), drawY.toFloat(), paint)
            }
        }
    }

    // compute starting X to center the hand on screen horizontally
    private fun computeHandStartX(numCards: Int): Int {
        if (numCards <= 0) return (width / 2) - (cardWidth / 2)
        val totalWidth = ((numCards - 1) * cardSpacing) + cardWidth
        return ((width - totalWidth) / 2f).toInt()
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
        canvas.drawOval(rimRect, paint)

        val innerRect = RectF(rimRect.left + 8f, rimRect.top + 8f, rimRect.right - 8f, rimRect.bottom - 8f)
        paint.color = Color.argb(170, 60, 60, 60)
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

    // ---------- Lifecycle ----------
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
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    // ---------- Card bitmap creation / loading ----------
    private fun getOrCreateCardBitmap(cardCode: String): Bitmap {
        cardBitmapCache.get(cardCode)?.let { return it }

        val desiredW = cardWidth
        val desiredH = cardHeight

        val composed = composeCardFromAssets(cardCode, desiredW, desiredH)
        val bmp = composed ?: generateCardImage(cardCode, desiredW, desiredH)

        cardBitmapCache.put(cardCode, bmp)
        return bmp
    }

    private fun getOrCreateCardBackBitmap(): Bitmap {
        val key = "__CARD_BACK__"
        cardBitmapCache.get(key)?.let { return it }

        val candidates = listOf("card_back", "cardback", "back", "jback")
        var bmp: Bitmap? = null
        for (n in candidates) {
            bmp = loadDrawableBitmap(n, (cardWidth * 0.75f).toInt(), (cardHeight * 0.75f).toInt(), preserveAspect = true)
            if (bmp != null) break
        }

        if (bmp == null) {
            val scale = 0.55f
            val w = (cardWidth * scale).toInt()
            val h = (cardHeight * scale).toInt()
            val gen = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(gen)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val rectF = RectF(0f, 0f, w.toFloat(), h.toFloat())
            p.color = Color.rgb(18, 77, 126)
            canvas.drawRoundRect(rectF, 8f, 8f, p)
            p.color = Color.argb(40, 255, 255, 255); p.strokeWidth = 6f
            for (i in -h until w step 18) canvas.drawLine(i.toFloat(), 0f, (i + h).toFloat(), h.toFloat(), p)
            p.style = Paint.Style.STROKE; p.color = Color.BLACK; p.strokeWidth = 3f
            canvas.drawRoundRect(rectF, 8f, 8f, p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = h * 0.18f; p.textAlign = Paint.Align.CENTER
            canvas.drawText("♠", w / 2f, h / 2f + p.textSize / 3f, p)
            bmp = gen
        }

        cardBitmapCache.put(key, bmp)
        return bmp
    }

    private fun clearAndRecycleBitmaps() {
        fun recycleList(bitmaps: CopyOnWriteArrayList<Bitmap>) {
            for (b in bitmaps) if (!b.isRecycled) b.recycle()
            bitmaps.clear()
        }
        recycleList(playerHand)
        recycleList(potCards)
        synchronized(cardBitmapCache) { cardBitmapCache.evictAll() }
    }

    // ---------- Card art fallback (text/glyph) ----------
    private fun generateCardImage(cardCode: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        p.color = Color.WHITE; canvas.drawRoundRect(rect, width * 0.04f, width * 0.04f, p)
        p.style = Paint.Style.STROKE; p.color = Color.BLACK; p.strokeWidth = max(3f, width * 0.02f)
        canvas.drawRoundRect(rect, width * 0.04f, width * 0.04f, p)
        p.style = Paint.Style.FILL

        if (cardCode.equals("Joker", true) || cardCode.equals("RJ", true) || cardCode.equals("NJ", true) || cardCode.equals("BJ", true)) {
            p.color = if (cardCode.equals("RJ", true)) Color.RED else Color.BLACK
            p.textSize = height * 0.16f; p.textAlign = Paint.Align.CENTER
            canvas.drawText("JOKER", width / 2f, height / 2f + p.textSize / 3f, p)
            return bitmap
        }

        val rank = if (cardCode.length > 1) cardCode.dropLast(1) else cardCode
        val suitChar = cardCode.lastOrNull() ?: '?'
        val isRed = suitChar.equals('H', true) || suitChar.equals('D', true)
        p.color = if (isRed) Color.RED else Color.BLACK

        val cornerSize = width * 0.18f
        p.textSize = cornerSize; p.textAlign = Paint.Align.LEFT
        canvas.drawText(rank, 12f, cornerSize + 6f, p)
        canvas.save(); canvas.rotate(180f, width / 2f, height / 2f)
        canvas.drawText(rank, 12f, cornerSize + 6f, p); canvas.restore()

        val suitGlyph = when (suitChar.uppercaseChar()) {
            'S' -> "♠"; 'H' -> "♥"; 'D' -> "♦"; 'C' -> "♣"
            else -> "?"
        }
        p.textSize = height * 0.28f; p.textAlign = Paint.Align.CENTER
        canvas.drawText(suitGlyph, width / 2f, height / 2f + p.textSize / 3f, p)
        return bitmap
    }

    // ---------- Compose card faces from assets tailored to your filenames ----------
    private fun composeCardFromAssets(cardCode: String, w: Int, h: Int): Bitmap? {
        val code = cardCode.trim()
        if (code.isEmpty()) return null

        // JOKER handling (compose onto white base to avoid transparency showing table)
        val jokerNames = listOf("face_joker_red", "face_joker_black", "joker_red", "joker_black", "face_joker")
        if (code.equals("Joker", true) || code.equals("RJ", true) || code.equals("NJ", true) || code.equals("BJ", true)) {
            for (name in jokerNames) {
                loadDrawableBitmap(name, (w * 0.9f).toInt(), (h * 0.9f).toInt(), preserveAspect = true)?.let { jb ->
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c = Canvas(out); val p = Paint(Paint.ANTI_ALIAS_FLAG)
                    p.color = Color.WHITE; val rect = RectF(0f,0f,w.toFloat(),h.toFloat()); c.drawRoundRect(rect, w*0.04f, w*0.04f, p)
                    p.style = Paint.Style.STROKE; p.color = Color.BLACK; p.strokeWidth = max(2f, w * 0.02f); c.drawRoundRect(rect, w*0.04f, w*0.04f, p)
                    p.style = Paint.Style.FILL
                    val src = Rect(0,0,jb.width,jb.height)
                    val dstW = jb.width.toFloat(); val dstH = jb.height.toFloat()
                    val dst = RectF((w-dstW)/2f, (h-dstH)/2f, (w+dstW)/2f, (h+dstH)/2f)
                    c.drawBitmap(jb, src, dst, p)
                    return out
                }
            }
            return null
        }

        // parse rank & suit (T accepted as 10)
        val suitChar = code.lastOrNull() ?: return null
        var rankRaw = code.dropLast(1)
        if (rankRaw.equals("T", true)) rankRaw = "10"
        val rankStr = rankRaw.toUpperCase(Locale.US)

        val suitName = when (suitChar.uppercaseChar()) {
            'H' -> "hearts"
            'D' -> "diamonds"
            'S' -> "spades"
            'C' -> "clubs"
            else -> return null
        }
        val isRed = suitName == "hearts" || suitName == "diamonds"

        // Rank candidates (your files include rank_*.png and some separate face files)
        val rankCandidates = mutableListOf<String>()
        rankCandidates.add("rank_${rankStr.toLowerCase(Locale.US)}_${if (isRed) "red" else "black"}") // rank_4_red
        rankCandidates.add("rank_${rankStr.toLowerCase(Locale.US)}") // rank_4
        // face names you provided: jack_*, queen_*, king_*
        if (rankStr in listOf("J", "Q", "K")) {
            val name = when(rankStr) {
                "J" -> "jack"
                "Q" -> "queen"
                "K" -> "king"
                else -> rankStr.toLowerCase(Locale.US)
            }
            rankCandidates.add("${name}_${if (isRed) "red" else "black"}") // jack_red
            rankCandidates.add(name) // jack
        }

        // suit candidates (you don't have suit pngs listed, but try common names)
        val suitCandidates = listOf("suit_$suitName", suitName, "pip_$suitName", "${suitName}_small")

        // face candidates (k q j might have dedicated face images)
        val faceCandidates = mutableListOf<String>()
        if (rankStr in listOf("K", "Q", "J")) {
            val faceName = when(rankStr) {
                "K" -> "king"
                "Q" -> "queen"
                "J" -> "jack"
                else -> rankStr.toLowerCase(Locale.US)
            }
            faceCandidates.add("${faceName}_${if (isRed) "red" else "black"}") // king_red
            faceCandidates.add(faceName) // king
        }
        // joker face candidates were handled above

        // sizes
        val cornerW = (w * 0.22f).toInt().coerceAtLeast(10)
        val cornerH = (h * 0.22f).toInt().coerceAtLeast(10)
        val pipSize = (min(w,h) * 0.12f).toInt().coerceAtLeast(6)
        val faceW = (w * 0.6f).toInt()
        val faceH = (h * 0.6f).toInt()

        val rankBmp = loadDrawableBitmapFromAny(rankCandidates, cornerW, cornerH, preserveAspect = true)
        val suitBmp = loadDrawableBitmapFromAny(suitCandidates, pipSize, pipSize, preserveAspect = true)
        val faceBmp = loadDrawableBitmapFromAny(faceCandidates, faceW, faceH, preserveAspect = true)

        if (rankBmp == null && suitBmp == null && faceBmp == null) return null

        // Compose onto white rounded card base
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; val rectf = RectF(0f,0f,w.toFloat(),h.toFloat()); c.drawRoundRect(rectf, w*0.04f, w*0.04f, p)
        p.style = Paint.Style.STROKE; p.color = Color.BLACK; p.strokeWidth = max(2f, w*0.02f); c.drawRoundRect(rectf, w*0.04f, w*0.04f, p)
        p.style = Paint.Style.FILL

        // corner rank (bmp or text fallback)
        if (rankBmp != null) {
            c.drawBitmap(rankBmp, 8f, 8f, p)
            c.save(); c.rotate(180f, w/2f, h/2f); c.drawBitmap(rankBmp, 8f, 8f, p); c.restore()
        } else {
            p.color = if (isRed) Color.RED else Color.BLACK
            val textSize = cornerH * 0.9f
            p.textSize = textSize; p.textAlign = Paint.Align.LEFT
            c.drawText(rankStr, 8f, textSize + 4f, p)
            c.save(); c.rotate(180f, w/2f, h/2f); c.drawText(rankStr, 8f, textSize + 4f, p); c.restore()
        }

        // face art large if present
        if (faceBmp != null) {
            val src = Rect(0,0, faceBmp.width, faceBmp.height)
            val dstW = faceBmp.width.toFloat(); val dstH = faceBmp.height.toFloat()
            val dst = RectF((w-dstW)/2f, (h-dstH)/2f, (w+dstW)/2f, (h+dstH)/2f)
            c.drawBitmap(faceBmp, src, dst, p)
            return out
        }

        // numeric pips or single glyph fallback
        val numericRank = when(rankStr) {
            "A" -> 1
            "J","Q","K" -> 0
            else -> rankStr.toIntOrNull() ?: 0
        }
        val pipPositions = getPipPositions(numericRank, w, h)
        if (pipPositions.isNotEmpty() && suitBmp != null) {
            val src = Rect(0,0,suitBmp.width, suitBmp.height)
            for (pt in pipPositions) {
                val x = pt.first - (pipSize/2f)
                val y = pt.second - (pipSize/2f)
                val dst = RectF(x, y, x + pipSize, y + pipSize)
                c.drawBitmap(suitBmp, src, dst, p)
            }
        } else {
            val suitGlyph = when(suitName) {
                "hearts" -> "♥"
                "diamonds" -> "♦"
                "spades" -> "♠"
                "clubs" -> "♣"
                else -> "?"
            }
            p.color = if (isRed) Color.RED else Color.BLACK
            p.textSize = h * 0.28f; p.textAlign = Paint.Align.CENTER
            c.drawText(suitGlyph, w/2f, h/2f + p.textSize/3f, p)
        }

        return out
    }

    private fun getPipPositions(numericRank: Int, w: Int, h: Int): List<Pair<Float, Float>> {
        if (numericRank <= 0) return emptyList()
        val leftX = w * 0.25f; val midX = w * 0.5f; val rightX = w * 0.75f
        val topY = h * 0.18f; val upperMidY = h * 0.32f; val centerY = h * 0.5f; val lowerMidY = h * 0.68f; val bottomY = h * 0.82f
        return when(numericRank) {
            1 -> listOf(Pair(midX, centerY))
            2 -> listOf(Pair(midX, upperMidY), Pair(midX, lowerMidY))
            3 -> listOf(Pair(midX, upperMidY), Pair(midX, centerY), Pair(midX, lowerMidY))
            4 -> listOf(Pair(leftX, upperMidY), Pair(rightX, upperMidY), Pair(leftX, lowerMidY), Pair(rightX, lowerMidY))
            5 -> getPipPositions(4,w,h) + listOf(Pair(midX, centerY))
            6 -> listOf(Pair(leftX, topY), Pair(leftX, centerY), Pair(leftX, bottomY), Pair(rightX, topY), Pair(rightX, centerY), Pair(rightX, bottomY))
            7 -> getPipPositions(6,w,h) + listOf(Pair(midX, topY))
            8 -> getPipPositions(7,w,h) + listOf(Pair(midX, bottomY))
            9 -> listOf(
                Pair(leftX, topY), Pair(midX, topY), Pair(rightX, topY),
                Pair(leftX, centerY), Pair(midX, centerY), Pair(rightX, centerY),
                Pair(leftX, bottomY), Pair(midX, bottomY), Pair(rightX, bottomY)
            )
            10 -> listOf(
                Pair(leftX, topY), Pair(midX, topY), Pair(rightX, topY),
                Pair(leftX, upperMidY), Pair(rightX, upperMidY),
                Pair(leftX, lowerMidY), Pair(rightX, lowerMidY),
                Pair(leftX, bottomY), Pair(midX, bottomY), Pair(rightX, bottomY)
            )
            else -> emptyList()
        }
    }

    // ---------- Resource loading helpers ----------
    private fun loadDrawableBitmap(resName: String, reqW: Int, reqH: Int, preserveAspect: Boolean = false): Bitmap? {
        if (resName.isBlank()) return null
        val id = resources.getIdentifier(resName, "drawable", context.packageName)
        if (id == 0) return null
        val src = BitmapFactory.decodeResource(resources, id) ?: return null
        if (!preserveAspect) {
            return if (src.width == reqW && src.height == reqH) src else Bitmap.createScaledBitmap(src, reqW, reqH, true)
        }
        // preserve aspect: scale to fit inside reqW x reqH
        val scale = min(reqW.toFloat() / src.width.toFloat(), reqH.toFloat() / src.height.toFloat())
        val dstW = max(1, (src.width * scale).toInt())
        val dstH = max(1, (src.height * scale).toInt())
        return Bitmap.createScaledBitmap(src, dstW, dstH, true)
    }

    private fun loadDrawableBitmapFromAny(names: List<String>, w: Int, h: Int, preserveAspect: Boolean = false): Bitmap? {
        for (n in names) {
            if (n.isBlank()) continue
            loadDrawableBitmap(n, w, h, preserveAspect)?.let { return it }
            // try variant with underscores / hyphens normalized
            val alt = n.replace("-", "_").replace("__", "_")
            if (alt != n) loadDrawableBitmap(alt, w, h, preserveAspect)?.let { return it }
        }
        return null
    }
}
