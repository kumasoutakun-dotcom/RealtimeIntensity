package com.example.rtintensity.processor

import com.example.rtintensity.filter.RealtimeIntensityFilter
import kotlin.math.sqrt

/** 1サンプル分の処理結果。UI表示・CSV保存の両方から参照する共通データ構造。 */
data class ProcessedSample(
    val timestampNanos: Long,
    val axGal: Double,
    val ayGal: Double,
    val azGal: Double,
    val filteredXGal: Double,
    val filteredYGal: Double,
    val filteredZGal: Double,
    val combinedGal: Double,
    val rawIntensity: Double,      // 丸め前
    val roundedIntensity: Double,  // 気象庁式の丸め処理後(表示用)
    val sampleRateHz: Double
)

/**
 * SensorManager から得た生の加速度[m/s^2](重力込み, Sensor.TYPE_ACCELEROMETER)を受け取り、
 *   1) m/s^2 -> gal (1 gal = 0.01 m/s^2 なので 1 m/s^2 = 100 gal) に変換
 *   2) X/Y/Zそれぞれに [RealtimeIntensityFilter] を独立に適用
 *      (重力(直流成分)を別途ローパスで推定・除去する処理は行わない。
 *       理由: このフィルタ自体が0.5Hz以下で急減衰し、フィルタ1段目だけで見ても
 *       直流(0Hz)ゲインが厳密に0になる設計になっている(NiedFilterCoefficients.filter1の
 *       β0+β1+β2 = 0 であることから解析的に確認できる)。したがって生の加速度を
 *       そのまま入力すれば、フィルタ処理の過程で重力による直流バイアスは
 *       ほぼ完全に除去される。ただし、これは「静止状態で置いてある」ことが前提であり、
 *       途中で端末の向きを変えるとステップ状の変化として現れ、地震動と区別できない
 *       誤検知の原因になる。)
 *   3) 3成分を sqrt(x^2+y^2+z^2) で合成
 *   4) [IntensityCalculator] でリアルタイム震度相当値を計算
 * という一連の処理をまとめるクラス。
 */
class SeismicProcessor {

    private val filterX = RealtimeIntensityFilter()
    private val filterY = RealtimeIntensityFilter()
    private val filterZ = RealtimeIntensityFilter()

    private var intensityCalculator: IntensityCalculator? = null

    private var lastTimestampNanos: Long = -1L
    private var smoothedSampleRateHz: Double = Double.NaN

    companion object {
        const val MS2_TO_GAL = 100.0 // 1 m/s^2 = 100 gal
    }

    fun reset() {
        filterX.resetState(); filterY.resetState(); filterZ.resetState()
        intensityCalculator?.reset()
        lastTimestampNanos = -1L
        smoothedSampleRateHz = Double.NaN
    }

    /**
     * ax, ay, az: Sensor.TYPE_ACCELEROMETER の生値[m/s^2](重力込み)。
     * timestampNanos: SensorEvent.timestamp (端末起動からのモノトニック時刻, ナノ秒。
     *                 壁時計時刻ではないことに注意)。
     */
    fun onNewSample(ax: Float, ay: Float, az: Float, timestampNanos: Long): ProcessedSample? {
        if (lastTimestampNanos < 0L) {
            lastTimestampNanos = timestampNanos
            return null // 最初の1サンプルは dt が定義できないので処理をスキップする
        }
        val dtSeconds = (timestampNanos - lastTimestampNanos) / 1_000_000_000.0
        lastTimestampNanos = timestampNanos
        if (dtSeconds <= 0.0 || dtSeconds > 1.0) {
            // タイムスタンプの逆行、またはセンサー停止からの復帰等で
            // 異常に大きい間隔が空いた場合はこのサンプルを捨てて仕切り直す。
            return null
        }

        // サンプリング周波数の表示用に軽く平滑化(指数移動平均)。
        val instantHz = 1.0 / dtSeconds
        smoothedSampleRateHz = if (smoothedSampleRateHz.isNaN()) {
            instantHz
        } else {
            0.9 * smoothedSampleRateHz + 0.1 * instantHz
        }

        filterX.configure(dtSeconds)
        filterY.configure(dtSeconds)
        filterZ.configure(dtSeconds)

        val calc = intensityCalculator ?: IntensityCalculator(dtSeconds).also { intensityCalculator = it }
        calc.configure(dtSeconds)

        val axGal = ax * MS2_TO_GAL
        val ayGal = ay * MS2_TO_GAL
        val azGal = az * MS2_TO_GAL

        val fx = filterX.process(axGal)
        val fy = filterY.process(ayGal)
        val fz = filterZ.process(azGal)

        val combined = sqrt(fx * fx + fy * fy + fz * fz)
        val rawI = calc.push(combined)
        val roundedI = IntensityCalculator.jmaRound(rawI)

        return ProcessedSample(
            timestampNanos = timestampNanos,
            axGal = axGal, ayGal = ayGal, azGal = azGal,
            filteredXGal = fx, filteredYGal = fy, filteredZGal = fz,
            combinedGal = combined,
            rawIntensity = rawI,
            roundedIntensity = roundedI,
            sampleRateHz = smoothedSampleRateHz
        )
    }
}
