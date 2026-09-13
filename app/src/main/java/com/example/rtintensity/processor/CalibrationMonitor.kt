package com.example.rtintensity.processor

import kotlin.math.sqrt

/**
 * 要件9「キャリブレーション」への対応。
 *
 * 「ユーザーが適当にスマホを振ってキャリブレーションする」方式ではなく、
 * 「机などに静置した状態でのセンサー出力(オフセット・ノイズ)を
 *  常時モニタし、画面に表示し続ける」方式を採用する。
 * 実際に地震動を検知しているかどうかの判定等には使わず、
 * あくまで「今、静止状態としてどの程度のノイズ/オフセットが
 * 乗っているか」をユーザーに提示するための補助表示に留める
 * (自動でしきい値判定をして測定開始を制御する、といったことはしない)。
 *
 * 直近 [windowSize] サンプルの生の加速度[gal](フィルタ処理前)について、
 * 軸ごとの平均・標準偏差を計算する。
 */
class CalibrationMonitor(private val windowSize: Int = 200) {

    private val bufX = ArrayDeque<Double>()
    private val bufY = ArrayDeque<Double>()
    private val bufZ = ArrayDeque<Double>()

    data class Stats(val meanX: Double, val sdX: Double, val meanY: Double, val sdY: Double, val meanZ: Double, val sdZ: Double)

    fun reset() {
        bufX.clear(); bufY.clear(); bufZ.clear()
    }

    fun push(axGal: Double, ayGal: Double, azGal: Double): Stats {
        addBounded(bufX, axGal)
        addBounded(bufY, ayGal)
        addBounded(bufZ, azGal)
        val (mx, sx) = meanSd(bufX)
        val (my, sy) = meanSd(bufY)
        val (mz, sz) = meanSd(bufZ)
        return Stats(mx, sx, my, sy, mz, sz)
    }

    private fun addBounded(buf: ArrayDeque<Double>, v: Double) {
        buf.addLast(v)
        if (buf.size > windowSize) buf.removeFirst()
    }

    private fun meanSd(buf: ArrayDeque<Double>): Pair<Double, Double> {
        if (buf.isEmpty()) return 0.0 to 0.0
        val mean = buf.sum() / buf.size
        val variance = buf.sumOf { (it - mean) * (it - mean) } / buf.size
        return mean to sqrt(variance)
    }
}
