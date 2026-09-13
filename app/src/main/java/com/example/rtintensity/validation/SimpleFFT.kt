package com.example.rtintensity.validation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * オフライン検証(K-NET波形との比較)専用の、素朴な反復型radix-2 FFT。
 * リアルタイム処理では一切使わない(気象庁の元アルゴリズムがFFTベースで
 * リアルタイム処理に不向きであることが、本アプリで漸化式フィルタを
 * 実装する動機そのものであるため)。
 *
 * 入力長が2のべき乗でない場合はゼロ埋めして次の2のべき乗に拡張する。
 * 【簡略化として明記】ゼロ埋めにより、周波数ビンの間隔(分解能)が
 * 元の記録長からの理論値と変わり、また巡回畳み込みではなく線形畳み込みに
 * 近づく(端の折り返しノイズが減る)効果がある。気象庁の公式解説には
 * FFT長の決め方についての明記はなく、本実装のゼロ埋めは検証目的の
 * 実用上の簡略化である。
 */
object SimpleFFT {

    fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** re, im は同じ長さ(2のべき乗)の配列。破壊的に(in-place)変換する。 */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT長は2のべき乗である必要があります: $n" }
        if (n == 1) return

        // ビット反転並べ替え
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        var len = 2
        val sign = if (inverse) 1.0 else -1.0
        while (len <= n) {
            val ang = sign * 2.0 * PI / len
            val wReal = cos(ang)
            val wImag = sin(ang)
            var i = 0
            while (i < n) {
                var curReal = 1.0
                var curImag = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curReal - im[i + k + len / 2] * curImag
                    val vIm = re[i + k + len / 2] * curImag + im[i + k + len / 2] * curReal
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextReal = curReal * wReal - curImag * wImag
                    val nextImag = curReal * wImag + curImag * wReal
                    curReal = nextReal
                    curImag = nextImag
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val nD = n.toDouble()
            for (i in 0 until n) {
                re[i] = re[i] / nD
                im[i] = im[i] / nD
            }
        }
    }
}
