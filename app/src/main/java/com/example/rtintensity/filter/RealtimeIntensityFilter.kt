package com.example.rtintensity.filter

/**
 * 1軸分のリアルタイム震度用フィルタ。
 * [NiedFilterCoefficients] の6段の双2次フィルタを直列に適用し、
 * 最後に全体ゲインを乗じる。X・Y・Z それぞれについて、
 * このクラスのインスタンスを1つずつ(合計3個)用意し、
 * 内部状態(直近の入出力履歴)を軸ごとに独立に保持すること。
 *
 * 【サンプリング間隔(dt)の扱いについて(簡略化の明記)】
 * 気象庁・防災科研のオリジナルの想定は、据置型の強震計が一定周波数
 * (例: 100Hz)で動作することを前提としている。
 * 一方、Androidの実機センサーはイベント間隔が真に一定ではなく、
 * OSスケジューリング等により毎回わずかにジッタが生じる。
 *
 * 本実装では、簡略化として「一定周波数を仮定して固定係数を使う」のではなく、
 * センサーの実タイムスタンプから求めた瞬時のΔTをそのまま毎サンプル
 * フィルタ設計に用いる(=毎回係数を再計算する)方式を採用した。
 * これは公式資料に明記された処理ではなく、可変サンプリング間隔の実機に
 * 対応するための拡張である。ジャイロ積分など他のセンサー処理でも
 * 実測ΔTをそのまま使うのが一般的であり、理論的な破綻はないと考えられるが、
 * 気象庁方式が本来前提とする「厳密に一定周波数のLTI(線形時不変)フィルタ」
 * ではなくなる点は誤差要因として明記しておく。
 */
class RealtimeIntensityFilter {

    private val stage1 = Biquad()
    private val stage2 = Biquad()
    private val stage3 = Biquad()
    private val stage4 = Biquad()
    private val stage5 = Biquad()
    private val stage6 = Biquad()

    private var configuredDt: Double = -1.0

    /** dtSeconds が前回と異なる場合のみ6段分の係数を再計算する。 */
    fun configure(dtSeconds: Double) {
        if (dtSeconds <= 0.0) return
        if (dtSeconds == configuredDt) return
        configuredDt = dtSeconds
        stage1.setCoefficients(NiedFilterCoefficients.filter1(dtSeconds))
        stage2.setCoefficients(NiedFilterCoefficients.filter2(dtSeconds))
        stage3.setCoefficients(NiedFilterCoefficients.filter3(dtSeconds))
        stage4.setCoefficients(NiedFilterCoefficients.filter4(dtSeconds))
        stage5.setCoefficients(NiedFilterCoefficients.filter5(dtSeconds))
        stage6.setCoefficients(NiedFilterCoefficients.filter6(dtSeconds))
    }

    fun resetState() {
        stage1.resetState(); stage2.resetState(); stage3.resetState()
        stage4.resetState(); stage5.resetState(); stage6.resetState()
    }

    /** xGal: 加速度[gal]の生値(重力込みでよい。理由はクラスコメント参照)。戻り値もgal。 */
    fun process(xGal: Double): Double {
        var v = stage1.process(xGal)
        v = stage2.process(v)
        v = stage3.process(v)
        v = stage4.process(v)
        v = stage5.process(v)
        v = stage6.process(v)
        return v * NiedFilterCoefficients.GAIN
    }
}
