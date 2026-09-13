package com.example.rtintensity.filter

/**
 * 差分方程式
 *   y[k] = ( -a1*y[k-1] - a2*y[k-2] + b0*x[k] + b1*x[k-1] + b2*x[k-2] ) / a0
 * で表される、双2次(biquad)IIRフィルタ1段分の状態と係数を保持するクラス。
 *
 * 係数(a0,a1,a2,b0,b1,b2)自体はここでは決めない。
 * [NiedFilterCoefficients] が、気象庁フィルタを近似する防災科研方式の
 * アナログプロトタイプをサンプリング間隔 dt で双一次変換した具体的な値を計算する。
 */
class Biquad {
    private var a0 = 1.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0

    // 過去2ステップ分の入力・出力
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun setCoefficients(c: BiquadCoefficients) {
        a0 = c.a0
        a1 = c.a1
        a2 = c.a2
        b0 = c.b0
        b1 = c.b1
        b2 = c.b2
    }

    /** フィルタの内部状態(過去の入出力履歴)だけをゼロに戻す。係数はそのまま。 */
    fun resetState() {
        x1 = 0.0; x2 = 0.0
        y1 = 0.0; y2 = 0.0
    }

    fun process(x0: Double): Double {
        val y0 = (-a1 * y1 - a2 * y2 + b0 * x0 + b1 * x1 + b2 * x2) / a0
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0
        return y0
    }
}

/** 双2次フィルタ1段分の係数。 */
data class BiquadCoefficients(
    val a0: Double, val a1: Double, val a2: Double,
    val b0: Double, val b1: Double, val b2: Double
)
