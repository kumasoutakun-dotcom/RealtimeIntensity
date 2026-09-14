package com.example.rtintensity.processor

/**
 * 表示用の震度階級に、下降方向のみのヒステリシス(単純な持続確認)を
 * 適用するための状態機械。
 *
 * 【設計方針(震度階級のチカチカ対策)】
 * - 上昇方向(rawClassが現在表示している階級以上になった場合)は
 *   即座に反映する。実際の地震動の立ち上がりを遅らせないため。
 * - 下降方向(rawClassが現在表示している階級を下回った場合)は、
 *   その状態が [requiredConsecutiveUpdates] 回連続するまで表示を
 *   維持する。この間に一度でも現在の表示階級以上に戻ったら、
 *   下降の判定はリセットされる(カウントは0に戻る)。
 * - 弱い揺れを無条件に消す処理ではない。あくまで「表示している階級を
 *   下げる」タイミングを遅らせるだけであり、内部の連続値
 *   (rawIntensity)そのものには一切手を加えない。
 *
 * 【requiredConsecutiveUpdates の既定値(3)について】
 * この値は気象庁・防災科研の公式資料に基づくものではなく、
 * 「1Hz程度で呼ばれる」という前提のもとで本アプリ独自に選んだ値である。
 * 3回(≒3秒)とした理由:
 *   - 境界付近の短時間のノイズ的な上下動(1〜2秒程度)を吸収できる長さ。
 *   - 実際に揺れが収まった場合に、表示が下がるまでの遅延が
 *     体感的に不自然にならない程度の短さ(参考値アプリであり、
 *     緊急地震速報のような即時性が必須の用途ではないため)。
 *呼び出し側の更新間隔を変える場合は、この回数も合わせて見直すこと。
 */
class HysteresisIntensityClassifier(
    private val requiredConsecutiveUpdates: Int = DEFAULT_REQUIRED_CONSECUTIVE_UPDATES
) {
    var displayedClass: IntensityClass = IntensityClass.LEVEL_0
        private set

    private var belowCount: Int = 0

    fun reset() {
        displayedClass = IntensityClass.LEVEL_0
        belowCount = 0
    }

    /**
     * rawIntensity: 丸め処理をしていない内部連続値。
     * 呼び出し1回を「1更新サイクル」とみなす(呼び出し側が
     * 意図した頻度、例えば1Hzで呼ぶことを前提とする)。
     * 戻り値: このサイクルで実際に表示すべき震度階級。
     */
    fun update(rawIntensity: Double): IntensityClass {
        val rawClass = IntensityClass.fromRawIntensity(rawIntensity)
        if (rawClass.ordinal >= displayedClass.ordinal) {
            // 上昇または同じ: 即座に反映し、下降の保留カウントはリセットする。
            displayedClass = rawClass
            belowCount = 0
        } else {
            // 下降候補: 連続して下回った回数を数える。
            belowCount++
            if (belowCount >= requiredConsecutiveUpdates) {
                displayedClass = rawClass
                belowCount = 0
            }
        }
        return displayedClass
    }

    companion object {
        const val DEFAULT_REQUIRED_CONSECUTIVE_UPDATES = 3
    }
}
