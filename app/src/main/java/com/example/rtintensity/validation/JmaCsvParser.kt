package com.example.rtintensity.validation

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 気象庁の3成分CSVと、本アプリが書き出す同形式の
 * 「6行メタデータ + 3列加速度」形式を読み込むパーサ。
 *
 * 想定形式:
 * SITE CODE= 93051 益城町宮園,,
 * LAT.=  32.7880,,
 * LON.= 130.8190,,
 * Sampling Freq= 100Hz,,
 * UNIT  = gal(cm/s/s),,
 * INITIAL TIME = 2016 04 14 21 26 20,,
 * NS,EW,UD
 * -0.017,-0.017,-0.002
 * ...
 *
 * NS,EW,UD と X,Y,Z の両方を受け付ける。
 * 本アプリの書き出しはスマートフォンの実軸をそのまま表すため X,Y,Z を使用する。
 */
object JmaCsvParser {

    data class Record(
        val siteCode: String,
        val latitude: String,
        val longitude: String,
        val samplingHz: Double,
        val unit: String,
        val initialTime: String,
        val axis1Label: String,
        val axis2Label: String,
        val axis3Label: String,
        val component1Gal: DoubleArray,
        val component2Gal: DoubleArray,
        val component3Gal: DoubleArray
    )

    fun parse(input: InputStream): Record {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val lines = reader.readLines()
            .map { it.removePrefix("\uFEFF").trimEnd('\r') }

        if (lines.size < 8) {
            throw IllegalArgumentException("3成分CSVのデータ行が不足しています")
        }

        fun headerValue(prefix: String): String {
            val line = lines.firstOrNull { it.startsWith(prefix) }
                ?: throw IllegalArgumentException("ヘッダに \"$prefix\" が見つかりません")
            return line.substringAfter("=", "")
                .trim()
                .trimEnd(',')
                .trim()
        }

        val siteCode = headerValue("SITE CODE")
        val latitude = headerValue("LAT.")
        val longitude = headerValue("LON.")

        val samplingRaw = headerValue("Sampling Freq")
        val samplingHz = samplingRaw.removeSuffix("Hz").trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("Sampling Freqを解釈できません: $samplingRaw")
        if (!samplingHz.isFinite() || samplingHz <= 0.0) {
            throw IllegalArgumentException("Sampling Freqが不正です: $samplingRaw")
        }

        val unit = headerValue("UNIT")
        val initialTime = headerValue("INITIAL TIME")

        val columnIndex = lines.indexOfFirst {
            val cols = it.split(",").map { c -> c.trim().uppercase() }
            cols.size == 3 && (
                cols == listOf("NS", "EW", "UD") ||
                cols == listOf("X", "Y", "Z")
            )
        }
        if (columnIndex < 0) {
            throw IllegalArgumentException(
                "3成分の列ヘッダ(NS,EW,UD または X,Y,Z)が見つかりません"
            )
        }

        val headerCols = lines[columnIndex].split(",").map { it.trim() }
        val c1 = ArrayList<Double>()
        val c2 = ArrayList<Double>()
        val c3 = ArrayList<Double>()

        for (lineIndex in columnIndex + 1 until lines.size) {
            val line = lines[lineIndex].trim()
            if (line.isEmpty()) continue

            val cols = line.split(",").map { it.trim() }
            if (cols.size != 3) {
                throw IllegalArgumentException(
                    "データ行を3列として解釈できません (行${lineIndex + 1}): $line"
                )
            }

            val v1 = cols[0].toDoubleOrNull()
            val v2 = cols[1].toDoubleOrNull()
            val v3 = cols[2].toDoubleOrNull()
            if (v1 == null || v2 == null || v3 == null) {
                throw IllegalArgumentException(
                    "数値として解釈できないデータがあります (行${lineIndex + 1}): $line"
                )
            }

            c1.add(v1)
            c2.add(v2)
            c3.add(v3)
        }

        if (c1.isEmpty()) {
            throw IllegalArgumentException("加速度データがありません")
        }

        return Record(
            siteCode = siteCode,
            latitude = latitude,
            longitude = longitude,
            samplingHz = samplingHz,
            unit = unit,
            initialTime = initialTime,
            axis1Label = headerCols[0],
            axis2Label = headerCols[1],
            axis3Label = headerCols[2],
            component1Gal = c1.toDoubleArray(),
            component2Gal = c2.toDoubleArray(),
            component3Gal = c3.toDoubleArray()
        )
    }
}
