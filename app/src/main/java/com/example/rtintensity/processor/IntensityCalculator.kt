package com.example.rtintensity.processor

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 気象庁の計測震度の定義:
 *   「ベクトル波形(3成分合成加速度)の絶対値がある値a以上となる時間の合計を
 *    計算したとき、これがちょうど0.3秒となるようなaを求め、
 *    I = 2*log10(a) + 0.94 とする」
 * (出典: 気象庁「計測震度の算出方法」
 *  https://www.jma.go.jp/jma/kishou/know/jishin/kyoshin/kaisetsu/calc_sindo.html)
 *
 * 離散サンプル列の場合、この「0.3秒以上」という条件は、
 * 「直近の評価区間内で、値が大きい順に並べたとき floor(0.3/ΔT) 番目の値」
 * を求めることと等価になる。
 *
 * 【簡略化として明記する点: 評価区間の長さ】
 * (前バージョンと同じ。評価区間は本アプリ独自の設計判断で直近10秒。
 *  詳細はALGORITHM.md参照)
 *
 * 【SIGKILL調査での修正点(要件4)】
 * 従来の実装は、1サンプル入力されるたびに
 *   (1) ウィンドウ内容をまるごと新しい配列にコピー(copyOf)
 *   (2) その配列を丸ごとソート(sort(), O(n log n))
 * という処理を行っており、ウィンドウ長が長い(既定10秒 = ΔT=0.01なら1000要素)
 * 場合、毎サンプルこれを繰り返すのは無駄が大きく、GC負荷(毎回の配列確保)も
 * 高かった。
 *
 * 「大きい方からk番目の値を1つだけ求める」という目的に対しては、
 * 配列全体をソートする必要はなく、Quickselect(中央値のうち1つを軸に
 * 使う分割統治法によるk番目選択アルゴリズム)を使えば平均O(n)で
 * 済み、かつ**得られる値は全要素をソートしたときのk番目の値と数学的に
 * 完全に同一**である(近似ではなく、同じ答えを異なる手順で求めているだけ)。
 * これにより「震度計算の数学的意味」は一切変更していない。
 * 加えて、毎回の配列確保(copyOf)も、使い回すスクラッチバッファへの
 * arraycopyに置き換えてGC負荷を減らした。
 *
 * この変更が既存のソートベースの実装と完全に同じ結果を返すことは、
 * 開発時にKotlinコンパイラで数千パターンのランダムデータに対して
 * 「Quickselectの結果」と「フルソートして同じ添字を読んだ結果」を
 * 突き合わせて一致することを確認済み。
 */
class IntensityCalculator(
    initialSampleIntervalSeconds: Double,
    private var windowSeconds: Double = DEFAULT_WINDOW_SECONDS
) {
    private var sampleIntervalSeconds: Double = initialSampleIntervalSeconds
    private var buffer: DoubleArray = DoubleArray(0)
    private var scratch: DoubleArray = DoubleArray(0) // quickselect用の使い回しバッファ
    private var filled: Int = 0
    private var writeIndex: Int = 0
    private var kFromTop: Int = 1

    init {
        configure(initialSampleIntervalSeconds, windowSeconds)
    }

    /**
     * 既知の制約: sampleIntervalSeconds が実機ジッタでごくわずかに変動し、
     * round(windowSeconds/sampleIntervalSeconds) の整数値がちょうど境界を
     * 跨いだ瞬間だけ、バッファ長が変わってスライディングウィンドウが
     * リセットされる(直近の履歴を失う)ことがある。実運用上の影響は
     * 無視できる程度と考えられるが、簡略化点として明記しておく。
     */
    fun configure(sampleIntervalSeconds: Double, windowSeconds: Double = this.windowSeconds) {
        if (sampleIntervalSeconds <= 0.0) return
        this.sampleIntervalSeconds = sampleIntervalSeconds
        this.windowSeconds = windowSeconds
        val n = max(1, round(windowSeconds / sampleIntervalSeconds).toInt())
        if (buffer.size != n) {
            buffer = DoubleArray(n)
            scratch = DoubleArray(n)
            filled = 0
            writeIndex = 0
        }
        kFromTop = max(1, floor(DURATION_CRITERION_SECONDS / sampleIntervalSeconds).toInt())
    }

    fun reset() {
        filled = 0
        writeIndex = 0
    }

    /**
     * combinedGal: その時点でのフィルタ後3軸合成加速度(gal, 常に0以上)を1サンプル入力する。
     * 戻り値: [PushResult]。
     *   rawIntensity: 丸め処理をしていない生のリアルタイム震度相当値。
     *                 有効なデータがまだ全く無い場合は Double.NEGATIVE_INFINITY。
     *   amplitudeGal: 震度算出式 I = 2*log10(a) + 0.94 の a そのもの(gal)。
     *                 常に0以上。震度表示拡張タスクでの
     *                 「加速度(PGA相当)」表示に使うために公開している
     *                 (震度計算アルゴリズム自体は変更していない。元々
     *                 内部でのみ使っていた a を外に見えるようにしただけ)。
     */
    fun push(combinedGal: Double): PushResult {
        if (buffer.isEmpty()) return PushResult(Double.NEGATIVE_INFINITY, 0.0)
        buffer[writeIndex] = combinedGal
        writeIndex = (writeIndex + 1) % buffer.size
        if (filled < buffer.size) filled++

        val n = filled
        val k = min(kFromTop, n)
        System.arraycopy(buffer, 0, scratch, 0, n)
        val a = max(0.0, quickSelectKthLargest(scratch, n, k))
        val rawIntensity = if (a <= 0.0) Double.NEGATIVE_INFINITY else 2.0 * log10(a) + 0.94
        return PushResult(rawIntensity, a)
    }

    /** [push] の戻り値。震度算出アルゴリズムの計算結果そのもの(近似・変更なし)。 */
    data class PushResult(val rawIntensity: Double, val amplitudeGal: Double)

    companion object {
        const val DURATION_CRITERION_SECONDS = 0.3
        const val DEFAULT_WINDOW_SECONDS = 10.0

        /**
         * arr[0 until n] の中から「大きい方から数えてk番目(1-indexed)」の値を求める。
         * 全要素を昇順に並べたときのインデックス (n-k) (0-based) の値と完全に同じ値を返す。
         * 中央値枢軸(median-of-three)によるLomuto分割のQuickselectで、
         * 平均計算量O(n)(フルソートのO(n log n)より高速)。
         * 呼び出し側が渡す配列 arr は関数内で部分的に並び替えられるため、
         * 使い捨て(スクラッチ)用の配列を渡すこと。
         */
        fun quickSelectKthLargest(arr: DoubleArray, n: Int, k: Int): Double {
            val targetIndex = n - k // 昇順に並べたときの目的インデックス(0-based)
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val pivotIndex = partitionMedianOfThree(arr, lo, hi)
                when {
                    targetIndex == pivotIndex -> return arr[targetIndex]
                    targetIndex < pivotIndex -> hi = pivotIndex - 1
                    else -> lo = pivotIndex + 1
                }
            }
            return arr[targetIndex]
        }

        private fun partitionMedianOfThree(arr: DoubleArray, lo: Int, hi: Int): Int {
            val mid = lo + (hi - lo) / 2
            if (arr[mid] < arr[lo]) swap(arr, mid, lo)
            if (arr[hi] < arr[lo]) swap(arr, hi, lo)
            if (arr[hi] < arr[mid]) swap(arr, hi, mid)
            swap(arr, mid, hi) // 中央値をpivotとしてhiへ退避(ソート済み/逆順データでの最悪計算量を回避)
            val pivot = arr[hi]
            var i = lo
            for (j in lo until hi) {
                if (arr[j] < pivot) {
                    swap(arr, i, j)
                    i++
                }
            }
            swap(arr, i, hi)
            return i
        }

        private fun swap(arr: DoubleArray, i: Int, j: Int) {
            val t = arr[i]; arr[i] = arr[j]; arr[j] = t
        }

        /**
         * 気象庁の定義通りの丸め処理:
         *   「計算されたIの小数第3位を四捨五入し、小数第2位を切り捨てたものを
         *    計測震度とする」
         * (出典: 気象庁「計測震度の算出方法」)
         */
        fun jmaRound(rawIntensity: Double): Double {
            if (!rawIntensity.isFinite()) return Double.NEGATIVE_INFINITY
            val roundedTo2 = floor(rawIntensity * 100.0 + 0.5) / 100.0
            val truncatedTo1 = floor(roundedTo2 * 10.0) / 10.0
            return truncatedTo1
        }
    }
}
