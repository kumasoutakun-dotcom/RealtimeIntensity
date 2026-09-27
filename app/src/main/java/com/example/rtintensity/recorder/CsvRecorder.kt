package com.example.rtintensity.recorder

import android.content.Context
import android.net.Uri
import com.example.rtintensity.processor.ProcessedSample
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 計測データを、気象庁3成分CSVと同じ構造
 * (メタデータ6行 + 3成分列 + 加速度データ)で保存する。
 *
 * 保存対象は生の3軸加速度(ax/ay/az)[gal]。
 * フィルタ後波形やリアルタイム震度などの内部計算値はこの標準形式には含めない。
 *
 * スマートフォンの実軸はX/Y/Zなので、列名もX,Y,Zとする。
 * SITE CODEはアプリ名、緯度経度は未設定。
 * INITIAL TIMEは1秒ウォームアップ後、最初に保存したサンプルの壁時計時刻。
 */
class CsvRecorder(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var headerWritten = false
    private var recordingStartWallClockMillis: Long = 0L

    val isRecording: Boolean get() = writer != null

    fun start(): File {
        stop()

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "recordings").apply { mkdirs() }
        val name = "rtintensity_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date()) + ".csv"

        val file = File(dir, name)
        writer = BufferedWriter(FileWriter(file))
        currentFile = file
        headerWritten = false
        recordingStartWallClockMillis = 0L
        return file
    }

    private fun writeHeader(samplingHz: Double) {
        val w = writer ?: return

        val initialTime = SimpleDateFormat(
            "yyyy MM dd HH mm ss",
            Locale.US
        ).format(Date(recordingStartWallClockMillis))

        val frequency = maxOf(1, samplingHz.roundToInt())

        w.write("SITE CODE= RealtimeIntensity,,\n")
        w.write("LAT.=  ,,\n")
        w.write("LON.=  ,,\n")
        w.write("Sampling Freq= ${frequency}Hz,,\n")
        w.write("UNIT  = gal(cm/s/s),,\n")
        w.write("INITIAL TIME = $initialTime,,\n")
        w.write("X,Y,Z\n")
    }

    fun append(sample: ProcessedSample) {
        val w = writer ?: return

        if (!headerWritten) {
            // SeismicProcessorの1秒ウォームアップを通過した最初の
            // 本番サンプルを「記録開始」とする。
            recordingStartWallClockMillis = System.currentTimeMillis()
            writeHeader(sample.sampleRateHz)
            headerWritten = true
        }

        w.write("${sample.axGal},${sample.ayGal},${sample.azGal}\n")
    }

    fun stop() {
        writer?.flush()
        writer?.close()
        writer = null
    }

    fun currentFilePath(): String? =
        currentFile?.takeIf { it.exists() }?.absolutePath

    fun currentFileName(): String? =
        currentFile?.takeIf { it.exists() }?.name

    fun hasRecordedData(): Boolean {
        val file = currentFile ?: return false
        return file.exists() && file.length() > 0L && headerWritten
    }

    /**
     * 計測終了後の最新CSVをStorage Access Frameworkで選んだ場所へコピーする。
     */
    fun exportTo(uri: Uri): Boolean {
        val source = currentFile ?: return false
        if (isRecording || !source.exists() || !headerWritten) return false

        val output = context.contentResolver.openOutputStream(uri) ?: return false
        output.use { out ->
            source.inputStream().use { input ->
                input.copyTo(out)
            }
        }
        return true
    }
}
