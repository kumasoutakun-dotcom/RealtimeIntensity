package com.example.rtintensity.color

import android.graphics.Color
import com.example.rtintensity.processor.IntensityClass

/**
 * 震度階級バッジ・履歴帯に使う固定10色パレット。
 * 震度表示拡張タスクの仕様(5-1節)で指定された背景色/文字色/枠線色の
 * 表をそのまま採用している(独自に配色を作成していない)。
 * KyoshinShindoColorMap(連続値用, [KyoshinShindoColorMap.kt])とは別系統の、
 * 階級(離散値)専用の配色であることに注意。
 */
data class ClassColorSet(val background: Int, val text: Int, val border: Int)

object IntensityClassColors {
    private val table: Map<IntensityClass, ClassColorSet> = mapOf(
        IntensityClass.LEVEL_0 to ClassColorSet(Color.parseColor("#6482AA"), Color.parseColor("#F0F0FF"), Color.parseColor("#3C506E")),
        IntensityClass.LEVEL_1 to ClassColorSet(Color.parseColor("#193C4B"), Color.parseColor("#F0F0FF"), Color.parseColor("#3C6478")),
        IntensityClass.LEVEL_2 to ClassColorSet(Color.parseColor("#3264CD"), Color.parseColor("#F0F0FF"), Color.parseColor("#5A87D7")),
        IntensityClass.LEVEL_3 to ClassColorSet(Color.parseColor("#7DD27D"), Color.parseColor("#1E2832"), Color.parseColor("#4B824B")),
        IntensityClass.LEVEL_4 to ClassColorSet(Color.parseColor("#FFD700"), Color.parseColor("#1E2832"), Color.parseColor("#AA8C00")),
        IntensityClass.LEVEL_5_LOWER to ClassColorSet(Color.parseColor("#FF9B32"), Color.parseColor("#1E2832"), Color.parseColor("#CD6400")),
        IntensityClass.LEVEL_5_UPPER to ClassColorSet(Color.parseColor("#FF6900"), Color.parseColor("#1E2832"), Color.parseColor("#AA4600")),
        IntensityClass.LEVEL_6_LOWER to ClassColorSet(Color.parseColor("#E62D19"), Color.parseColor("#F0F0FF"), Color.parseColor("#F5968C")),
        IntensityClass.LEVEL_6_UPPER to ClassColorSet(Color.parseColor("#960014"), Color.parseColor("#F0F0FF"), Color.parseColor("#E1A0AA")),
        IntensityClass.LEVEL_7 to ClassColorSet(Color.parseColor("#8205C8"), Color.parseColor("#F0F0FF"), Color.parseColor("#D282FA"))
    )

    /** 初回アクセス時にmapOf(...)が1回構築されるのみで、以降は参照のみ(要件6-2と同様の考え方)。 */
    fun colorsFor(intensityClass: IntensityClass): ClassColorSet =
        table[intensityClass] ?: table.getValue(IntensityClass.LEVEL_0)
}
