package com.example.rtintensity.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import com.example.rtintensity.color.IntensityClassColors
import com.example.rtintensity.processor.IntensityClass

/**
 * 震度階級履歴帯(3-3節)。
 *
 * 【色凡例ではない】このViewは「震度0〜7の色一覧」を並べた凡例ではなく、
 * 実際に観測された震度階級の時間変化を、左(過去)→右(現在)の順に
 * 記録した帯である。新しい値は右端に追加し、古い値は左へ押し出されて
 * 破棄される(固定長リングバッファ)。
 *
 * 【区間の長さについて】各リングバッファ1コマは、想定どおり一定間隔
 * (呼び出し側が[MAX_UPDATE_HZ]=10Hzを超えない頻度でpushする前提)で
 * 追加されるため、同じ震度階級が続いた期間はそのまま「同じ色のコマが
 * 連続する区間の横幅」として表現される。1コマずつ描画しているだけで
 * 特別なランレングス処理はしていないが、更新間隔が一定であれば
 * 結果として要求どおりの「区間の長さが持続時間を表す帯」になる。
 *
 * 【更新頻度】push()の呼び出し自体を10Hzに制限するのは呼び出し側
 * (MainActivity)の責務。このView自体はpushされた値をそのまま描画するのみ。
 */
class IntensityHistoryBandView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var capacity = 100 // 10Hz更新想定で10秒分
    private var classes = arrayOfNulls<IntensityClass>(capacity)
    private var writeIndex = 0
    private var filled = 0

    private val backgroundPaint = Paint().apply { color = Color.parseColor("#1F3153"); style = Paint.Style.FILL; isAntiAlias = true }
    private val fillPaint = Paint()

    /**
     * 表示するコマ数(=時間幅)を変更する。
     * 【注意】classesは固定長配列のため、capacityフィールドを書き換えるだけでは
     * 実際のバッファ長は変わらない。ここで新しいサイズの配列を作り直す必要がある
     * (開発時にこの点を見落として capacity だけ更新するバグを作り込んでいたが、
     * 本ファイルのコードレビュー時に気づいて修正した)。
     */
    fun setCapacity(n: Int) {
        capacity = n
        classes = arrayOfNulls(capacity)
        resetInternal()
    }

    /** 新しい震度階級を右端に追加する(要件どおり10Hzを超えない頻度で呼ぶこと)。 */
    fun push(intensityClass: IntensityClass) {
        classes[writeIndex] = intensityClass
        writeIndex = (writeIndex + 1) % classes.size
        if (filled < classes.size) filled++
        invalidate()
    }

    fun clear() {
        resetInternal()
        invalidate()
    }

    private fun resetInternal() {
        for (i in classes.indices) classes[i] = null
        writeIndex = 0
        filled = 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 16f, 16f, backgroundPaint)
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)
        if (filled == 0) {
            canvas.restore()
            return
        }

        val oldestPos = if (filled < classes.size) 0 else writeIndex
        val cellWidth = w / classes.size
        // 埋まっていないコマ分は右詰めで描画する(左側は空白のまま)。
        val leftPad = (classes.size - filled) * cellWidth

        for (i in 0 until filled) {
            val idx = (oldestPos + i) % classes.size
            val cls = classes[idx] ?: continue
            fillPaint.color = IntensityClassColors.colorsFor(cls).background
            val left = leftPad + i * cellWidth
            canvas.drawRect(left, 0f, left + cellWidth + 1f, h, fillPaint)
        }
        canvas.restore()
    }
}
