package com.example.rtintensity.processor

import kotlin.math.floor
import kotlin.math.log10

/**
 * 現在のフィルタ後3軸合成加速度から、リアルタイム震度相当値を
 * その時点のサンプルだけで算出する計算器。
 *
 * 以前は直近10秒のスライディングウィンドウ内から「0.3秒基準」に対応する
 * 振幅を選んでいたが、現在はその評価窓を廃止している。
 * そのため、フィルタ後の加速度が下がれば、震度相当値も次のサンプルから
 * 追従して下がる。
 *
 * 本アプリの値は参考値としての「リアルタイム震度相当値」であり、
 * 気象庁が発表する正式な計測震度そのものではない。
 */
class IntensityCalculator(initialSampleIntervalSeconds: Double) {

    // 既存コードとの互換性のためサンプル間隔を保持するが、
    // 現在の瞬時計算そのものには使用しない。
    private var sampleIntervalSeconds = initialSampleIntervalSeconds

    /** 計測開始中のセンサー設定変更などに対応するための既存API。 */
    fun configure(sampleIntervalSeconds: Double) {
        if (sampleIntervalSeconds > 0.0) {
            this.sampleIntervalSeconds = sampleIntervalSeconds
        }
    }

    fun reset() {
        // 履歴を保持しないため、リセット対象はない。
    }

    /**
     * combinedGal: その時点のフィルタ後3軸合成加速度(gal, 0以上)。
     *
     * rawIntensity は現在サンプルだけから直接算出する。
     * amplitudeGal は震度算出式に入力した現在の振幅。
     */
    fun push(combinedGal: Double): PushResult {
        val a = maxOf(0.0, combinedGal)
        val rawIntensity = if (a <= 0.0) {
            Double.NEGATIVE_INFINITY
        } else {
            2.0 * log10(a) + 0.94
        }
        return PushResult(rawIntensity, a)
    }

    /** [push] の戻り値。 */
    data class PushResult(val rawIntensity: Double, val amplitudeGal: Double)

    companion object {
        /**
         * 気象庁式の丸め処理。
         * 計算されたIの小数第3位を四捨五入し、小数第2位を切り捨てる。
         */
        fun jmaRound(rawIntensity: Double): Double {
            if (!rawIntensity.isFinite()) return Double.NEGATIVE_INFINITY
            val roundedTo2 = floor(rawIntensity * 100.0 + 0.5) / 100.0
            return floor(roundedTo2 * 10.0) / 10.0
        }
    }
}
