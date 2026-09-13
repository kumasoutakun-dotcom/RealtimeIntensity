package com.example.rtintensity.validation

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.rtintensity.R
import kotlin.concurrent.thread

/**
 * 要件10「検証」への対応。
 * K-NET/KiK-net ASCII形式の3成分ファイル(N-S, E-W, U-D)をユーザーに選ばせ、
 *   1) OfflineJmaIntensity.computeReferenceIntensity で「本来のFFTベース計測震度」を計算
 *   2) OfflineJmaIntensity.replayRealtimeFilter で「本アプリの漸化式リアルタイム震度」の
 *      記録区間中の最大値を計算
 * の両方を求めて誤差を画面に表示する。
 *
 * ファイルアクセスは Storage Access Framework (ACTION_OPEN_DOCUMENT) を使うため、
 * 追加の実行時ストレージ権限は不要。
 */
class ValidationActivity : AppCompatActivity() {

    private var uriNS: Uri? = null
    private var uriEW: Uri? = null
    private var uriUD: Uri? = null

    private lateinit var textPicked: TextView
    private lateinit var textResult: TextView

    private val pickNS = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uriNS = it; updatePickedText() }
    private val pickEW = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uriEW = it; updatePickedText() }
    private val pickUD = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uriUD = it; updatePickedText() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_validation)

        textPicked = findViewById(R.id.textPickedFiles)
        textResult = findViewById(R.id.textResult)

        findViewById<Button>(R.id.buttonPickNS).setOnClickListener { pickNS.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.buttonPickEW).setOnClickListener { pickEW.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.buttonPickUD).setOnClickListener { pickUD.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.buttonRun).setOnClickListener { runValidation() }
    }

    private fun updatePickedText() {
        textPicked.text = "N-S: ${uriNS?.lastPathSegment ?: "未選択"}\n" +
            "E-W: ${uriEW?.lastPathSegment ?: "未選択"}\n" +
            "U-D: ${uriUD?.lastPathSegment ?: "未選択"}"
    }

    private fun runValidation() {
        val ns = uriNS; val ew = uriEW; val ud = uriUD
        if (ns == null || ew == null || ud == null) {
            Toast.makeText(this, "3成分すべてのファイルを選択してください", Toast.LENGTH_SHORT).show()
            return
        }
        textResult.text = "計算中..."
        thread {
            try {
                val compNS = contentResolver.openInputStream(ns)!!.use { KNetAsciiParser.parse(it) }
                val compEW = contentResolver.openInputStream(ew)!!.use { KNetAsciiParser.parse(it) }
                val compUD = contentResolver.openInputStream(ud)!!.use { KNetAsciiParser.parse(it) }

                if (compNS.samplingHz != compEW.samplingHz || compNS.samplingHz != compUD.samplingHz) {
                    runOnUiThread {
                        textResult.text = "エラー: 3成分のサンプリング周波数が一致しません " +
                            "(NS=${compNS.samplingHz}Hz, EW=${compEW.samplingHz}Hz, UD=${compUD.samplingHz}Hz)"
                    }
                    return@thread
                }

                val fs = compNS.samplingHz
                val reference = OfflineJmaIntensity.computeReferenceIntensity(
                    compNS.accelerationGal, compEW.accelerationGal, compUD.accelerationGal, fs
                )
                val realtime = OfflineJmaIntensity.replayRealtimeFilter(
                    compNS.accelerationGal, compEW.accelerationGal, compUD.accelerationGal, fs
                )

                val roundedDiff = realtime.maxRoundedIntensity - reference.referenceIntensity

                runOnUiThread {
                    textResult.text = buildString {
                        appendLine("サンプリング周波数: $fs Hz")
                        appendLine("データ点数: ${compNS.accelerationGal.size}")
                        appendLine()
                        appendLine("[本来の方式] 気象庁FFTベース計測震度(この区間全体):")
                        appendLine("  I(JMA公式アルゴリズム) = ${reference.referenceIntensity}")
                        appendLine()
                        appendLine("[本アプリの方式] 漸化式リアルタイム震度の区間中最大値:")
                        appendLine("  I(リアルタイム,丸め後) = ${realtime.maxRoundedIntensity}")
                        appendLine("  I(リアルタイム,丸め前) = ${"%.4f".format(realtime.maxRawIntensity)}")
                        appendLine()
                        appendLine("誤差(丸め後の震度どうしの差) = ${"%.2f".format(roundedDiff)}")
                        appendLine()
                        appendLine("注: 気象庁公式アルゴリズムは記録区間全体を1つのFFTで処理する")
                        appendLine("のに対し、リアルタイム版は直近10秒のスライディングウィンドウ")
                        appendLine("内で0.3秒基準を評価している。この評価区間の違いは、特に")
                        appendLine("継続時間の短い/長い揺れで結果に差を生む要因になり得る点に")
                        appendLine("留意すること(ALGORITHM.md参照)。")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { textResult.text = "エラー: ${e.message}" }
            }
        }
    }
}
