package com.audx.android

import org.junit.Test
import org.junit.Assert.*

class AudxConfigTest {

    @Test
    fun `config with default values`() {
        val config = AudxConfig()
        assertEquals(Audx.FRAME_RATE, config.inputRate)
        assertEquals(Audx.AUDX_RESAMPLER_QUALITY_DEFAULT, config.resampleQuality)
    }

    @Test
    fun `config validation accepts valid resample quality`() {
        // Should not throw
        AudxConfig(resampleQuality = Audx.AUDX_RESAMPLER_QUALITY_MIN)
        AudxConfig(resampleQuality = Audx.AUDX_RESAMPLER_QUALITY_DEFAULT)
        AudxConfig(resampleQuality = Audx.AUDX_RESAMPLER_QUALITY_MAX)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config validation rejects quality too low`() {
        AudxConfig(resampleQuality = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config validation rejects quality too high`() {
        AudxConfig(resampleQuality = 11)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config validation rejects negative input rate`() {
        AudxConfig(inputRate = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config validation rejects zero input rate`() {
        AudxConfig(inputRate = 0)
    }

    @Test
    fun `config validation accepts positive input rate`() {
        // Should not throw
        AudxConfig(inputRate = 8000)
        AudxConfig(inputRate = 16000)
        AudxConfig(inputRate = 48000)
    }
}

class AudxBuilderTest {

    @Test
    fun `builder creates instance with default config`() {
        val audx = Audx.Builder().build()
        assertNotNull(audx)
        assertFalse(audx.isClosed())
        audx.close()
        assertTrue(audx.isClosed())
    }

    @Test
    fun `builder applies custom config`() {
        val audx = Audx.Builder()
            .config {
                inputRate(16000)
                resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_VOIP)
            }
            .build()

        assertNotNull(audx)
        assertFalse(audx.isClosed())
        audx.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `builder with invalid config throws on build`() {
        Audx.Builder()
            .config {
                resampleQuality(15)  // Invalid
            }
            .build()
    }
}

class AudxConstantsTest {

    @Test
    fun `frame rate is 48kHz`() {
        assertEquals(48000, Audx.FRAME_RATE)
    }

    @Test
    fun `frame size is 480 samples`() {
        assertEquals(480, Audx.FRAME_SIZE)
    }

    @Test
    fun `resample quality constants are valid`() {
        assertEquals(0, Audx.AUDX_RESAMPLER_QUALITY_MIN)
        assertEquals(10, Audx.AUDX_RESAMPLER_QUALITY_MAX)
        assertEquals(4, Audx.AUDX_RESAMPLER_QUALITY_DEFAULT)
        assertEquals(3, Audx.AUDX_RESAMPLER_QUALITY_VOIP)
    }

    @Test
    fun `resample quality constants are in valid range`() {
        assertTrue(Audx.AUDX_RESAMPLER_QUALITY_MIN >= 0)
        assertTrue(Audx.AUDX_RESAMPLER_QUALITY_MAX <= 10)
        assertTrue(Audx.AUDX_RESAMPLER_QUALITY_DEFAULT in Audx.AUDX_RESAMPLER_QUALITY_MIN..Audx.AUDX_RESAMPLER_QUALITY_MAX)
        assertTrue(Audx.AUDX_RESAMPLER_QUALITY_VOIP in Audx.AUDX_RESAMPLER_QUALITY_MIN..Audx.AUDX_RESAMPLER_QUALITY_MAX)
    }
}

class AudxExceptionTest {

    @Test
    fun `AudxProcessingException has correct hierarchy`() {
        val exception = AudxProcessingException("test message")
        assertTrue(exception is AudxException)
        assertTrue(exception is Exception)
        assertEquals("test message", exception.message)
    }

    @Test
    fun `AudxInitializationException has correct hierarchy`() {
        val exception = AudxInitializationException("test message")
        assertTrue(exception is AudxException)
        assertTrue(exception is Exception)
        assertEquals("test message", exception.message)
    }

    @Test
    fun `exceptions support cause`() {
        val cause = RuntimeException("cause")
        val exception = AudxProcessingException("message", cause)
        assertEquals(cause, exception.cause)
    }
}
