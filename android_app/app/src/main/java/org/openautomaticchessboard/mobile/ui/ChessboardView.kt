package org.openautomaticchessboard.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.github.bhlangonijr.chesslib.Piece
import kotlin.math.min

class ChessboardView(context: Context) : View(context) {
    var pieces: Map<Int, Piece> = emptyMap(); set(value) { field = value; invalidate(); updateDescription() }
    var sensors: Set<Int>? = null; set(value) { field = value; invalidate(); updateDescription() }
    var flipped: Boolean = false; set(value) { field = value; invalidate() }
    var trolley: Pair<Int, Int>? = null; set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light = Color.rgb(227, 215, 190)
    private val dark = Color.rgb(92, 113, 103)
    private val missing = Color.rgb(226, 68, 80)
    private val unexpected = Color.rgb(245, 157, 56)
    private val occupied = Color.rgb(48, 191, 133)
    private val carriage = Color.rgb(45, 210, 226)
    private val cellRect = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; updateDescription() }

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
            drawSensorMarkers(canvas, rect, cell, index)
            pieces[index]?.let { drawPiece(canvas, rect, cell, it) }
            drawTrolley(canvas, rect, cell, file, rank)
        } }
    }

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
        val description = if (sensed == null) "Chessboard. Sensor state unavailable."
        else "Chessboard. ${sensed.size} occupied sensors. ${(pieces.keys - sensed).size} missing and ${(sensed - pieces.keys).size} unexpected."
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
