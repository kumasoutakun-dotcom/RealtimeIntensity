package com.example.rtintensity.recorder

import android.content.Context
import com.example.rtintensity.processor.ProcessedSample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 要件8「データ保存」への対応。
 * 列: timestamp, ax, ay, az, filteredX, filteredY, filteredZ, combined, realtimeIntensity
 *
 * 保存先はスコープドストレージの制約を受けないアプリ専用領域
 * (getExternalFilesDir)とし、実行時ストレージ権限を不要にしている。
 * timestamp列には、後でPython等で扱いやすいように、
 * SensorEvent.timestamp(起動からのモノトニックなナノ秒)をそのまま
 * 書き出す。壁時計時刻ではない点に注意(コメントをCSVヘッダ直後にも残す)。
 */
class CsvRecorder(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null

    val isRecording: Boolean get() = writer != null

    fun start(): File {
        // getExternalFilesDir は外部ストレージが一時的に利用不可の場合に null を
        // 返すことがある(Android公式ドキュメントに明記された挙動)ため、
        // その場合はアプリ内部ストレージ(filesDir)にフォールバックする。
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "recordings").apply { mkdirs() }
        val name = "rtintensity_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date()) + ".csv"
        val file = File(dir, name)
        val w = BufferedWriter(FileWriter(file))
        w.write("# timestamp is SensorEvent.timestamp in nanoseconds since an arbitrary monotonic epoch (NOT wall-clock time)\n")
        w.write("timestamp,ax,ay,az,filteredX,filteredY,filteredZ,combined,realtimeIntensity\n")
        writer = w
        currentFile = file
        return file
    }

    fun append(sample: ProcessedSample) {
        val w = writer ?: return
        w.write(
            "${sample.timestampNanos},${sample.axGal},${sample.ayGal},${sample.azGal}," +
                "${sample.filteredXGal},${sample.filteredYGal},${sample.filteredZGal}," +
                "${sample.combinedGal},${sample.rawIntensity}\n"
        )
    }

    fun stop() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    fun currentFilePath(): String? = currentFile?.absolutePath
}
