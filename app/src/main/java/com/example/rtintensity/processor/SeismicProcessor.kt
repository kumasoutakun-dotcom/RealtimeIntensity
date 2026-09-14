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
    val pgaEquivalentGal: Double,  // 震度算出式のa(gal)。震度表示拡張タスクでの「加速度」表示に使用
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
    private var smoothedDtSeconds: Double = Double.NaN

    companion object {
        const val MS2_TO_GAL = 100.0 // 1 m/s^2 = 100 gal

        /**
         * フィルタ係数・IntensityCalculatorのウィンドウ長計算に使う
         * ΔT平滑化の重み(旧値側)。センサー表示用の[smoothedSampleRateHz]
         * (重み0.9)より強めに平滑化する(0.98)。
         *
         * 【ノイズ調査での知見】
         * 実機(Nothing Phone (3a))ではセンサーの実測サンプリング間隔が
         * サンプルごとに微小にジッタし、旧実装ではこの瞬時ΔTをそのまま
         * 毎サンプルNIEDフィルタの係数計算に使っていた。これは
         * 「センサーの実タイムスタンプに忠実」という考え方自体は妥当だが、
         * 本来ほぼ一定であるはずの物理的なサンプリング周波数に対して、
         * 測定上のジッタ由来の微小な係数変動を毎サンプル注入することになり、
         * フィルタが厳密なLTI(線形時不変)から外れて余計な数値的ノイズを
         * 生む要因になり得る。センサーの実際の供給レート自体は測定中に
         * 動的には変化しないという前提のもと、強めのEMA(平滑化定数0.98、
         * 時定数はおよそ dt/(1-0.98) = 50*dt 程度)でジッタを平均化した
         * ΔTをフィルタ係数計算に使うよう変更した。表示用の
         * [smoothedSampleRateHz](重み0.9)は従来どおりで、画面の
         * 「サンプリング周波数」表示の反応性は変えていない。
         */
        private const val FILTER_DT_SMOOTHING_ALPHA = 0.98
    }

    fun reset() {
        filterX.resetState(); filterY.resetState(); filterZ.resetState()
        intensityCalculator?.reset()
        lastTimestampNanos = -1L
        smoothedSampleRateHz = Double.NaN
        smoothedDtSeconds = Double.NaN
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

        // サンプリング周波数の表示用に軽く平滑化(指数移動平均、従来どおり)。
        val instantHz = 1.0 / dtSeconds
        smoothedSampleRateHz = if (smoothedSampleRateHz.isNaN()) {
            instantHz
        } else {
            0.9 * smoothedSampleRateHz + 0.1 * instantHz
        }

        // フィルタ係数計算に使うΔTは、瞬時ジッタを抑えるためより強く平滑化する。
        // (このΔT自体は実測値から求めた推定値であり、固定のダミー値ではない。
        //  NIEDフィルタの数式・係数計算式そのものは一切変更していない。)
        smoothedDtSeconds = if (smoothedDtSeconds.isNaN()) {
            dtSeconds
        } else {
            FILTER_DT_SMOOTHING_ALPHA * smoothedDtSeconds + (1.0 - FILTER_DT_SMOOTHING_ALPHA) * dtSeconds
        }

        filterX.configure(smoothedDtSeconds)
        filterY.configure(smoothedDtSeconds)
        filterZ.configure(smoothedDtSeconds)

        val calc = intensityCalculator ?: IntensityCalculator(smoothedDtSeconds).also { intensityCalculator = it }
        calc.configure(smoothedDtSeconds)

        val axGal = ax * MS2_TO_GAL
        val ayGal = ay * MS2_TO_GAL
        val azGal = az * MS2_TO_GAL

        val fx = filterX.process(axGal)
        val fy = filterY.process(ayGal)
        val fz = filterZ.process(azGal)

        val combined = sqrt(fx * fx + fy * fy + fz * fz)
        val pushResult = calc.push(combined)
        val rawI = pushResult.rawIntensity
        val roundedI = IntensityCalculator.jmaRound(rawI)

        return ProcessedSample(
            timestampNanos = timestampNanos,
            axGal = axGal, ayGal = ayGal, azGal = azGal,
            filteredXGal = fx, filteredYGal = fy, filteredZGal = fz,
            combinedGal = combined,
            rawIntensity = rawI,
            roundedIntensity = roundedI,
            pgaEquivalentGal = pushResult.amplitudeGal,
            sampleRateHz = smoothedSampleRateHz
        )
    }
}
