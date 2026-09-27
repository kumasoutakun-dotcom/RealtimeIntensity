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
import com.example.rtintensity.processor.IntensityClass
import com.example.rtintensity.processor.CalibrationMonitor
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

    // ---- 震度表示の更新スロットリング ----
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

    // ---- PGA(1Hz色バー)用: 直近1秒間の combinedGal 最大値を集計する(仕様変更) ----
    // combinedGal(フィルタ後3軸合成の瞬時値)は handleSample() (センサー用の
    // 専用バックグラウンドスレッド)側で毎サンプル届くのに対し、PGA表示は
    // updateIntensityDisplayExtension() (UIスレッド、約1Hzに間引き済み)側で
    // 「直近1秒間の最大値」として読み出す。異なるスレッド間でDouble(8バイト)を
    // 素の var でやり取りするとJVM上でアトミック性・可視性が保証されないため、
    // ビットパターンをAtomicLongに載せてCAS(compare-and-set)で最大値を更新する
    // (下の sampleCounter と同じAtomicLongを使う方針を踏襲)。
    // 【簡略化として明記する点】
    // これは「1Hzタイマーが読み出すたびに0へリセットする」単純な集計(tumbling
    // window)であり、常に厳密に直近1.000秒間の値というわけではない
    // (次のリセットまでの実際の間隔は intensityDisplayUpdateIntervalMillis の
    // 呼び出しタイミング次第で多少前後する)。震度相当値の計算には使わない、
    // UI表示専用の集計値。
    private val pgaWindowMaxGalBits = AtomicLong(0L)

    /** combinedGal の新しいサンプルが来るたびに呼ぶ。直近1秒集計用の最大値をスレッドセーフに更新する。 */
    private fun updatePgaWindowMax(candidateGal: Double) {
        while (true) {
            val currentBits = pgaWindowMaxGalBits.get()
            val current = java.lang.Double.longBitsToDouble(currentBits)
            if (candidateGal <= current) return
            val newBits = java.lang.Double.doubleToLongBits(candidateGal)
            if (pgaWindowMaxGalBits.compareAndSet(currentBits, newBits)) return
        }
    }

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
        pgaWindowMaxGalBits.set(0L)
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

            // PGA(1Hz色バー)用の「直近1秒間の最大値」集計は、UIスレッドの間引きとは
            // 無関係に全サンプルに対して行う(仕様変更)。
            updatePgaWindowMax(sample.combinedGal)

            // CSV保存は要件どおり毎サンプル行う(間引かない、フォーマットも変更なし)。
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
     * 震度表示拡張(震度階級バッジ・計測震度相当・リアルタイム震度・PGA・
     * リアルタイム加速度・履歴帯・XYZ加速度波形)の更新。
     *
     * 【内部連続値と表示値の厳格な分離】
     * 震度階級の判定には sample.rawIntensity (丸め前)を使う。
     * sample.roundedIntensity (気象庁式に丸めた表示専用の値)は、
     * このメソッドの中では判定・色付けに使わない。
     *
     * 【仕様変更: 値の用途とUIへの受け渡しの整理】
     * 震度計算アルゴリズム自体(RealtimeIntensityFilter・IntensityCalculator)は
     * 一切変更していない。変更したのは「どの値をどの表示にどの頻度で渡すか」のみ。
     *   - リアルタイム加速度(textMeasuredAcceleration, 数値, 約30Hz):
     *     sample.combinedGal(フィルタ後3軸合成の瞬時値)をそのまま表示する。
     *     【確定事項・変更理由】従来はここに sample.pgaEquivalentGal
     *     (震度算出用の代表振幅)を表示していたが、現在は combinedGal の
     *     瞬時値に変更し、この数値は常に「今」を表す。
     *   - PGA(underlineMeasuredAcceleration, 色バー, 1Hz):
     *     updatePgaWindowMax() が集計した「直近1秒間のcombinedGal最大値」を
     *     KyoshinShindoColorMap.colorForPga() に渡す。震度算出には使わない、
     *     UI表示専用の集計値(sample.pgaEquivalentGalとは別物)。
     *   - リアルタイム震度(textMeasuredIntensity, 数値, 約30Hz):
     *     sample.rawIntensity (現在サンプルから直接算出した、丸め前の
     *     震度相当値)をそのまま表示する。
     *   - 計測震度相当(underlineMeasuredIntensity, 色バー, 1Hz):
     *     リアルタイム震度と同じsample.rawIntensityから色を算出する。
     *     色バーの更新頻度のみ1Hzに保つ(チカチカ対策、従来どおり)。
     *   - 震度階級(textIntensityClassBadge・intensityHistoryBand,
     *     バッジ+履歴帯, 1Hz): sample.rawIntensityから直接判定する。
     *     表示更新は従来どおり1Hzのままにして、表示上のチカチカを抑える。
     *
     * 【更新頻度(ノイズ対策タスクでの変更、今回も踏襲)】
     * XYZ加速度波形は従来どおり最大10Hz。震度階級バッジ・計測震度相当(色バー)・
     * PGA(色バー)・履歴帯は、チカチカ対策として約1Hzに間引く。一方、
     * リアルタイム加速度・リアルタイム震度の数値表示は、今回の仕様変更で
     * この間引きの対象外とし、このメソッドの呼び出し頻度(uiUpdateIntervalMillis
     * に従う、約30Hz)のまま毎回更新するようにした。
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

        // リアルタイム加速度(数値)・リアルタイム震度(数値)は仕様変更により1Hz間引きの
        // 対象外。このメソッドの呼び出し頻度(約30Hz)のまま、このサンプル自身の
        // 瞬時値をそのまま表示する。
        textMeasuredAcceleration.text = String.format(Locale.JAPAN, "%.2f gal", sample.combinedGal)
        textMeasuredIntensity.text = if (sample.rawIntensity.isFinite()) {
            String.format(Locale.JAPAN, "%.2f", sample.rawIntensity)
        } else {
            "--"
        }

        // 震度階級・計測震度相当(色バー)・PGA(色バー)・履歴帯(ノイズ対策: 約1Hzに間引く。従来どおり)。
        if (nowElapsed - lastIntensityDisplayUpdateElapsedMillis < intensityDisplayUpdateIntervalMillis) {
            return
        }
        lastIntensityDisplayUpdateElapsedMillis = nowElapsed

        // 計測震度相当(色バー)は、加速度と同様にこのサンプル自身の
        // rawIntensityをそのまま使う。追加のピーク保持・平滑化は行わない。
        val displayedClass = IntensityClass.fromRawIntensity(sample.rawIntensity)
        val classColors = IntensityClassColors.colorsFor(displayedClass)

        textIntensityClassBadge.text = displayedClass.label
        textIntensityClassBadge.setTextColor(classColors.text)
        textIntensityClassBadge.background = GradientDrawable().apply {
            cornerRadius = 12f
            setColor(classColors.background)
            setStroke(4, classColors.border)
        }

        // 計測震度相当の色バーは、リアルタイム震度と同じ値(このサンプルのrawIntensity)
        // から算出する(数値表示と下線色が食い違わないようにするため)。
        underlineMeasuredIntensity.setBackgroundColor(KyoshinShindoColorMap.colorForIntensity(sample.rawIntensity))

        // PGAの色バーは、リアルタイム加速度(combinedGal)から集計した直近1秒間の
        // 最大値を使う(sample.pgaEquivalentGalとは別物)。取得と同時に0へ
        // リセットし、次の1秒間の集計を開始する。
        val pgaGal = java.lang.Double.longBitsToDouble(pgaWindowMaxGalBits.getAndSet(0L))
        underlineMeasuredAcceleration.setBackgroundColor(KyoshinShindoColorMap.colorForPga(pgaGal))

        // 履歴帯も、この1Hz更新時点の表示階級を積む。
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
