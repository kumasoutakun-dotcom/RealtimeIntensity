package com.example.rtintensity.processor

/**
 * 震度階級(0, 1, 2, 3, 4, 5弱, 5強, 6弱, 6強, 7)。
 *
 * このファイルはこれまでの filter/processor パッケージの方針にならい、
 * Android APIに依存しない純粋なKotlinのみで構成している(色情報は
 * Android依存の [com.example.rtintensity.color.IntensityClassColors] 側に分離した)。
 *
 * 【判定に使う値についての重要な注意】
 * [fromRawIntensity] には、必ず丸め処理をしていない内部連続値
 * (IntensityCalculatorが返すrawIntensity、またはProcessedSample.rawIntensity)を
 * 渡すこと。表示用に丸めた値(roundedIntensity)を渡してはいけない。
 * 例: 内部値 4.495 (表示上は 4.50) は、境界表(下記)に対して
 * 3.5≤4.495<4.5 なので「震度4」と判定される。
 *
 * 境界値は、震度表示拡張タスクの仕様でそのまま指定された表を採用している。
 */
enum class IntensityClass(val label: String) {
    LEVEL_0("0"),
    LEVEL_1("1"),
    LEVEL_2("2"),
    LEVEL_3("3"),
    LEVEL_4("4"),
    LEVEL_5_LOWER("5弱"),
    LEVEL_5_UPPER("5強"),
    LEVEL_6_LOWER("6弱"),
    LEVEL_6_UPPER("6強"),
    LEVEL_7("7");

    companion object {
        /**
         * rawIntensity: 丸め処理をしていない内部連続値。
         * データがまだ無い場合(Double.NEGATIVE_INFINITY)やNaNはLEVEL_0を返す
         * (「揺れが検出されていない=震度0相当」という扱い。境界表の
         * 「0.5未満」という条件そのままに従った結果であり、特別扱いはしていない)。
         */
        fun fromRawIntensity(rawIntensity: Double): IntensityClass {
            if (rawIntensity.isNaN()) return LEVEL_0
            return when {
                rawIntensity < 0.5 -> LEVEL_0
                rawIntensity < 1.5 -> LEVEL_1
                rawIntensity < 2.5 -> LEVEL_2
                rawIntensity < 3.5 -> LEVEL_3
                rawIntensity < 4.5 -> LEVEL_4
                rawIntensity < 5.0 -> LEVEL_5_LOWER
                rawIntensity < 5.5 -> LEVEL_5_UPPER
                rawIntensity < 6.0 -> LEVEL_6_LOWER
                rawIntensity < 6.5 -> LEVEL_6_UPPER
                else -> LEVEL_7
            }
        }
    }
}
