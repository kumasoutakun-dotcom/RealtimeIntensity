package com.example.rtintensity.filter

import kotlin.math.PI

/**
 * 気象庁の計測震度フィルタ H(f) = FL(f)・FF(f)・FH(f) を、
 * 防災科学技術研究所(NIED)方式(功刀・他, 2008)に基づき、
 * 6段の双2次(biquad) IIRフィルタ + ゲインで近似するための係数計算。
 *
 * ============================== 出典・確度について ==============================
 *
 * 【完全に一次資料で確認できたもの】
 *  ・目標とする周波数特性そのもの H(f)=FL(f)*FF(f)*FH(f):
 *      気象庁「計測震度の算出方法」
 *      https://www.jma.go.jp/jma/kishou/know/jishin/kyoshin/kaisetsu/calc_sindo.html
 *      FL(f) = sqrt(1 - exp(-(f/0.5)^3))          … ローカット(周期効果込みではない)
 *      FF(f) = sqrt(1/f)                           … 周期効果
 *      FH(f) = (1 + Σ c_k (f/10)^(2k))^(-1/2), k=1..6 … ハイカット
 *          c1=0.694, c2=0.241, c3=0.0557, c4=0.009664, c5=0.00134, c6=0.000155
 *
 *  ・防災科研がこの近似方式を「功刀・他(2008), 地震第2輯, 60, 243-252,
 *    doi:10.4294/zisin.60.243」として論文発表していること、および
 *    現在の強震モニタは改良版「功刀・他(2013), 地震第2輯, 65, 223-230,
 *    doi:10.4294/zisin.65.223」を使っていること
 *      → 防災科研 強震モニタ特集ページの記載で確認
 *        (https://www.kyoshin.bosai.go.jp/kyoshin/topics/html20240101160813/)
 *
 * 【一次資料(論文Appendix)そのものは未確認】
 *  上記2論文はJ-STAGEで公開されているが、本セッションの環境からは
 *  robots.txt によりPDFを直接取得できなかった。そのため、論文Appendixに
 *  記載されているという係数計算式(A11)~(A15)の"原文"は確認できていない。
 *
 * 【代わりに採用した根拠】
 *  功刀・他(2008)のフィルタ構成(8個のアナログプロトタイプ(A1)~(A8)を
 *  6個の双2次フィルタにまとめ、双一次変換 s^-1≈(ΔT/2)(1+z^-1)/(1-z^-1) で
 *  デジタル化する、という考え方そのものは第三者の技術記事
 *    ことほ(2025)「加速度からリアルタイム震度を計算してみる」
 *    https://note.com/kotoho7/n/n66409051c060
 *  で解説されており、その記事に付随する実際に動作するオープンソース実装
 *    https://github.com/kotoho7/calc-realtime-shindo-advent2025
 *  から、6フィルタそれぞれの具体的な係数計算式と定数
 *    f0=0.45Hz, f1=7.0Hz, f2=0.5Hz, h2a=1.0, h2b=0.75,
 *    f3=12.0Hz(h=0.9), f4=20.0Hz(h=0.6), f5=30.0Hz(h=0.6), g=1.262
 *  を抽出した。この実装は、SMDA2(防災科研配布の解析ツール)の出力と
 *  重ねてもほぼ一致する波形になることが同記事内で示されており、また
 *  本ファイルの係数式は、複数のΔTについて上記オープンソース実装が
 *  実際に計算した数値(倍精度)と完全一致することを、本プロジェクト作成時に
 *  Pythonで数値的に検算済みである。
 *
 * 【簡略化・不確実性として明記しておく点】
 *  1. 上記の定数は功刀・他"2008"のものであり、防災科研が現在正式に
 *     使っている功刀・他"2013"の改良版ではない。2013年論文の要旨によれば、
 *     2013年改良版はゲイン調整と0.5Hz付近の補正フィルタ(h2a/h2b相当)を
 *     見直したもので、ΔI(近似値と気象庁計測震度との差)の標準偏差が
 *     全データで0.0386→0.70倍、震度4以上のデータで0.0426→0.63倍に
 *     改善されると報告されている(論文アブストラクトより)。
 *     2013年版の正確な係数を反映したい場合は、doi:10.4294/zisin.65.223 の
 *     Appendix Aを入手し、下記の定数(F0〜F5, H2A, H2B, H3〜H5, GAIN)を
 *     書き換えること。
 *  2. 双一次変換は本質的に近似であり、ナイキスト周波数に近い帯域で
 *     周波数特性が理論値からずれる(周波数ワーピング)ことが2013年論文でも
 *     指摘されている。本実装はこの点について独自の補正は行っていない。
 * ================================================================================
 */
object NiedFilterCoefficients {

    // ---- 功刀・他(2008)で用いられている固定パラメータ ----
    const val F0 = 0.45   // Hz  (フィルタ1: ベース形状, 低域側)
    const val F1 = 7.0    // Hz  (フィルタ1・2: ベース形状, 高域側)
    const val F2 = 0.5    // Hz  (フィルタ3: 0.5Hz近辺の補正)
    const val H2A = 1.0   //     (フィルタ3の分子側減衰定数)
    const val H2B = 0.75  //     (フィルタ3の分母側減衰定数)
    const val F3 = 12.0   // Hz  (フィルタ4: ハイカット1段目)
    const val H3 = 0.9
    const val F4 = 20.0   // Hz  (フィルタ5: ハイカット2段目)
    const val H4 = 0.6
    const val F5 = 30.0   // Hz  (フィルタ6: ハイカット3段目)
    const val H5 = 0.6
    const val GAIN = 1.262 // 全体ゲイン(6フィルタ直列適用後に乗じる)

    /**
     * フィルタ1: アナログプロトタイプ (A1)×(A2) を dt[秒]で双一次変換したもの。
     *   A1(s) = 1 / (ω0 s^-1 + 1)                       [ω0=2π・F0]
     *   A2(s) = (ω1 s^-1 + 1) / (ω1 s^-1 + 2)            [ω1=2π・F1]
     */
    fun filter1(dt: Double): BiquadCoefficients {
        val wa1 = 2.0 * PI * F0
        val wa2 = 2.0 * PI * F1
        val dt2 = dt * dt
        val a0 = 8.0 / dt2 + (4.0 * wa1 + 2.0 * wa2) / dt + wa1 * wa2
        val a1 = 2.0 * wa1 * wa2 - 16.0 / dt2
        val a2 = 8.0 / dt2 - (4.0 * wa1 + 2.0 * wa2) / dt + wa1 * wa2
        val b0 = 4.0 / dt2 + 2.0 * wa2 / dt
        val b1 = -8.0 / dt2
        val b2 = 4.0 / dt2 - 2.0 * wa2 / dt
        return BiquadCoefficients(a0, a1, a2, b0, b1, b2)
    }

    /**
     * フィルタ2: アナログプロトタイプ (A3)×(A4) を dt[秒]で双一次変換したもの。
     *   A3(s) = (4ω1 s^-1 + 1) / (ω1 s^-1 + 8)
     *   A4(s) = (0.25ω1 s^-1 + 1) / (ω1 s^-1 + 0.5)
     * (両者を展開すると ω1 に関する2次式どうしの比になり、
     *  単一の ω1 だけで閉じた式になる。)
     */
    fun filter2(dt: Double): BiquadCoefficients {
        val w = 2.0 * PI * F1
        val dt2 = dt * dt
        val a0 = 16.0 / dt2 + 17.0 * w / dt + w * w
        val a1 = 2.0 * w * w - 32.0 / dt2
        val a2 = 16.0 / dt2 - 17.0 * w / dt + w * w
        val b0 = 4.0 / dt2 + 8.5 * w / dt + w * w
        val b1 = 2.0 * w * w - 8.0 / dt2
        val b2 = 4.0 / dt2 - 8.5 * w / dt + w * w
        return BiquadCoefficients(a0, a1, a2, b0, b1, b2)
    }

    /**
     * フィルタ3: 0.5Hz付近のゲインピークを再現する2次補正フィルタ (A5)。
     *   A5(s) = (1 + 2*h2a*ω2 s^-1 + ω2^2 s^-2) / (1 + 2*h2b*ω2 s^-1 + ω2^2 s^-2)
     */
    fun filter3(dt: Double): BiquadCoefficients {
        val w = 2.0 * PI * F2
        val dt2 = dt * dt
        val a0 = 12.0 / dt2 + 12.0 * H2B * w / dt + w * w
        val a1 = 10.0 * w * w - 24.0 / dt2
        val a2 = 12.0 / dt2 - 12.0 * H2B * w / dt + w * w
        val b0 = 12.0 / dt2 + 12.0 * H2A * w / dt + w * w
        val b1 = 10.0 * w * w - 24.0 / dt2
        val b2 = 12.0 / dt2 - 12.0 * H2A * w / dt + w * w
        return BiquadCoefficients(a0, a1, a2, b0, b1, b2)
    }

    /**
     * フィルタ4〜6共通: 2次ハイカット(ローパス)プロトタイプ (A6)(A7)(A8)。
     *   An(s) = ωn^2 s^-2 / (1 + 2*hn*ωn s^-1 + ωn^2 s^-2)
     */
    private fun highCut(dt: Double, h: Double, f: Double): BiquadCoefficients {
        val w = 2.0 * PI * f
        val dt2 = dt * dt
        val a0 = 12.0 / dt2 + 12.0 * h * w / dt + w * w
        val a1 = 10.0 * w * w - 24.0 / dt2
        val a2 = 12.0 / dt2 - 12.0 * h * w / dt + w * w
        val b0 = w * w
        val b1 = 10.0 * w * w
        val b2 = w * w
        return BiquadCoefficients(a0, a1, a2, b0, b1, b2)
    }

    fun filter4(dt: Double): BiquadCoefficients = highCut(dt, H3, F3)
    fun filter5(dt: Double): BiquadCoefficients = highCut(dt, H4, F4)
    fun filter6(dt: Double): BiquadCoefficients = highCut(dt, H5, F5)
}
