package com.squirrel.polestarsound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

enum class SoundMode { OFF, ENGINE_V6, SPACESHIP }

/**
 * 실시간 PCM 합성 엔진.
 * - ENGINE_V6: 6기통 4행정 엔진의 점화 주파수를 기반으로 배음 + 배기 노이즈를 합성
 * - SPACESHIP: 디튠된 저음 오실레이터 + LFO 변조 + 필터링 노이즈로 SF 우주선 험(hum) 사운드 생성
 *
 * rpm/throttle 값은 실제 차량 CAN 데이터가 아니라 GPS 속도(및 가속도)로부터 추정한 값입니다.
 * (서드파티 앱은 Android Automotive의 차량 속성 API에 접근할 수 없기 때문)
 */
class SoundEngine {

    companion object {
        private const val SAMPLE_RATE = 44100
    }

    @Volatile var mode: SoundMode = SoundMode.OFF
    @Volatile var rpm: Float = 800f          // 추정 RPM (아이들 ~ 레드라인)
    @Volatile var throttle: Float = 0f       // 0f..1f, 가속 페달 추정치(속도 변화율 기반)
    @Volatile var masterVolume: Float = 0.6f // 0f..1f

    private var track: AudioTrack? = null
    private var renderThread: Thread? = null
    @Volatile private var running = false

    // 합성용 위상 누적기
    private var enginePhase = DoubleArray(4)
    private var noiseFilterState = 0f
    private var spaceshipPhase = DoubleArray(3)
    private var lfoPhase = 0.0

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = (minBuf * 2).coerceAtLeast(4096)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track?.play()
        running = true
        renderThread = Thread { renderLoop(bufSize) }.also { it.start() }
    }

    fun stop() {
        running = false
        renderThread?.join(300)
        renderThread = null
        track?.stop()
        track?.release()
        track = null
    }

    private fun renderLoop(bufSize: Int) {
        val chunk = ShortArray(bufSize / 2)
        while (running) {
            when (mode) {
                SoundMode.OFF -> chunk.fill(0)
                SoundMode.ENGINE_V6 -> synthEngine(chunk)
                SoundMode.SPACESHIP -> synthSpaceship(chunk)
            }
            track?.write(chunk, 0, chunk.size)
        }
    }

    // ---------------- 6기통 엔진 사운드 ----------------
    // 4행정 6기통: 크랭크 1회전당 3회 점화 -> 점화 주파수 = (rpm/60) * 3
    private fun synthEngine(out: ShortArray) {
        val firingFreq = (rpm / 60f) * 3f
        val amp = (0.25f + throttle * 0.55f) * masterVolume

        for (i in out.indices) {
            val t = 1.0 / SAMPLE_RATE

            // 기본 점화 주파수 + 배음 3개 (거친 엔진음 질감)
            var sample = 0.0
            val harmonics = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
            val harmAmp = doubleArrayOf(1.0, 0.55, 0.30, 0.15)
            for (h in harmonics.indices) {
                enginePhase[h] += 2.0 * PI * firingFreq * harmonics[h] * t
                if (enginePhase[h] > 2.0 * PI) enginePhase[h] -= 2.0 * PI
                sample += harmAmp[h] * sin(enginePhase[h])
            }
            sample /= 2.0

            // 배기 노이즈 (저역 통과 필터링된 화이트노이즈로 러프한 배기음 표현)
            val whiteNoise = Random.nextFloat() * 2f - 1f
            noiseFilterState += 0.15f * (whiteNoise - noiseFilterState)
            val exhaust = noiseFilterState * (0.35 + throttle * 0.4)

            var mixed = (sample * 0.7 + exhaust * 0.3) * amp
            mixed = mixed.coerceIn(-1.0, 1.0)
            out[i] = (mixed * Short.MAX_VALUE).toInt().toShort()
        }
    }

    // ---------------- 우주선 사운드 ----------------
    // 디튠된 저음 오실레이터 3개 + 느린 LFO로 진폭/피치를 흔들어 유기적인 SF 험 사운드 생성
    private fun synthSpaceship(out: ShortArray) {
        val baseFreqs = doubleArrayOf(72.0, 108.0, 144.5) // 살짝 디튠된 배음 관계
        val amp = (0.3f + throttle * 0.5f) * masterVolume

        for (i in out.indices) {
            val t = 1.0 / SAMPLE_RATE
            lfoPhase += 2.0 * PI * 0.15 * t // 0.15Hz 느린 LFO
            if (lfoPhase > 2.0 * PI) lfoPhase -= 2.0 * PI
            val lfo = sin(lfoPhase) * 0.5 + 0.5 // 0..1

            var sample = 0.0
            for (h in baseFreqs.indices) {
                val vibrato = 1.0 + 0.004 * sin(lfoPhase * (1.0 + h * 0.3))
                spaceshipPhase[h] += 2.0 * PI * baseFreqs[h] * vibrato * t
                if (spaceshipPhase[h] > 2.0 * PI) spaceshipPhase[h] -= 2.0 * PI
                sample += sin(spaceshipPhase[h]) / baseFreqs.size
            }

            // 하이 프리퀀시 쉬머 (필터링 노이즈)
            val whiteNoise = Random.nextFloat() * 2f - 1f
            noiseFilterState += 0.02f * (whiteNoise - noiseFilterState)
            val shimmer = noiseFilterState * 0.4

            var mixed = (sample * (0.6 + 0.3 * lfo) + shimmer * 0.25) * amp
            mixed = mixed.coerceIn(-1.0, 1.0)
            out[i] = (mixed * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
