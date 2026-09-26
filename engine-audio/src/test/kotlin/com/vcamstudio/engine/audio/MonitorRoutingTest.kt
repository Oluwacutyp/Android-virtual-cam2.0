package com.vcamstudio.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 44 (r33 FIX B): the MONITOR mix must never carry the MIC bus
 * unless the DEV "monitor mic" toggle is on; the RECORD mix always does.
 * This is the executable proof behind "mic doesn't echo with monitor on".
 */
class MonitorRoutingTest {

    private fun constPcm(frames: Int, amplitude: Short): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in out.indices) out[i] = amplitude
        return out
    }

    /** Max absolute sample (manual loop — no stdlib overload resolution). */
    private fun peak(a: ShortArray): Int {
        var m = 0
        for (v in a) {
            val av = if (v < 0) -v else v
            if (av > m) m = av
        }
        return m
    }

    @Test
    fun `monitor mix excludes mic by default`() {
        val mixer = AudioMixer()
        val frames = AudioMixer.FRAME_FRAMES
        // MIC loud, MEDIA quiet — any mic leak into the monitor is unmistakable.
        mixer.offerPcm(AudioBusId.MIC, constPcm(frames, 8000), channels = 2)
        mixer.offerPcm(AudioBusId.MEDIA, constPcm(frames, 1000), channels = 2)

        val rec = ShortArray(frames * 2)
        val mon = ShortArray(frames * 2)
        assertEquals(frames, mixer.readInto(rec, mon))

        val maxMon = peak(mon)
        val maxRec = peak(rec)
        // Monitor: MEDIA only (~1000 * gain 1 * 32767/32767).
        assertTrue("monitor must not carry mic: maxMon=$maxMon", maxMon in 900..1200)
        // Record: MIC + MEDIA (~9000).
        assertTrue("record must carry both: maxRec=$maxRec", maxRec in 8500..9500)
    }

    @Test
    fun `dev toggle routes mic into monitor`() {
        val mixer = AudioMixer()
        val frames = AudioMixer.FRAME_FRAMES
        mixer.setMonitorMic(true)
        mixer.offerPcm(AudioBusId.MIC, constPcm(frames, 8000), channels = 2)
        mixer.offerPcm(AudioBusId.MEDIA, constPcm(frames, 1000), channels = 2)

        val rec = ShortArray(frames * 2)
        val mon = ShortArray(frames * 2)
        assertEquals(frames, mixer.readInto(rec, mon))

        val maxMon = peak(mon)
        assertTrue("monitor must carry mic when enabled: maxMon=$maxMon", maxMon in 8500..9500)
        assertTrue(mixer.monitorsMic())
    }

    @Test
    fun `tts stays monitored`() {
        val mixer = AudioMixer()
        val frames = AudioMixer.FRAME_FRAMES
        mixer.offerPcm(AudioBusId.TTS, constPcm(frames, 2000), channels = 2)

        val rec = ShortArray(frames * 2)
        val mon = ShortArray(frames * 2)
        mixer.readInto(rec, mon)
        val maxMon = peak(mon)
        assertTrue("tts must stay monitored: maxMon=$maxMon", maxMon in 1900..2100)
    }

    @Test
    fun `record path reads every bus exactly once`() {
        val mixer = AudioMixer()
        val frames = AudioMixer.FRAME_FRAMES
        mixer.offerPcm(AudioBusId.MIC, constPcm(frames, 4000), channels = 2)
        val rec = ShortArray(frames * 2)
        val mon = ShortArray(frames * 2)
        mixer.readInto(rec, mon)
        // Second read: ring drained -> silence (pop-once, no double counting).
        mixer.readInto(rec, mon)
        assertEquals(0, peak(mon))
    }
}
