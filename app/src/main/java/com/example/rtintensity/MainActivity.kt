package com.example.rtintensity

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.rtintensity.color.IntensityClassColors
import com.example.rtintensity.color.KyoshinShindoColorMap
import com.example.rtintensity.processor.CalibrationMonitor
import com.example.rtintensity.processor.HysteresisIntensityClassifier
import com.example.rtintensity.processor.ProcessedSample
import com.example.rtintensity.processor.SeismicProcessor
import com.example.rtintensity.recorder.CsvRecorder
import com.example.rtintensity.sensor.AccelerometerSource
import com.example.rtintensity.ui.IntensityHistoryBandView
import com.example.rtintensity.ui.WaveformView
import com.example.rtintensity.validation.ValidationActivity
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var accelerometerSource: AccelerometerSource
    private val processor = SeismicProcessor()
    private val calibrationMonitor = CalibrationMonitor()
    private lateinit var csvRecorder: CsvRecorder

    private lateinit var textSampleRate: TextView
    private lateinit var textSensorStatus: TextView
    private lateinit var textCalibration: TextView
    private lateinit var textRecordingStatus: TextView
    private lateinit var buttonStartStop: Button

    private lateinit var waveformRaw: WaveformView
    private lateinit var waveformFiltered: WaveformView
    private lateinit var waveformIntensity: WaveformView

    // ---- 震度表示拡張(新規追加分) ----
    private lateinit var textIntensityClassBadge: TextView
    private lateinit var textMeasuredIntensity: TextView
    private lateinit var underlineMeasuredIntensity: View
    private lateinit var textMeasuredAcceleration: TextView
    private lateinit var underlineMeasuredAcceleration: View
    private lateinit var intensityHistoryBand: IntensityHistoryBandView
    private lateinit var waveformXyz: WaveformView

    private var isMeasuring = false

    // ---- UI更新スロットリング(要件2): センサーコールバックとは切り離し、
    //      最大でこの間隔(既定約30fps)でしかUIスレッドに投げない。 ----
    private val uiUpdateIntervalMillis = 33L
    private var lastUiUpdateElapsedMillis = 0L

    // ---- xyz波形の更新スロットリング(震度表示拡張タスクから維持: 最大10Hz) ----
    // 今回のノイズ対策タスクでは「XYZ波形は現状維持」という指示のため、
    // このレートは変更していない。
    private val xyzWaveformUpdateIntervalMillis = 100L
    private var lastXyzWaveformUpdateElapsedMillis = 0L

    // ---- 震度階級・計測震度相当・加速度・履歴帯の更新スロットリング ----
    // 【ノイズ対策タスクでの変更】
    // 実機(Nothing Phone (3a))で、震度階級・数値・色・履歴帯が
    // 頻繁に変化してチカチカする問題があった。調査の結果、これらは
    // 従来10Hzでの更新を意図していたにもかかわらず、実際にはUI全体の
    // 30fpsスロットリングでしか間引かれておらず(履歴帯・xyz波形だけが
    // 正しく10Hzになっていた)、震度階級バッジ・計測震度相当・加速度・
    // 下線色は実質30Hzで更新されていたことが判明した(STEP1報告参照)。
    // これが「チカチカ」の主要因の一つと考え、これらをまとめて
    // 約1Hzに間引く専用のタイマーを新設した。
    //
    // 1Hzという値は、気象庁の計測震度そのものの更新規則ではなく、
    // 「人間が目で見る表示の更新頻度」として本アプリが独自に選んだもので、
    // 強震モニタのリアルタイム震度表示更新間隔(旧: 約2秒に1回、
    // 現在: 約1秒に1回)を参考にしつつ、スマートフォンセンサーの
    // ノイズ特性に対して妥当かを検討した結果である。
    // rawIntensity・amplitudeGal自体は毎サンプル(センサーレートのまま)
    // 更新され続けており、CSVにも毎サンプル記録される。1Hzで間引くのは
    // あくまで人間向けの表示部分のみ。
    private val intensityDisplayUpdateIntervalMillis = 1000L
    private var lastIntensityDisplayUpdateElapsedMillis = 0L

    // 表示用震度階級のヒステリシス(要件6: 震度階級のチカチカ対策)。
    // 上昇は即座、下降は3回連続(≒3秒、上記1Hzでの呼び出し前提)で確認後に反映する。
    // 詳細な設計理由は HysteresisIntensityClassifier のコメントを参照。
    private val hysteresisClassifier = HysteresisIntensityClassifier()

    // ---- デバッグログ(要件6)用のカウンタ ----
    private val sampleCounter = AtomicLong(0)
    private val logEveryNSamples = 200L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // KyoshinShindoColorMapはアプリ起動時に1度だけ読み込み、以降はキャッシュのみ参照する(要件6-2)。
        KyoshinShindoColorMap.init(applicationContext)

        accelerometerSource = AccelerometerSource(this)
        csvRecorder = CsvRecorder(this)

        textSampleRate = findViewById(R.id.textSampleRate)
        textSensorStatus = findViewById(R.id.textSensorStatus)
        textCalibration = findViewById(R.id.textCalibration)
        textRecordingStatus = findViewById(R.id.textRecordingStatus)
        buttonStartStop = findViewById(R.id.buttonStartStop)

        waveformRaw = findViewById(R.id.waveformRaw)
        waveformFiltered = findViewById(R.id.waveformFiltered)
        waveformIntensity = findViewById(R.id.waveformIntensity)

        textIntensityClassBadge = findViewById(R.id.textIntensityClassBadge)
        textMeasuredIntensity = findViewById(R.id.textMeasuredIntensity)
        underlineMeasuredIntensity = findViewById(R.id.underlineMeasuredIntensity)
        textMeasuredAcceleration = findViewById(R.id.textMeasuredAcceleration)
        underlineMeasuredAcceleration = findViewById(R.id.underlineMeasuredAcceleration)
        intensityHistoryBand = findViewById(R.id.intensityHistoryBand)
        waveformXyz = findViewById(R.id.waveformXyz)
        // コマ数(容量)は同じ100のままにしているが、更新頻度が異なる
        // (xyz波形=10Hz→約10秒分、履歴帯=約1Hz→約100秒分)ため、
        // 両者が示す時間幅は今回の変更でもう一致しない。これは
        // 「XYZ波形は現状維持、震度階級・履歴帯は約1Hzに低下」という
        // 今回の指示を文字通り適用した結果であり、意図的な選択である
        // (時間幅を再び揃えたい場合は、別途容量の調整を検討すること)。
        intensityHistoryBand.setCapacity(HISTORY_CAPACITY)
        waveformXyz.setCapacity(HISTORY_CAPACITY)

        textSensorStatus.text = accelerometerSource.describeSensor()

        buttonStartStop.setOnClickListener {
            if (isMeasuring) stopMeasuring() else startMeasuring()
        }

        findViewById<Button>(R.id.buttonValidation).setOnClickListener {
            startActivity(Intent(this, ValidationActivity::class.java))
        }
    }

    private fun startMeasuring() {
        if (!accelerometerSource.isAvailable) {
            textSensorStatus.text = "加速度センサーが利用できません"
            return
        }
        processor.reset()
        calibrationMonitor.reset()
        sampleCounter.set(0)
        lastUiUpdateElapsedMillis = 0L
        lastXyzWaveformUpdateElapsedMillis = 0L
        lastIntensityDisplayUpdateElapsedMillis = 0L
        hysteresisClassifier.reset()
        intensityHistoryBand.clear()
        waveformXyz.clearAll()

        val file = csvRecorder.start()
        textRecordingStatus.text = "記録中: ${file.absolutePath}"

        // AccelerometerSource.start() は内部で SecurityException を捕捉して
        // フォールバックするため、ここで例外が飛んでくることは無いはずだが、
        // 予期しない失敗であってもアプリを落とさないよう念のため囲っておく。
        val started = try {
            accelerometerSource.start { ax, ay, az, timestampNanos ->
                handleSample(ax, ay, az, timestampNanos)
            }
        } catch (e: Exception) {
            Log.e(TAG, "accelerometerSource.start() で予期しない例外", e)
            false
        }

        if (started) {
            isMeasuring = true
            buttonStartStop.text = getString(R.string.btn_stop)
        } else {
            csvRecorder.stop()
            textRecordingStatus.text = "センサーの初期化に失敗しました(Logcatを確認してください)"
        }
    }

    private fun stopMeasuring() {
        accelerometerSource.stop()
        csvRecorder.stop()
        isMeasuring = false
        buttonStartStop.text = getString(R.string.btn_start)
        textRecordingStatus.text = "記録を停止しました: ${csvRecorder.currentFilePath()}"
    }

    /**
     * センサー用の専用バックグラウンドスレッド(AccelerometerSource参照)から
     * 呼ばれる。ここでの処理(フィルタ計算・CSV書き込み)はメインスレッドを
     * 一切ブロックしない。UIへの反映だけを [uiUpdateIntervalMillis] 間隔に
     * 間引いて runOnUiThread に投げる(要件2)。
     */
    private fun handleSample(ax: Float, ay: Float, az: Float, timestampNanos: Long) {
        val startNanos = System.nanoTime()
        try {
            val stats = calibrationMonitor.push(
                (ax * SeismicProcessor.MS2_TO_GAL).toDouble(),
                (ay * SeismicProcessor.MS2_TO_GAL).toDouble(),
                (az * SeismicProcessor.MS2_TO_GAL).toDouble()
            )
            val sample = processor.onNewSample(ax, ay, az, timestampNanos) ?: return

            // CSV保存は要件どおり毎サンプル行う(間引かない)。
            csvRecorder.append(sample)

            val nowElapsed = SystemClock.elapsedRealtime()
            if (nowElapsed - lastUiUpdateElapsedMillis >= uiUpdateIntervalMillis) {
                lastUiUpdateElapsedMillis = nowElapsed
                runOnUiThread { updateUi(sample, stats) }
            }

            val count = sampleCounter.incrementAndGet()
            if (count % logEveryNSamples == 0L) {
                val processingMicros = (System.nanoTime() - startNanos) / 1000
                Log.d(
                    TAG,
                    "count=$count rate=${"%.1f".format(sample.sampleRateHz)}Hz " +
                        "lastSampleProcessingMicros=$processingMicros"
                )
            }
        } catch (e: Exception) {
            // ここで例外を握りつぶさずログに残す(要件6)。
            // センサーコールバックの中で未捕捉例外が伝播すると、
            // バックグラウンドスレッドとはいえアプリ全体がクラッシュし得るため、
            // 1サンプル分の処理失敗としてログに残し計測は継続する。
            Log.e(TAG, "handleSample内で例外が発生した(このサンプルの処理をスキップして継続)", e)
        }
    }

    private fun updateUi(sample: ProcessedSample, stats: CalibrationMonitor.Stats) {
        // 計測震度・X/Y/Z現在値・3軸合成(フィルタ後)の個別表示は、
        // 上部の震度階級/計測震度相当/加速度/XYZ加速度波形と内容が
        // 重複するため、UIレイアウト整理タスクで下部詳細情報から削除した
        // (値の計算自体は sample に従来どおり残っており、CSVにも
        // 変更なく記録される。削除したのは表示のみ)。
        textSampleRate.text = String.format(Locale.JAPAN, "%.1f Hz", sample.sampleRateHz)
        textCalibration.text = String.format(
            Locale.JAPAN,
            "X:%.2f±%.2f Y:%.2f±%.2f Z:%.2f±%.2f",
            stats.meanX, stats.sdX, stats.meanY, stats.sdY, stats.meanZ, stats.sdZ
        )

        val rawCombinedBeforeFilter = kotlin.math.sqrt(
            sample.axGal * sample.axGal + sample.ayGal * sample.ayGal + sample.azGal * sample.azGal
        )
        waveformRaw.pushValue("raw", rawCombinedBeforeFilter.toFloat())
        waveformFiltered.pushValue("filtered", sample.combinedGal.toFloat())
        if (sample.roundedIntensity.isFinite()) {
            waveformIntensity.pushValue("intensity", sample.roundedIntensity.toFloat())
        }

        updateIntensityDisplayExtension(sample)
    }

    /**
     * 震度表示拡張(震度階級バッジ・計測震度相当・加速度・履歴帯・XYZ加速度波形)の更新。
     *
     * 【内部連続値と表示値の厳格な分離】
     * 震度階級の判定(ヒステリシス適用前の生の階級)には sample.rawIntensity
     * (丸め前)を使う。sample.roundedIntensity(気象庁式に丸めた表示専用の値)は、
     * このメソッドの中では判定・色付けに一切使わない。
     *
     * 【更新頻度(ノイズ対策タスクでの変更)】
     * XYZ加速度波形は従来どおり最大10Hz。震度階級バッジ・計測震度相当・加速度・
     * 下線色・履歴帯は、チカチカ対策として約1Hzに間引く(このメソッドが
     * 呼ばれる頻度自体は従来どおり最大30Hzのままだが、その中でさらに
     * 2つの独立したタイマーでネストして間引いている)。
     */
    private fun updateIntensityDisplayExtension(sample: ProcessedSample) {
        val nowElapsed = SystemClock.elapsedRealtime()

        // XYZ加速度波形(震度表示拡張タスクから維持: 最大10Hz、今回は変更しない)。
        if (nowElapsed - lastXyzWaveformUpdateElapsedMillis >= xyzWaveformUpdateIntervalMillis) {
            lastXyzWaveformUpdateElapsedMillis = nowElapsed
            waveformXyz.pushValue("X", sample.filteredXGal.toFloat(), Color.RED)
            waveformXyz.pushValue("Y", sample.filteredYGal.toFloat(), Color.GREEN)
            waveformXyz.pushValue("Z", sample.filteredZGal.toFloat(), Color.BLUE)
        }

        // 震度階級・計測震度相当・加速度・履歴帯(ノイズ対策: 約1Hzに間引く)。
        if (nowElapsed - lastIntensityDisplayUpdateElapsedMillis < intensityDisplayUpdateIntervalMillis) {
            return
        }
        lastIntensityDisplayUpdateElapsedMillis = nowElapsed

        // ヒステリシス適用後の表示用階級(上昇は即座、下降は持続確認後)。
        // rawIntensityそのものは一切変更していない。
        val displayedClass = hysteresisClassifier.update(sample.rawIntensity)
        val classColors = IntensityClassColors.colorsFor(displayedClass)

        textIntensityClassBadge.text = displayedClass.label
        textIntensityClassBadge.setTextColor(classColors.text)
        textIntensityClassBadge.background = GradientDrawable().apply {
            cornerRadius = 12f
            setColor(classColors.background)
            setStroke(4, classColors.border)
        }

        textMeasuredIntensity.text = if (sample.rawIntensity.isFinite()) {
            String.format(Locale.JAPAN, "%.2f", sample.rawIntensity)
        } else {
            "--"
        }
        // 計測震度の色付けは内部連続値(rawIntensity)をそのままKyoshinShindoColorMapへ。
        // (色付けにはヒステリシスを適用していない。ヒステリシスは離散的な
        //  「震度階級」表示のチカチカ対策であり、連続値の色は約1Hzへの
        //  間引きだけで十分に視覚的な変化が緩やかになるため。)
        underlineMeasuredIntensity.setBackgroundColor(KyoshinShindoColorMap.colorForIntensity(sample.rawIntensity))

        textMeasuredAcceleration.text = String.format(Locale.JAPAN, "%.2f gal", sample.pgaEquivalentGal)
        underlineMeasuredAcceleration.setBackgroundColor(KyoshinShindoColorMap.colorForPga(sample.pgaEquivalentGal))

        // 履歴帯もヒステリシス適用後の表示用階級を積む(履歴帯自体もチカチカ対策の対象のため)。
        intensityHistoryBand.push(displayedClass)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isMeasuring) {
            accelerometerSource.stop()
            csvRecorder.stop()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        // コマ数(容量)。xyz波形(10Hz)では約10秒分、履歴帯(約1Hz)では
        // 約100秒分に相当する(上のfindViewById直後のコメントを参照)。
        private const val HISTORY_CAPACITY = 100
    }
}
