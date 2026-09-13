package com.example.rtintensity

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.rtintensity.processor.CalibrationMonitor
import com.example.rtintensity.processor.ProcessedSample
import com.example.rtintensity.processor.SeismicProcessor
import com.example.rtintensity.recorder.CsvRecorder
import com.example.rtintensity.sensor.AccelerometerSource
import com.example.rtintensity.ui.WaveformView
import com.example.rtintensity.validation.ValidationActivity
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var accelerometerSource: AccelerometerSource
    private val processor = SeismicProcessor()
    private val calibrationMonitor = CalibrationMonitor()
    private lateinit var csvRecorder: CsvRecorder

    private lateinit var textIntensityValue: TextView
    private lateinit var textAx: TextView
    private lateinit var textAy: TextView
    private lateinit var textAz: TextView
    private lateinit var textCombined: TextView
    private lateinit var textSampleRate: TextView
    private lateinit var textSensorStatus: TextView
    private lateinit var textCalibration: TextView
    private lateinit var textRecordingStatus: TextView
    private lateinit var buttonStartStop: Button

    private lateinit var waveformRaw: WaveformView
    private lateinit var waveformFiltered: WaveformView
    private lateinit var waveformIntensity: WaveformView

    private var isMeasuring = false

    // ---- UI更新スロットリング(要件2): センサーコールバックとは切り離し、
    //      最大でこの間隔(既定約30fps)でしかUIスレッドに投げない。 ----
    private val uiUpdateIntervalMillis = 33L
    private var lastUiUpdateElapsedMillis = 0L

    // ---- デバッグログ(要件6)用のカウンタ ----
    private val sampleCounter = AtomicLong(0)
    private val logEveryNSamples = 200L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        accelerometerSource = AccelerometerSource(this)
        csvRecorder = CsvRecorder(this)

        textIntensityValue = findViewById(R.id.textIntensityValue)
        textAx = findViewById(R.id.textAx)
        textAy = findViewById(R.id.textAy)
        textAz = findViewById(R.id.textAz)
        textCombined = findViewById(R.id.textCombined)
        textSampleRate = findViewById(R.id.textSampleRate)
        textSensorStatus = findViewById(R.id.textSensorStatus)
        textCalibration = findViewById(R.id.textCalibration)
        textRecordingStatus = findViewById(R.id.textRecordingStatus)
        buttonStartStop = findViewById(R.id.buttonStartStop)

        waveformRaw = findViewById(R.id.waveformRaw)
        waveformFiltered = findViewById(R.id.waveformFiltered)
        waveformIntensity = findViewById(R.id.waveformIntensity)

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
        textIntensityValue.text = if (sample.roundedIntensity.isFinite()) {
            String.format(Locale.JAPAN, "%.1f", sample.roundedIntensity)
        } else {
            "--"
        }
        textAx.text = String.format(Locale.JAPAN, "%.2f", sample.axGal)
        textAy.text = String.format(Locale.JAPAN, "%.2f", sample.ayGal)
        textAz.text = String.format(Locale.JAPAN, "%.2f", sample.azGal)
        textCombined.text = String.format(Locale.JAPAN, "%.2f", sample.combinedGal)
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
    }
}
