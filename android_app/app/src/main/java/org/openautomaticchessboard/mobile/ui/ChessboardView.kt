package org.openautomaticchessboard.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.github.bhlangonijr.chesslib.Piece
import kotlin.math.min

class ChessboardView(context: Context) : View(context) {
    var pieces: Map<Int, Piece> = emptyMap(); set(value) {
        if (field == value) return
        field = value; invalidate(); updateDescription(); accessibilityHelper.invalidateRoot()
    }
    var sensors: Set<Int>? = null; set(value) {
        if (field == value) return
        field = value; invalidate(); updateDescription(); accessibilityHelper.invalidateRoot()
    }
    var flipped: Boolean = false; set(value) {
        if (field == value) return
        field = value; invalidate(); accessibilityHelper.invalidateRoot()
    }
    var trolley: Pair<Int, Int>? = null; set(value) {
        if (field == value) return
        field = value; invalidate()
    }
    var selectedSquares: Set<Int> = emptySet(); set(value) {
        if (field == value) return
        field = value
        invalidate()
        updateDescription()
        accessibilityHelper.invalidateRoot()
        if (isAttachedToWindow) announceForAccessibility(selectionDescription())
    }
    var onSquareTapped: ((Int) -> Unit)? = null; set(value) {
        field = value
        isClickable = value != null
        accessibilityHelper.invalidateRoot()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light = Color.rgb(227, 215, 190)
    private val dark = Color.rgb(92, 113, 103)
    private val missing = Color.rgb(226, 68, 80)
    private val unexpected = Color.rgb(245, 157, 56)
    private val occupied = Color.rgb(48, 191, 133)
    private val carriage = Color.rgb(45, 210, 226)
    private val selection = Color.rgb(255, 213, 79)
    private val cellRect = RectF()
    private val accessibilityHelper = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            squareAt(x, y) ?: INVALID_ID

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            virtualViewIds.addAll(0..63)
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            node.contentDescription = squareDescription(virtualViewId)
            node.setBoundsInParent(squareBounds(virtualViewId))
            node.isClickable = onSquareTapped != null
            node.isSelected = virtualViewId in selectedSquares
            if (onSquareTapped != null) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || onSquareTapped == null) return false
            activateSquare(virtualViewId)
            return true
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
        updateDescription()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val size = min(availableWidth, availableHeight)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val cell = size / 8f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)

        repeat(8) { screenRank -> repeat(8) { screenFile ->
            val file = if (flipped) 7 - screenFile else screenFile
            val rank = if (flipped) screenRank else 7 - screenRank
            val index = rank * 8 + file
            cellRect.set(left + screenFile * cell, top + screenRank * cell,
                left + (screenFile + 1) * cell, top + (screenRank + 1) * cell)
            val rect = cellRect
            drawSquare(canvas, rect, file, rank)
            drawSelection(canvas, rect, cell, index)
            drawSensorMarkers(canvas, rect, cell, index)
            pieces[index]?.let { drawPiece(canvas, rect, cell, it) }
            drawTrolley(canvas, rect, cell, file, rank)
        } }
    }

    private fun drawSelection(canvas: Canvas, rect: RectF, cell: Float, index: Int) {
        if (index !in selectedSquares) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(4f, cell * .09f)
        paint.color = selection
        val inset = paint.strokeWidth / 2f
        canvas.drawRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onSquareTapped == null) return super.onTouchEvent(event)
        if (event.action != MotionEvent.ACTION_UP) return true
        val square = squareAt(event.x, event.y) ?: return true
        onSquareTapped?.invoke(square)
        performClick()
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun activateSquare(square: Int) {
        onSquareTapped?.invoke(square)
        performClick()
    }

    private fun squareAt(x: Float, y: Float): Int? {
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        if (size <= 0f || x < left || x >= left + size || y < top || y >= top + size) return null
        val screenFile = ((x - left) / (size / 8f)).toInt().coerceIn(0, 7)
        val screenRank = ((y - top) / (size / 8f)).toInt().coerceIn(0, 7)
        val file = if (flipped) 7 - screenFile else screenFile
        val rank = if (flipped) screenRank else 7 - screenRank
        return rank * 8 + file
    }

    private fun squareBounds(square: Int): Rect {
        val file = square % 8
        val rank = square / 8
        val screenFile = if (flipped) 7 - file else file
        val screenRank = if (flipped) rank else 7 - rank
        val size = min(width, height).toFloat()
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val cell = size / 8f
        return Rect(
            (left + screenFile * cell).toInt(),
            (top + screenRank * cell).toInt(),
            (left + (screenFile + 1) * cell).toInt(),
            (top + (screenRank + 1) * cell).toInt(),
        )
    }

    private fun squareDescription(square: Int): String = buildString {
        append(squareName(square))
        pieces[square]?.let { append(", ${it.name.lowercase().replace('_', ' ')}") }
        sensors?.let { append(if (square in it) ", sensor occupied" else ", sensor empty") }
        if (square in selectedSquares) append(", selected")
    }

    private fun selectionDescription(): String = if (selectedSquares.isEmpty()) {
        "Square selection cleared"
    } else {
        "Selected ${selectedSquares.sorted().joinToString(" and ") { squareName(it) }}"
    }

    private fun squareName(square: Int): String =
        "${('a'.code + square % 8).toChar()}${square / 8 + 1}"

    private fun drawSquare(canvas: Canvas, rect: RectF, file: Int, rank: Int) {
        paint.style = Paint.Style.FILL
        paint.color = if ((file + rank) % 2 == 0) dark else light
        canvas.drawRect(rect, paint)
    }

    private fun drawSensorMarkers(canvas: Canvas, rect: RectF, cell: Float, index: Int) {
        val expected = index in pieces
        val sensed = sensors?.contains(index)
        if (sensed == true) {
            paint.style = Paint.Style.FILL
            paint.color = if (expected) occupied else unexpected
            canvas.drawCircle(rect.left + cell * .18f, rect.top + cell * .18f, cell * .09f, paint)
        }
        if (sensors != null && expected && sensed == false) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(3f, cell * .055f)
            paint.color = missing
            canvas.drawRect(rect.left + paint.strokeWidth, rect.top + paint.strokeWidth,
                rect.right - paint.strokeWidth, rect.bottom - paint.strokeWidth, paint)
        }
    }

    private fun drawPiece(canvas: Canvas, rect: RectF, cell: Float, piece: Piece) {
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = cell * .68f
        val pieceColor = if (piece.fenSymbol.first().isUpperCase()) Color.WHITE else Color.rgb(20, 25, 30)
        paint.color = pieceColor
        paint.setShadowLayer(cell * .04f, 0f, cell * .025f,
            if (pieceColor == Color.WHITE) Color.BLACK else Color.WHITE)
        val symbol = unicode[piece] ?: piece.fenSymbol
        val y = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(symbol, rect.centerX(), y, paint)
        paint.clearShadowLayer()
    }

    private fun drawTrolley(canvas: Canvas, rect: RectF, cell: Float, file: Int, rank: Int) {
        val (x, y) = trolley ?: return
        if (x != file || y != rank) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(4f, cell * .075f)
        paint.color = carriage
        canvas.drawCircle(rect.centerX(), rect.centerY(), cell * .42f, paint)
    }

    private fun updateDescription() {
        val sensed = sensors
        val sensorDescription = if (sensed == null) "Sensor state unavailable."
        else "${sensed.size} occupied sensors. ${(pieces.keys - sensed).size} missing and ${(sensed - pieces.keys).size} unexpected."
        val selectedDescription = if (selectedSquares.isEmpty()) "No squares selected."
        else "Selected ${selectedSquares.sorted().joinToString(" and ") { squareName(it) }}."
        val description = "Chessboard. $sensorDescription $selectedDescription"
        if (contentDescription != description) contentDescription = description
    }

    companion object {
        private val unicode = mapOf(
            Piece.WHITE_KING to "♔", Piece.WHITE_QUEEN to "♕", Piece.WHITE_ROOK to "♖",
            Piece.WHITE_BISHOP to "♗", Piece.WHITE_KNIGHT to "♘", Piece.WHITE_PAWN to "♙",
            Piece.BLACK_KING to "♚", Piece.BLACK_QUEEN to "♛", Piece.BLACK_ROOK to "♜",
            Piece.BLACK_BISHOP to "♝", Piece.BLACK_KNIGHT to "♞", Piece.BLACK_PAWN to "♟",
        )
    }
}
