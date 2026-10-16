package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** The music renderer trims its track's playback rate from the queued level (#610). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioClockTrimTest.RateTrack::class])
class AudioClockTrimTest {
    private val reports = mutableListOf<String>()
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private lateinit var renderer: Any
    private lateinit var track: AudioTrack

    @Before fun setUp() {
        MediaClockTrim.learnedTrim = 0.0
        RateTrack.rates.clear()
        RateTrack.writes.clear()
        RateTrack.result = AudioTrack.SUCCESS
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder()
                .setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(96_000)
            .build()
        renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false),
                0, 500, { message: String -> reports.add(message) }, false, null, false, true)
        set("track", track)
        set("mappedChannel", AudioChannel.MEDIA)
        set("playbackStarted", true)
        set("startThresholdBytes", 57_600)
        set("bytesPerSecond", 48_000 * 4)
        // 300 ms of music is the target; createTrack sets this up for music only.
        set("clockTrim", MediaClockTrim(48_000, 14_400))
    }

    @After fun tearDown() {
        track.release()
        MediaClockTrim.learnedTrim = 0.0
    }

    private fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
    private fun get(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)

    private fun write(bytes: Int) = type.getDeclaredMethod("writePcm", ByteArray::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        .invoke(renderer, ByteArray(bytes), 0, bytes)

    @Test fun aLowLevelSlowsTheTrackAndTheStatsShowTheRate() {
        // 150 ms queued against a 300 ms target.
        write(28_800)
        assertEquals(listOf(47_280), RateTrack.rates)
        type.getDeclaredMethod("logStatsIfDue", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, true)
        assertTrue(reports.toString(), reports.any { it.startsWith("audio stats") && it.contains(" playbackRate=47280 ended=true") })
    }

    @Test fun aTrackThatRefusesTheRateKeepsPlayingWithoutTheTrim() {
        RateTrack.result = AudioTrack.ERROR_INVALID_OPERATION
        write(28_800)
        assertNull(get("clockTrim"))
        assertTrue(reports.toString(), reports.contains("Audio: clock trim off audioType=media rate=47280 result=-3"))
        write(28_800)
        assertEquals(listOf(47_280), RateTrack.rates)
    }

    @Test fun nothingIsTrimmedBeforeMusicPlays() {
        set("playbackStarted", false)
        write(2_048)
        assertTrue(RateTrack.rates.isEmpty())
    }

    @Test fun optedOutMusicDoesNotChangeRateOrInheritLearnedPitchEvenWhenBufferLevelChanges() {
        MediaClockTrim.learnedTrim = -0.015
        val sink = AndroidMediaSink(mediaClockTrimEnabled = false, onAudioDiagnostic = reports::add)
        track.release()
        try {
            // Exercise sink -> renderer -> track creation, the same seam used by the C11 host.
            val format = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 96, "media")
            renderer = requireNotNull(AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer",
                AudioStreamId::class.java, AudioFormat::class.java).apply { isAccessible = true }
                .invoke(sink, AudioStreamId(102, "media"), format))
            type.getDeclaredMethod("createTrack").apply { isAccessible = true }.invoke(renderer)
            track = get("track") as AudioTrack
            assertNull(get("clockTrim"))
            assertTrue(reports.toString(), reports.any { it.contains("Audio: ready") && it.contains("clockTrim=off") })
            set("playbackStarted", true)
            set("fadeApplied", true)
            val writePcm = type.getDeclaredMethod("writePcm", ByteArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            // Low and then high queued levels used to cause the controller to bend the pitch.
            for (size in listOf(4_096, 28_800, 76_800)) {
                val pcm = ByteArray(size) { (it % 127).toByte() }
                val start = RateTrack.writes.size
                writePcm.invoke(renderer, pcm, 0, pcm.size)
                assertEquals(pcm.toList(), RateTrack.writes.drop(start))
            }
            assertTrue("fixed-rate music must never invoke setPlaybackRate", RateTrack.rates.isEmpty())
        } finally { sink.close() }
    }

    @Implements(AudioTrack::class)
    class RateTrack {
        @Implementation fun getBufferSizeInFrames() = 33_600
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun play() = Unit
        @Implementation fun pause() = Unit
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            writes.addAll(bytes.copyOfRange(offset, offset + size).toList())
            return size
        }
        @Implementation fun setPlaybackRate(rate: Int): Int {
            rates += rate
            return result
        }

        companion object {
            @JvmStatic
            @Implementation
            fun getMinBufferSize(sampleRate: Int, channelConfig: Int, encoding: Int) = 4_096
            val rates = mutableListOf<Int>()
            val writes = mutableListOf<Byte>()
            var result = AudioTrack.SUCCESS
        }
    }
}
