package com.example.rtintensity.color

import android.content.Context
import android.graphics.Color
import kotlin.math.floor
import kotlin.math.log10

/**
 * 強震モニタのリアルタイム震度色マッピングデータ(ingen084氏「KyoshinShindoColorMap」)の
 * 読み込み・キャッシュ・色変換を行うオブジェクト。
 *
 * 出典: https://github.com/ingen084/KyoshinShindoColorMap
 * README記載の仕様どおり、CSVは「震度,R値,G値,B値」の形式で、
 * 実際にリポジトリから取得したファイルを本アプリの assets/KyoshinShindoColorMap.csv
 * にそのまま同梱し、それを読み込んで使用する(独自にRGB値を作成していない)。
 * 実データを確認したところ、震度 -3.0 から 7.0 まで 0.1 刻みで
 * 均等に101件のエントリが並んでいる(README記載の「7.0の色を試験的に追加」分も
 * 含めて、実際には -3.0〜7.0 の等間隔グリッドになっている)。
 *
 * 【ライセンスに関する注意】
 * 当該リポジトリにはLICENSEファイルが存在しない。READMEには
 * 「防災アプリケーションの今後の発展のために公開しています」「クレジットの
 * 表記はなるべくしていただけると助かります」と明記されているため、
 * クレジット表記(本アプリでは設定画面等に掲示。要件6-4参照)を行うことを
 * 条件に、この趣旨に沿って利用している。正式なOSSライセンスではない点は
 * 認識しておくこと。
 *
 * 【キャッシュについて(要件6-2)】
 * [init] はアプリ起動後、最初の1回だけ assets からCSVを読み込み、
 * IntArray(RGBのColor int)にパースしてメモリ上に保持する。以降は
 * この配列を参照するのみで、ファイル再読み込み・文字列パース・
 * Colorオブジェクトの都度生成は行わない。[init] は複数回呼んでも
 * 2回目以降は何もしない(冪等)。
 */
object KyoshinShindoColorMap {

    private const val ASSET_FILE_NAME = "KyoshinShindoColorMap.csv"
    private const val MIN_INTENSITY = -3.0
    private const val MAX_INTENSITY = 7.0
    private const val STEP = 0.1
    private const val ENTRY_COUNT = 101 // -3.0 ... 7.0, 0.1刻み

    // インデックス i が示す震度は MIN_INTENSITY + i*STEP。
    private var colors: IntArray = IntArray(0)
    private var loaded = false

    /** アプリ起動時に一度だけ呼ぶこと(例: MainActivity.onCreate)。 */
    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        val loadedColors = IntArray(ENTRY_COUNT) { Color.GRAY } // 万一パース失敗した行のフォールバック
        try {
            context.assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val parts = trimmed.split(",")
                    if (parts.size != 4) continue
                    val intensity = parts[0].toDoubleOrNull() ?: continue
                    val r = parts[1].toIntOrNull() ?: continue
                    val g = parts[2].toIntOrNull() ?: continue
                    val b = parts[3].toIntOrNull() ?: continue
                    val index = indexForIntensity(intensity)
                    if (index in 0 until ENTRY_COUNT) {
                        loadedColors[index] = Color.rgb(
                            r.coerceIn(0, 255),
                            g.coerceIn(0, 255),
                            b.coerceIn(0, 255)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("KyoshinShindoColorMap", "CSV読み込みに失敗した。フォールバック色(灰色)を使用する", e)
        }
        colors = loadedColors
        loaded = true
    }

    private fun indexForIntensity(intensity: Double): Int =
        Math.round((intensity - MIN_INTENSITY) / STEP).toInt()

    /**
     * 内部連続値(計測震度相当, 丸め前)をそのままこのカラーマップの定義域
     * (-3.0〜7.0)に対応させ、隣接する0.1刻みエントリ間を線形補間して
     * 色を返す(要件5-2 ①「0.1刻みのデータの場合は...適切に補間」に対応)。
     * 範囲外・NaN・Infinityは最低色/最高色にクランプする(要件6-3)。
     */
    fun colorForIntensity(intensity: Double): Int {
        if (!loaded || colors.isEmpty()) return Color.GRAY
        val safe = sanitize(intensity, MIN_INTENSITY, MAX_INTENSITY)
        return interpolatedColor(safe)
    }

    /**
     * PGA相当値[gal]を要件5-2 ②の対数正規化式で -3.0〜7.0 の震度スケールへ
     * 写像したうえで、[colorForIntensity] と同じ補間ロジックで色を求める。
     *
     *   t = (log10(PGA) + 2) / 5  (PGA=0.01galでt=0, PGA=1000galでt=1)
     *   t を [0,1] にクランプ
     *   このアプリのカラーテーブルは震度スケール(-3.0〜7.0, 幅10.0)なので、
     *   t をそのままカラーテーブルの定義域に線形に対応させる:
     *     mappedIntensity = MIN_INTENSITY + t * (MAX_INTENSITY - MIN_INTENSITY)
     *
     * PGA<=0、NaN、Infinityは最低色にクランプする(要件6-3)。
     */
    fun colorForPga(pgaGal: Double): Int {
        if (!loaded || colors.isEmpty()) return Color.GRAY
        if (pgaGal.isNaN()) return colors[0]
        if (pgaGal <= 0.0) return colors[0]
        if (pgaGal.isInfinite()) return colors[ENTRY_COUNT - 1]

        val logValue = log10(pgaGal)
        var t = (logValue + 2.0) / 5.0
        t = t.coerceIn(0.0, 1.0)
        val mappedIntensity = MIN_INTENSITY + t * (MAX_INTENSITY - MIN_INTENSITY)
        return interpolatedColor(mappedIntensity)
    }

    private fun sanitize(value: Double, lo: Double, hi: Double): Double {
        if (value.isNaN()) return lo
        if (value <= lo) return lo
        if (value >= hi) return hi
        return value
    }

    /** value(MIN_INTENSITY..MAX_INTENSITYにクランプ済み)から線形補間色を求める。 */
    private fun interpolatedColor(value: Double): Int {
        val posFloat = (value - MIN_INTENSITY) / STEP
        // 浮動小数点の丸め誤差により、本来ちょうどグリッド点であるべき値が
        // わずかにずれることがあるため、極めて近い場合は最寄りの整数に
        // 丸めてから使う(0.1刻みのグリッドに対して十分小さい許容誤差1e-6)。
        // この丸め誤差を放置すると、ちょうど0.1刻みの値を渡したときに
        // 隣の色とわずかに混ざった色になってしまう(検証時にkotlinコンパイラの
        // テストで実際に検出し、この対策を追加した)。
        val rounded = Math.round(posFloat).toDouble()
        val snapped = if (kotlin.math.abs(posFloat - rounded) < 1e-6) rounded else posFloat
        val lowIndex = floor(snapped).toInt().coerceIn(0, ENTRY_COUNT - 1)
        val highIndex = (lowIndex + 1).coerceIn(0, ENTRY_COUNT - 1)
        val frac = (snapped - lowIndex).coerceIn(0.0, 1.0)
        if (lowIndex == highIndex) return colors[lowIndex]
        return lerpColor(colors[lowIndex], colors[highIndex], frac)
    }

    private fun lerpColor(colorA: Int, colorB: Int, t: Double): Int {
        val r = lerpChannel(Color.red(colorA), Color.red(colorB), t)
        val g = lerpChannel(Color.green(colorA), Color.green(colorB), t)
        val b = lerpChannel(Color.blue(colorA), Color.blue(colorB), t)
        return Color.rgb(r, g, b)
    }

    private fun lerpChannel(a: Int, b: Int, t: Double): Int =
        (a + (b - a) * t).toInt().coerceIn(0, 255)
}
