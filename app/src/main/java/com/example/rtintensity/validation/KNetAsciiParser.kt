package com.example.rtintensity.validation

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * K-NET/KiK-net ASCII形式のパーサ。
 *
 * フォーマットの根拠(一次資料で確認済み):
 *   防災科学技術研究所「K-NET ASCIIフォーマットについて」
 *   https://www.kyoshin.bosai.go.jp/ja/knetascii/
 * によれば、ファイルは以下の17行のヘッダの後にデータ本体が続く。
 *   1  Origin Time
 *   2  Lat.
 *   3  Lon.
 *   4  Depth. (km)
 *   5  Mag.
 *   6  Station Code
 *   7  Station Lat.
 *   8  Station Lon.
 *   9  Station Height(m)
 *   10 Record Time
 *   11 Sampling Freq(Hz)   例: "100Hz"
 *   12 Duration Time(s)
 *   13 Dir.                例: "N-S" (チャンネル/方向)
 *   14 Scale Factor        例: "3920(gal)/6182761"  (デジット値 -> gal への係数)
 *   15 Max. Acc. (gal)
 *   16 Last Correction
 *   17 Memo.
 *   18行目以降: 8列区切りのデジット値(整数)が改行しながら並ぶ。
 *
 * デジット値 × (Scale Factorの分子/分母) = 加速度[gal]。
 * (公式解説にある例: デジット値8009, スケールファクタ3920(gal)/6182761 なら
 *  加速度は約5.078gal、という換算式と一致する形で実装している。)
 *
 * データ行は本来 各値9桁固定幅とされているが、値同士は空白で区切られているため、
 * 本実装では固定幅ではなく空白区切りとして読み取っている
 * (値が右詰めで書かれていれば、空白区切りでも同じ結果になるはずであり、
 *  厳密な桁位置に依存しないぶん頑健である、という判断による簡略化)。
 */
object KNetAsciiParser {

    data class KNetComponent(
        val direction: String,
        val samplingHz: Double,
        val scaleNumeratorGal: Double,
        val scaleDenominator: Double,
        val accelerationGal: DoubleArray
    )

    fun parse(input: InputStream): KNetComponent {
        val reader: BufferedReader = BufferedReader(InputStreamReader(input, Charsets.US_ASCII))
        val headerLines = ArrayList<String>()
        repeat(17) {
            headerLines.add(reader.readLine() ?: throw IllegalArgumentException("ヘッダ行が17行に満たない: K-NET ASCII形式ではない可能性があります"))
        }

        fun headerValue(label: String): String {
            val line = headerLines.firstOrNull { it.startsWith(label) }
                ?: throw IllegalArgumentException("ヘッダに \"$label\" が見つかりません")
            return line.substring(label.length).trim()
        }

        val samplingRaw = headerValue("Sampling Freq(Hz)") // 例: "100Hz"
        val samplingHz = samplingRaw.removeSuffix("Hz").trim().toDouble()

        val scaleRaw = headerValue("Scale Factor") // 例: "3920(gal)/6182761"
        val scaleParts = scaleRaw.replace("(gal)", "").split("/")
        if (scaleParts.size != 2) throw IllegalArgumentException("Scale Factorの形式を解釈できません: $scaleRaw")
        val scaleNumerator = scaleParts[0].trim().toDouble()
        val scaleDenominator = scaleParts[1].trim().toDouble()

        val direction = headerValue("Dir.")

        val digits = ArrayList<Double>()
        reader.forEachLine { line ->
            if (line.isNotBlank()) {
                for (tok in line.trim().split(Regex("\\s+"))) {
                    digits.add(tok.toDouble())
                }
            }
        }

        val scale = scaleNumerator / scaleDenominator
        val accel = DoubleArray(digits.size) { digits[it] * scale }

        return KNetComponent(
            direction = direction,
            samplingHz = samplingHz,
            scaleNumeratorGal = scaleNumerator,
            scaleDenominator = scaleDenominator,
            accelerationGal = accel
        )
    }
}
