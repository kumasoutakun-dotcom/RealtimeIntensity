package com.example.rtintensity.validation

import com.example.rtintensity.filter.RealtimeIntensityFilter
import com.example.rtintensity.processor.IntensityCalculator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 気象庁「計測震度の算出方法」に記載された、本来の(FFTベースの)アルゴリズムを
 * そのまま実装したもの。要件10「検証」のために、本アプリのリアルタイム
 * (漸化式)フィルタの結果と比較する基準値としてのみ用いる。
 *
 * 出典(完全に一次資料通り):
 *   FL(f) = sqrt(1 - exp(-(f/0.5)^3))
 *   FF(f) = sqrt(1/f)
 *   FH(f) = (1 + Σ c_k (f/10)^(2k))^(-1/2), k=1..6
 *     c1=0.694, c2=0.241, c3=0.0557, c4=0.009664, c5=0.00134, c6=0.000155
 *   H(f) = FL(f)*FF(f)*FH(f)
 *   手順: 3成分それぞれをFFT → H(f)を乗じる → 逆FFT → 3成分をベクトル合成
 *        → 絶対値がaを超える時間の合計がちょうど0.3秒となるaを求める
 *        → I = 2*log10(a) + 0.94
 *        → 小数第3位を四捨五入し、小数第2位を切り捨てる
 */
object OfflineJmaIntensity {

    private const val C1 = 0.694
    private const val C2 = 0.241
    private const val C3 = 0.0557
    private const val C4 = 0.009664
    private const val C5 = 0.00134
    private const val C6 = 0.000155

    /** f=0での特異点を避けつつ、公式の3フィルタ積を返す。f=0では定義よりゲイン0とする。 */
    private fun jmaFilterGain(fAbs: Double): Double {
        if (fAbs <= 0.0) return 0.0
        val fl = sqrt(max(0.0, 1.0 - exp(-(fAbs / 0.5).pow(3))))
        val ff = sqrt(1.0 / fAbs)
        val y = fAbs / 10.0
        val denom = 1.0 + C1 * y.pow(2) + C2 * y.pow(4) + C3 * y.pow(6) +
            C4 * y.pow(8) + C5 * y.pow(10) + C6 * y.pow(12)
        val fh = 1.0 / sqrt(denom)
        return fl * ff * fh
    }

    /** 1成分の加速度波形[gal]に、FFT経由で気象庁フィルタを適用した結果を返す(同じ長さ)。 */
    fun applyJmaFilterFft(accelGal: DoubleArray, samplingHz: Double): DoubleArray {
        val n = accelGal.size
        val nPad = SimpleFFT.nextPowerOfTwo(n)
        val re = DoubleArray(nPad)
        val im = DoubleArray(nPad)
        for (i in 0 until n) re[i] = accelGal[i]
        // n..nPad-1 はゼロ埋め(SimpleFFTの項のコメント参照)

        SimpleFFT.transform(re, im, inverse = false)

        for (k in 0 until nPad) {
            val binFreq = if (k <= nPad / 2) k * samplingHz / nPad else (k - nPad) * samplingHz / nPad
            val gain = jmaFilterGain(abs(binFreq))
            re[k] *= gain
            im[k] *= gain
        }

        SimpleFFT.transform(re, im, inverse = true)
        return DoubleArray(n) { re[it] }
    }

    data class ReferenceResult(
        val referenceIntensity: Double,
        val amplitudeGal: Double
    )

    /** 3成分(同一サンプリング周波数・同一長を想定)から、公式アルゴリズムどおりの基準震度を計算する。 */
    fun computeReferenceIntensity(ns: DoubleArray, ew: DoubleArray, ud: DoubleArray, samplingHz: Double): ReferenceResult {
        val n = minOf(ns.size, ew.size, ud.size)
        val fNs = applyJmaFilterFft(ns.copyOf(n), samplingHz)
        val fEw = applyJmaFilterFft(ew.copyOf(n), samplingHz)
        val fUd = applyJmaFilterFft(ud.copyOf(n), samplingHz)

        val combined = DoubleArray(n) { i -> sqrt(fNs[i] * fNs[i] + fEw[i] * fEw[i] + fUd[i] * fUd[i]) }
        val dt = 1.0 / samplingHz
        val k = max(1, kotlin.math.floor(0.3 / dt).toInt())
        val sorted = combined.copyOf()
        sorted.sort()
        val kk = min(k, n)
        val a = max(0.0, sorted[n - kk])
        val rawI = if (a <= 0.0) Double.NEGATIVE_INFINITY else 2.0 * log10(a) + 0.94
        return ReferenceResult(IntensityCalculator.jmaRound(rawI), a)
    }

    data class RealtimeReplayResult(
        val maxRoundedIntensity: Double,
        val maxRawIntensity: Double
    )

    /**
     * 本アプリのリアルタイム(漸化式)フィルタを、記録済み波形に対して
     * オフラインで「そのまま順番に流し込む」ことでリプレイし、
     * その間の最大リアルタイム震度相当値を求める。
     * これを [computeReferenceIntensity] の結果と比較することで、
     * 「本来のFFTベースの計測震度」と「本アプリの漸化式近似」の誤差を
     * 定量的に確認できる(要件10)。
     */
    fun replayRealtimeFilter(ns: DoubleArray, ew: DoubleArray, ud: DoubleArray, samplingHz: Double): RealtimeReplayResult {
        val n = minOf(ns.size, ew.size, ud.size)
        val dt = 1.0 / samplingHz
        val fx = RealtimeIntensityFilter().also { it.configure(dt) }
        val fy = RealtimeIntensityFilter().also { it.configure(dt) }
        val fz = RealtimeIntensityFilter().also { it.configure(dt) }
        val calc = IntensityCalculator(dt)

        var maxRaw = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val x = fx.process(ns[i])
            val y = fy.process(ew[i])
            val z = fz.process(ud[i])
            val combined = sqrt(x * x + y * y + z * z)
            val raw = calc.push(combined).rawIntensity
            if (raw > maxRaw) maxRaw = raw
        }
        return RealtimeReplayResult(IntensityCalculator.jmaRound(maxRaw), maxRaw)
    }
}
