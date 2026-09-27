package com.example.rtintensity.validation

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.rtintensity.R
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

/**
 * 気象庁の3成分CSV、または本アプリが書き出した同形式のCSVを
 * 1ファイルで読み込み、オフライン検証する。
 */
class ValidationActivity : AppCompatActivity() {

    private var selectedUri: Uri? = null

    private lateinit var textPicked: TextView
    private lateinit var textResult: TextView

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            selectedUri = uri
            updatePickedText()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_validation)

        textPicked = findViewById(R.id.textPickedFiles)
        textResult = findViewById(R.id.textResult)

        findViewById<Button>(R.id.buttonPickData).setOnClickListener {
            pickFile.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*"))
        }
        findViewById<Button>(R.id.buttonRun).setOnClickListener { runValidation() }
    }

    private fun updatePickedText() {
        textPicked.text = selectedUri?.lastPathSegment
            ?.substringAfterLast('/')
            ?: "未選択"
    }

    private fun runValidation() {
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, "3成分CSVを選択してください", Toast.LENGTH_SHORT).show()
            return
        }

        textResult.text = "計算中..."

        thread {
            try {
                val record =
                    contentResolver.openInputStream(uri)!!.use { JmaCsvParser.parse(it) }

                val n = minOf(
                    record.component1Gal.size,
                    record.component2Gal.size,
                    record.component3Gal.size
                )
                if (n <= 0) {
                    throw IllegalArgumentException("加速度データがありません")
                }

                val c1 = record.component1Gal.copyOf(n)
                val c2 = record.component2Gal.copyOf(n)
                val c3 = record.component3Gal.copyOf(n)

                val reference = OfflineJmaIntensity.computeReferenceIntensity(
                    c1, c2, c3, record.samplingHz
                )
                val isPhoneExport = record.axis1Label.equals("X", ignoreCase = true) &&
                    record.axis2Label.equals("Y", ignoreCase = true) &&
                    record.axis3Label.equals("Z", ignoreCase = true)

                val realtime = OfflineJmaIntensity.replayRealtimeFilter(
                    c1, c2, c3, record.samplingHz, primeFromFirstSecond = isPhoneExport
                )

                val roundedDiff =
                    realtime.maxRoundedIntensity - reference.referenceIntensity
                val durationSeconds = n / record.samplingHz

                runOnUiThread {
                    textResult.text = buildString {
                        appendLine("サイトコード: ${record.siteCode.ifBlank { "(未設定)" }}")
                        appendLine("初期時刻: ${record.initialTime.ifBlank { "(未設定)" }}")
                        appendLine(
                            "サンプリング周波数: ${formatHz(record.samplingHz)} Hz"
                        )
                        appendLine("データ点数: $n")
                        appendLine(
                            "記録時間: ${"%.2f".format(Locale.JAPAN, durationSeconds)} s"
                        )
                        appendLine("単位: ${record.unit.ifBlank { "(未設定)" }}")
                        appendLine(
                            "3成分: ${record.axis1Label},${record.axis2Label},${record.axis3Label}"
                        )
                        appendLine()
                        appendLine("[気象庁方式] FFTベース計測震度(区間全体)")
                        appendLine(
                            "  計測震度 = ${formatIntensity(reference.referenceIntensity)}"
                        )
                        appendLine(
                            "  基準振幅 a = ${"%.3f".format(
                                Locale.JAPAN,
                                reference.amplitudeGal
                            )} gal"
                        )
                        appendLine()
                        appendLine("[本アプリ方式] 漸化式リアルタイム震度")
                        appendLine(
                            "  区間中最大(丸め後) = ${
                                formatIntensity(realtime.maxRoundedIntensity)
                            }"
                        )
                        appendLine(
                            "  区間中最大(丸め前) = ${
                                "%.4f".format(Locale.JAPAN, realtime.maxRawIntensity)
                            }"
                        )
                        appendLine()
                        appendLine(
                            "差(丸め後) = ${
                                "%.2f".format(Locale.JAPAN, roundedDiff)
                            }"
                        )
                        appendLine()
                        appendLine("注: 気象庁方式は記録区間全体をFFTで処理するのに対し、")
                        appendLine("本アプリ方式は各サンプルを漸化式フィルタへ順次入力し、")
                        appendLine("現在サンプルの振幅からリアルタイム震度相当値を求める。")
                        appendLine("そのため、同じ波形でも両者は一致しない場合がある。")
                        if (isPhoneExport) {
                            appendLine()
                            appendLine("X/Y/Zの本アプリ書き出しデータでは、起動時過渡応答を")
                            appendLine("再現しないよう先頭約1秒の平均値でフィルタを初期化しています。")
                        }
                        if (record.latitude.isBlank() || record.longitude.isBlank()) {
                            appendLine()
                            appendLine("注: 緯度・経度はファイルに記録されていません。")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    textResult.text = "エラー: ${e.message ?: e::class.simpleName}"
                }
            }
        }
    }

    private fun formatHz(value: Double): String =
        if (abs(value - value.roundToInt()) < 1e-9) {
            value.roundToInt().toString()
        } else {
            "%.3f".format(Locale.JAPAN, value)
        }

    private fun formatIntensity(value: Double): String =
        if (value.isFinite()) "%.2f".format(Locale.JAPAN, value) else "--"
}
