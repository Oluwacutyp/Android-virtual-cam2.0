package com.vcamstudio.engine.transport

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r53: the pure transport contracts — route matrix, caps line, seqlock. */
class TransportTest {

    private fun probes(
        api: Int = 34,
        root: String = "none",
        xposed: Int = 0,
        vdm: String = "null",
        injectPerm: String = "denied",
        selinux: String = "Enforcing",
        videoDevs: Int = 0,
    ) = TransportCapabilities.Probes(api, root, xposed, vdm, injectPerm, selinux, videoDevs)

    // ---- route matrix ----------------------------------------------------

    @Test
    fun `no root and no vdm resolves to none with reasons`() {
        val (route, why) = TransportCapabilities.resolveRoute(probes())
        assertEquals(TransportRoute.NONE, route)
        assertTrue(why.contains("api<34"))
        assertTrue(why.contains("vdm=null"))
        assertTrue(why.contains("inject_perm=denied"))
        assertTrue(why.contains("no root"))
    }

    @Test
    fun `root is preferred over virtualdevice`() {
        val (route, why) = TransportCapabilities.resolveRoute(
            probes(root = "magisk", vdm = "non-null", injectPerm = "granted"),
        )
        assertEquals(TransportRoute.ROOT_HOOK, route)
        assertTrue(why.contains("root=magisk"))
    }

    @Test
    fun `virtualdevice needs all three gates`() {
        assertEquals(
            TransportRoute.VIRTUALDEVICE,
            TransportCapabilities.resolveRoute(
                probes(vdm = "non-null", injectPerm = "granted"),
            ).first,
        )
        assertEquals(
            TransportRoute.NONE,
            TransportCapabilities.resolveRoute(
                probes(vdm = "non-null", injectPerm = "denied"),
            ).first,
        )
        assertEquals(
            TransportRoute.NONE,
            TransportCapabilities.resolveRoute(
                probes(api = 33, vdm = "non-null", injectPerm = "granted"),
            ).first,
        )
    }

    @Test
    fun `debug override wins and says so`() {
        val (route, why) = TransportCapabilities.resolveRoute(
            probes(), debugOverride = TransportRoute.VIRTUALDEVICE,
        )
        assertEquals(TransportRoute.VIRTUALDEVICE, route)
        assertEquals("debug override", why)
    }

    @Test
    fun `su classification`() {
        assertEquals("magisk", TransportCapabilities.classifyRoot(true, true))
        assertEquals("su", TransportCapabilities.classifyRoot(true, false))
        assertEquals("none", TransportCapabilities.classifyRoot(false, true))
    }

    @Test
    fun `selinux file parse`() {
        assertEquals(
            "Enforcing",
            TransportCapabilities.selinuxFromEnforceFile(createTempFile("e", "t").apply { writeText("1") }),
        )
        assertEquals(
            "Permissive",
            TransportCapabilities.selinuxFromEnforceFile(createTempFile("e", "t").apply { writeText("0") }),
        )
        assertEquals("unknown", TransportCapabilities.selinuxFromEnforceFile(null))
    }

    // ---- caps line format (byte-for-byte contract) ------------------------

    @Test
    fun `caps line matches the mandated format`() {
        val caps = TransportCapabilities.detect(
            probes(api = 35, root = "su", xposed = 1, vdm = "non-null", injectPerm = "granted", videoDevs = 4),
        )
        assertEquals(
            "TRANSPORT_CAPS api=35 root=su xposed=1 vdm=non-null " +
                "inject_perm=granted selinux=Enforcing video_devs=4 " +
                "route=root_hook reason=root=su present; hook applies to Camera1 apps " +
                "with an Xposed framework (xposed=1)",
            caps.capsLine(),
        )
    }

    // ---- header codec / seqlock -------------------------------------------

    private fun header() = ByteBuffer.allocate(HeaderCodec.HEADER_BYTES)
        .order(ByteOrder.nativeOrder())

    @Test
    fun `header roundtrip`() {
        val b = header()
        val meta = FrameMeta(720, 1280, 90, 1234567890123L, Formats.I420)
        HeaderCodec.putHeader(b, meta, frameBytes = 1382400, payloadBytes = 1382400, seq = 8)
        assertEquals(HeaderCodec.MAGIC, HeaderCodec.magic(b))
        assertEquals(meta, HeaderCodec.meta(b))
        assertEquals(1382400, HeaderCodec.payloadBytes(b))
        assertEquals(8, HeaderCodec.seq(b))
    }

    @Test
    fun `seqlock stability is even seq`() {
        assertTrue(HeaderCodec.stableBefore(0))
        assertTrue(HeaderCodec.stableBefore(42))
        assertFalse(HeaderCodec.stableBefore(1))
        assertFalse(HeaderCodec.stableBefore(43))
    }
}
