package com.example.rtintensity.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 要件7「リアルタイム表示」の波形部分に対応する、簡易な複数系列の
 * スクロール折れ線グラフ。
 *
 * 【SIGKILL調査での修正点(要件3)】
 * 旧実装は pushValue() のたびに System.arraycopy() で配列全体を
 * 1つ左にシフトしていたため、1サンプルごとに O(capacity) のコピーが
 * 発生していた(capacity=300なら毎回300要素分のコピー)。
 * 本修正では書き込み位置(writeIndex)を回転させるだけのリングバッファ方式に
 * 変更し、pushValue()自体はO(1)にした。描画時(onDraw)にリングバッファを
 * 時系列順に読み出す処理だけがO(capacity)になるが、これは
 * MainActivity側で30fps程度に間引かれた描画タイミングでのみ発生するため、
 * センサーサンプリング周波数には依存しなくなっている。
 */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val seriesData = LinkedHashMap<String, FloatArray>()
    private val seriesColors = LinkedHashMap<String, Int>()
    private val writeIndices = HashMap<String, Int>()
    private val filledCounts = HashMap<String, Int>()
    private var capacity = 300

    private val linePaint = Paint().apply { strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val axisPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
    private val defaultColors = intArrayOf(
        Color.parseColor("#1976D2"), Color.parseColor("#D32F2F"),
        Color.parseColor("#388E3C"), Color.parseColor("#F57C00")
    )

    fun setCapacity(n: Int) {
        capacity = n
        seriesData.keys.toList().forEach { key ->
            seriesData[key] = FloatArray(capacity)
            writeIndices[key] = 0
            filledCounts[key] = 0
        }
    }

    /**
     * name: 系列名(初回呼び出し時に自動登録される)。value: 追加する最新値。O(1)。
     * color: 初回登録時にこの系列に使う色を明示的に指定したい場合に渡す
     *        (例: X/Y/Z軸を赤/緑/青で固定したい場合)。省略時は従来どおり
     *        登録順にdefaultColorsを巡回する(既存呼び出し箇所への
     *        後方互換性のため、この引数はオプションにしてある)。
     *        2回目以降の呼び出しでは無視される(色は初回登録時に確定)。
     */
    fun pushValue(name: String, value: Float, color: Int? = null) {
        val arr = seriesData.getOrPut(name) {
            seriesColors[name] = color ?: defaultColors[seriesColors.size % defaultColors.size]
            writeIndices[name] = 0
            filledCounts[name] = 0
            FloatArray(capacity)
        }
        val idx = writeIndices[name] ?: 0
        arr[idx] = value
        writeIndices[name] = (idx + 1) % arr.size
        filledCounts[name] = minOf((filledCounts[name] ?: 0) + 1, arr.size)
        invalidate()
    }

    fun clearAll() {
        seriesData.keys.toList().forEach { key ->
            seriesData[key] = FloatArray(capacity)
            writeIndices[key] = 0
            filledCounts[key] = 0
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(0f, h / 2f, w, h / 2f, axisPaint)

        var maxAbs = 1e-6f
        for (arr in seriesData.values) {
            for (v in arr) if (kotlin.math.abs(v) > maxAbs) maxAbs = kotlin.math.abs(v)
        }

        for ((name, arr) in seriesData) {
            val filled = filledCounts[name] ?: 0
            if (filled < 2) continue
            val start = writeIndices[name] ?: 0 // リングバッファが満杯なら、ここが最古のデータ位置
            val oldestPos = if (filled < arr.size) 0 else start

            linePaint.color = seriesColors[name] ?: Color.BLACK
            var prevX = 0f
            var prevY = h / 2f - (arr[oldestPos] / maxAbs) * (h / 2f * 0.9f)
            for (i in 1 until filled) {
                val idx = (oldestPos + i) % arr.size
                val x = w * i / (filled - 1)
                val y = h / 2f - (arr[idx] / maxAbs) * (h / 2f * 0.9f)
                canvas.drawLine(prevX, prevY, x, y, linePaint)
                prevX = x; prevY = y
            }
        }
    }
}
