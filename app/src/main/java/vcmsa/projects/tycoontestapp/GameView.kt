package vcmsa.projects.tycoontestapp

import android.content.Context
import android.graphics.*
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.*

class GameView(context: Context, attrs: AttributeSet? = null) : SurfaceView(context, attrs), Runnable {

    @Volatile private var running = false
    @Volatile private var roundMessage: String? = null
    @Volatile private var roundMessageShownAt: Long = 0
    private var thread: Thread? = null
    private var needsRedraw = false

    private val surfaceHolder: SurfaceHolder = holder
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Layout
    private val cardWidth = 140
    private val cardHeight = 210
    private val cardSpacing = 70
    private val cardBaseY = 240

    // Model/state
    private val playerCardCodes = mutableListOf<String>()
    private val playerHand = mutableListOf<Bitmap>()
    private val selectedOrder = mutableListOf<Int>()
    private val otherHands = mutableMapOf<String, List<Bitmap>>()

    private val potGroups = mutableListOf<List<String>>()
    private val potCardCodes = mutableListOf<String>()
    private val potCards = mutableListOf<Bitmap>()

    private var orderedPlayers: List<String> = emptyList()
    private var playerCounts: Map<String, Int> = emptyMap()
    private var localPlayerId: String? = null

    // Background / table
    @Volatile private var tableBitmap: Bitmap? = null

    // Card cache
    private val cardCache = mutableMapOf<String, Bitmap>()
    private val cardBackCache by lazy { loadCardBack() }

    interface SelectionListener { fun onSelectionChanged(selectedCodes: List<String>) }
    var selectionListener: SelectionListener? = null

    init {
        surfaceHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { if (running) startRenderThread(); needsRedraw=true }
            override fun surfaceChanged(holder: SurfaceHolder, f:Int, w:Int, h:Int) { tableBitmap=null; needsRedraw=true }
            override fun surfaceDestroyed(holder: SurfaceHolder) { stopRenderThread() }
        })
    }

    // ---------- Public ----------
    fun startGame() { if (running) return; running=true; needsRedraw=true; if (surfaceHolder.surface.isValid) startRenderThread() }
    fun stopGame() { running=false; stopRenderThread(); cardCache.clear(); playerHand.clear(); potCards.clear() }

    fun setPlayersFromTurnOrder(turnOrder: List<String>, localId: String?, counts: Map<String, Int>) {
        localPlayerId = localId
        orderedPlayers = if (localId == null) turnOrder else {
            val idx = turnOrder.indexOf(localId)
            if (idx>=0) turnOrder.drop(idx) + turnOrder.take(idx) else listOf(localId) + turnOrder.filter{it!=localId}
        }
        playerCounts = counts
        needsRedraw=true
    }

    fun setPlayerHandFromCodes(codes: List<String>) {
        playerCardCodes.clear(); playerCardCodes.addAll(codes)
        playerHand.clear(); playerHand.addAll(codes.map{ getOrCreateCard(it) })
        selectedOrder.clear(); selectionListener?.onSelectionChanged(getSelectedCardCodes()); needsRedraw=true
    }

    fun setPotFromCodes(groups: List<List<String>>) {
        potGroups.clear(); potGroups.addAll(groups)
        potCardCodes.clear(); potCardCodes.addAll(groups.flatten())
        potCards.clear(); potCards.addAll(potCardCodes.map{ getOrCreateCard(it) })
        needsRedraw=true
    }

    fun setOtherPlayerCount(playerId: String, count: Int) {
        otherHands[playerId] = List(count) { cardBackCache }
        needsRedraw = true
    }

    fun clearPot() { potGroups.clear(); potCardCodes.clear(); potCards.clear(); needsRedraw=true }
    fun getSelectedCardCodes(): List<String> = selectedOrder.mapNotNull { playerCardCodes.getOrNull(it) }
    fun clearSelection() { selectedOrder.clear(); selectionListener?.onSelectionChanged(getSelectedCardCodes()); needsRedraw=true }
    fun showRoundMessage(msg:String) { roundMessage=msg; roundMessageShownAt=System.currentTimeMillis(); needsRedraw=true }

    // ---------- Input ----------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action==MotionEvent.ACTION_DOWN) {
            val touchX = event.x.toInt()
            val touchY = event.y.toInt()
            val handY = height - cardBaseY
            val startX = computeHandStartX(playerHand.size)
            for (i in playerHand.indices.reversed()) {
                val x = startX + i*cardSpacing
                val y = if (selectedOrder.contains(i)) handY-30 else handY
                if (Rect(x,y,x+cardWidth,y+cardHeight).contains(touchX,touchY)) { toggleSelection(i); break }
            }
        }
        return true
    }

    private fun toggleSelection(index: Int) {
        if (!selectedOrder.remove(index)) selectedOrder.add(index)
        selectionListener?.onSelectionChanged(getSelectedCardCodes()); needsRedraw=true
    }

    // ---------- Render ----------
    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        while (running) {
            if (!surfaceHolder.surface.isValid) { Thread.sleep(16); continue }
            if (needsRedraw) {
                needsRedraw=false
                val canvas = try { surfaceHolder.lockCanvas() } catch (_:Throwable){null}
                canvas?.let { drawGame(it); surfaceHolder.unlockCanvasAndPost(it) }
            }
            Thread.sleep(16)
        }
    }

    private fun drawOtherPlayersHands(canvas: Canvas) {
        val spacing = 40f
        val cardW = cardBackCache.width.toFloat()
        val cardH = cardBackCache.height.toFloat()

        val playerIds = otherHands.keys.toList()

        playerIds.forEachIndexed { idx, playerId ->
            val hand = otherHands[playerId] ?: return@forEachIndexed

            when (idx) {
                0 -> { // Top center
                    val y = 50f
                    val totalW = (hand.size - 1) * spacing + cardW
                    val startX = (width - totalW) / 2f
                    hand.forEachIndexed { i, bmp ->
                        val x = startX + i * spacing
                        canvas.drawBitmap(bmp, x, y, paint)
                    }
                    drawPlayerLabel(canvas, playerId, width / 2f, y - 10f)
                }

                1 -> { // Left middle (vertical stack)
                    val x = 50f
                    val totalH = (hand.size - 1) * spacing + cardH
                    val startY = (height - totalH) / 2f
                    hand.forEachIndexed { i, bmp ->
                        val y = startY + i * spacing
                        canvas.drawBitmap(bmp, x, y, paint)
                    }
                    drawPlayerLabel(canvas, playerId, x + cardW / 2f, startY - 20f)
                }

                2 -> { // Right middle (vertical stack)
                    val x = width - cardW - 50f
                    val totalH = (hand.size - 1) * spacing + cardH
                    val startY = (height - totalH) / 2f
                    hand.forEachIndexed { i, bmp ->
                        val y = startY + i * spacing
                        canvas.drawBitmap(bmp, x, y, paint)
                    }
                    drawPlayerLabel(canvas, playerId, x + cardW / 2f, startY - 20f)
                }

                else -> { // Fallback: stack extras at top
                    val y = 50f + idx * (cardH + 20f)
                    hand.forEachIndexed { i, bmp ->
                        canvas.drawBitmap(bmp, 100f + i * spacing, y, paint)
                    }
                    drawPlayerLabel(canvas, playerId, 100f, y - 10f)
                }
            }
        }
    }

    private fun drawPlayerLabel(canvas: Canvas, playerId: String, cx: Float, cy: Float) {
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 32f
        paint.color = Color.WHITE
        paint.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        val count = playerCounts[playerId] ?: 0
        canvas.drawText("$playerId ($count)", cx, cy, paint)
        paint.clearShadowLayer()
    }

    private fun drawGame(canvas: Canvas) {
        // background
        if (tableBitmap==null) tableBitmap=loadDrawableBitmap("table",width,height,true)
        tableBitmap?.let{canvas.drawBitmap(it, null, Rect(0,0,width,height), paint)} ?: canvas.drawColor(Color.parseColor("#006400"))

        drawPot(canvas)
        drawRoundMessage(canvas)

        val startX = computeHandStartX(playerHand.size)
        val handY = height - cardBaseY
        drawOtherPlayersHands(canvas)
        playerHand.forEachIndexed { i,bmp ->
            val y = if (selectedOrder.contains(i)) handY-30 else handY
            canvas.drawBitmap(bmp, startX + i*cardSpacing.toFloat(), y.toFloat(), paint)
        }
    }

    private fun computeHandStartX(num:Int) = ((width - ((num-1)*cardSpacing + cardWidth))/2f).toInt()

    private fun drawRoundMessage(canvas: Canvas) {
        val msg=roundMessage ?: return
        if (System.currentTimeMillis()-roundMessageShownAt>3000) { roundMessage=null; return }
        paint.textAlign=Paint.Align.CENTER; paint.textSize=64f; paint.color=Color.WHITE
        paint.setShadowLayer(6f,2f,2f,Color.BLACK)
        canvas.drawText(msg,width/2f,height/2f,paint)
        paint.clearShadowLayer()
    }

    private fun drawPot(canvas: Canvas) {
        if (potGroups.isEmpty()) return
        val cx=width/2f; val cy=height/2f
        val rimW=min(width,height)*0.52f; val rimH=rimW*0.58f
        val rim=RectF(cx-rimW/2f,cy-rimH/2f,cx+rimW/2f,cy+rimH/2f)
        paint.color=Color.argb(230,25,25,25); paint.style=Paint.Style.FILL
        canvas.drawOval(rim,paint)
        val inner=RectF(rim.left+8f,rim.top+8f,rim.right-8f,rim.bottom-8f)
        paint.color=Color.argb(170,60,60,60)
        canvas.drawOval(inner,paint)

        val scale=0.9f; val drawW=cardWidth*scale; val drawH=cardHeight*scale
        potGroups.forEachIndexed { groupIndex, group ->
            val (angle, offset)=stableJitter(group.joinToString(""), groupIndex)
            val (dx,dy)=offset
            val centerX=cx+dx; val centerY=cy+dy
            val spacing=drawW*0.4f
            val groupWidth=(group.size-1)*spacing
            val startX=centerX-groupWidth/2f
            group.forEachIndexed { i, code ->
                val bmp=potCards.getOrNull(potCardCodes.indexOf(code)) ?: return@forEachIndexed
                val gx=startX+i*spacing; val gy=centerY
                canvas.save(); canvas.translate(gx,gy); canvas.rotate(angle)
                canvas.drawBitmap(bmp,null,RectF(-drawW/2,-drawH/2,drawW/2,drawH/2),paint)
                canvas.restore()
            }
        }
    }

    // ---------- Bitmaps ----------
    private fun getOrCreateCard(code:String): Bitmap = cardCache.getOrPut(code){ composeCardFromAssets(code) }

    private fun loadCardBack(): Bitmap = loadDrawableBitmap("card_back",cardWidth,cardHeight,true)

    private fun loadDrawableBitmap(name: String, w: Int, h: Int, scale: Boolean = false): Bitmap {
        val resId = resources.getIdentifier(name, "drawable", context.packageName)
        if (resId == 0) return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val b = BitmapFactory.decodeResource(resources, resId)
        return if (scale) Bitmap.createScaledBitmap(b, w, h, true) else b
    }

    private fun composeCardFromAssets(code: String): Bitmap {
        val bmp = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // white card background + border
        paint.color = Color.WHITE; paint.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat(), 12f, 12f, paint)
        paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        canvas.drawRoundRect(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat(), 12f, 12f, paint)

        // determine filenames
        val (rankFile, suitFile) = when(code.uppercase()) {
            "RJ" -> "rank_joker_red" to null
            "BJ" -> "rank_joker_black" to null
            else -> {
                val rank = code.dropLast(1).lowercase()
                val suitChar = code.last().uppercaseChar()
                val rankName = when(rank) {
                    "j" -> "rank_j"
                    "q" -> "rank_q"
                    "k" -> "rank_k"
                    "a" -> "rank_a"
                    else -> "rank_$rank"
                }
                val color = if (suitChar=='H'||suitChar=='D') "red" else "black"
                val suitName = when(suitChar) {
                    'H' -> "suit_hearts"
                    'D' -> "suit_diamonds"
                    'S' -> "suit_spades"
                    'C' -> "suit_clubs"
                    else -> null
                }
                var rankName_=rankName+"_"
                "$rankName_$color" to suitName
            }
        }

        // draw rank
        try {
            val rankBitmap = loadDrawableBitmap(rankFile, 60, 60, true)
            canvas.drawBitmap(rankBitmap, 12f, 12f, paint)
        } catch (_: Throwable) { }

        // draw suit
        suitFile?.let {
            try {
                val suitBitmap = loadDrawableBitmap(it, 50, 50, true)
                canvas.drawBitmap(suitBitmap, cardWidth-60f, cardHeight-60f, paint)
            } catch (_: Throwable) {}
        }

        return bmp
    }

    // ---------- Utils ----------
    private fun stableJitter(key:String, index:Int): Pair<Float, Pair<Float,Float>> {
        val seed=key.hashCode()+index*31
        val angle=((seed%31)-15).toFloat()
        val dx=((seed/31)%21-10).toFloat()
        val dy=((seed/17)%21-10).toFloat()
        return angle to (dx to dy)
    }

    private fun startRenderThread() { if(thread==null || !thread!!.isAlive) { thread=Thread(this); thread!!.start() } }
    private fun stopRenderThread() { thread?.join(500); thread=null }
}
