package com.example.rtintensity.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Sensor.TYPE_ACCELEROMETER (重力込みの生の加速度)を購読するラッパー。
 *
 * 【SIGKILL調査で確認された事実】
 * Logcatにより、計測開始直後に以下の SecurityException が発生していることが
 * 確認された(AccelerometerSource.start → MainActivity.startMeasuring):
 *   "To use the sampling rate of 0 microseconds, app needs to declare
 *    the normal permission HIGH_SAMPLING_RATE_SENSORS."
 * これは Android 12(API 31)以降、SENSOR_DELAY_FASTEST(=遅延0を要求)を
 * 含む200Hz超のサンプリング要求には、AndroidManifest.xmlでの
 * android.permission.HIGH_SAMPLING_RATE_SENSORS 宣言が必須になったため。
 * 対応として、(1) マニフェストに当該権限(normal権限。実行時ダイアログ不要)を
 * 追加し、(2) 万一それでも登録に失敗した場合に備えて、本クラス内でも
 * SecurityExceptionを捕捉し、より低いレート(SENSOR_DELAY_GAME, 約50Hz相当)に
 * フォールバックする防御的な実装にした。センサー処理そのもの(要求レート)は
 * 変更していない。
 *
 * 【センサーコールバックの実行スレッドについて】
 * 従来はHandlerを指定せずに registerListener していたため、
 * onSensorChanged は登録元スレッド(=MainActivityのメインUIスレッド)で
 * 呼ばれていた。この場合、フィルタ計算・CSV書き込み・UI更新が
 * すべてメインスレッド上で行われることになり、高いサンプリング周波数下では
 * メインスレッドを塞いでANR(操作不能判定)や強制終了の原因になり得る。
 * 本修正では専用のHandlerThreadを用意し、そのHandlerを
 * registerListenerに渡すことで、センサーイベントの受信・後続処理を
 * メインスレッドから完全に切り離した。
 */
class AccelerometerSource(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var currentListener: SensorEventListener? = null

    val isAvailable: Boolean get() = accelerometer != null

    /** センサーの機種依存情報。「センサー状態」表示に使う。 */
    fun describeSensor(): String {
        val s = accelerometer ?: return "加速度センサーが見つかりません"
        return "${s.name} / 分解能=${s.resolution} m/s^2 / 最大レンジ=${s.maximumRange} m/s^2"
    }

    fun start(onSample: (ax: Float, ay: Float, az: Float, timestampNanos: Long) -> Unit): Boolean {
        val sensor = accelerometer ?: return false

        val thread = HandlerThread("AccelerometerSensorThread").also { it.start() }
        handlerThread = thread
        val handler = Handler(thread.looper)
        backgroundHandler = handler

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                onSample(event.values[0], event.values[1], event.values[2], event.timestamp)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* 未使用 */ }
        }
        currentListener = listener

        val registered = try {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } catch (e: SecurityException) {
            Log.w(
                TAG,
                "SENSOR_DELAY_FASTEST requires android.permission.HIGH_SAMPLING_RATE_SENSORS; " +
                    "falling back to SENSOR_DELAY_GAME (~50Hz相当)", e
            )
            try {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
            } catch (e2: SecurityException) {
                Log.e(TAG, "SENSOR_DELAY_GAME でもセンサー登録に失敗した。計測を開始できない。", e2)
                stop()
                return false
            }
        }

        if (!registered) {
            Log.e(TAG, "sensorManager.registerListener がfalseを返した(センサー登録失敗)")
            stop()
        }
        return registered
    }

    fun stop() {
        currentListener?.let { sensorManager.unregisterListener(it) }
        currentListener = null
        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null
    }

    companion object {
        private const val TAG = "AccelerometerSource"
    }
}
