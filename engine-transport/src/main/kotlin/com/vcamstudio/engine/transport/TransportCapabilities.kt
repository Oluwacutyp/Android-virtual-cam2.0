package com.vcamstudio.engine.transport

import java.io.File

/**
 * r53: RUNTIME capability detection. No device model/manufacturer/carrier
 * strings anywhere — every field is a measured runtime fact, and the route
 * decision is a pure function of those facts (so the matrix is unit
 * tested). The detector takes injectable probes; the Android wiring lives
 * app-side, this file stays plain-JVM testable.
 */
object TransportCapabilities {

    data class Probes(
        val api: Int,
        val root: String,
        val xposed: Int,
        val vdm: String,
        val injectPerm: String,
        val selinux: String,
        val videoDevs: Int,
    )

    /**
     * Route resolution (pure):
     *  - root_hook preferred when root != none (broader coverage) — the
     *    hook ALSO needs an Xposed/LSPosed framework, reported via
     *    xposed=<n> but not gated here (the framework presence is only
     *    provable at hook time; the caps line says which gates fired).
     *  - virtualdevice when API >= 34 AND vdm non-null AND inject granted.
     *  - otherwise none, with the reason naming the failed gates.
     */
    fun resolveRoute(p: Probes, debugOverride: TransportRoute? = null): Pair<TransportRoute, String> {
        debugOverride?.let {
            return it to "debug override"
        }
        if (p.root != "none") {
            return TransportRoute.ROOT_HOOK to
                "root=${p.root} present; hook applies to Camera1 apps with an Xposed framework (xposed=${p.xposed})"
        }
        val gates = ArrayList<String>()
        if (p.api < 34) gates.add("api<34")
        if (p.vdm != "non-null") gates.add("vdm=${p.vdm}")
        if (p.injectPerm != "granted") gates.add("inject_perm=${p.injectPerm}")
        if (gates.isEmpty()) {
            return TransportRoute.VIRTUALDEVICE to
                "no root; vdm non-null and inject granted"
        }
        return TransportRoute.NONE to "no root; " + gates.joinToString(" ")
    }

    fun detect(p: Probes, debugOverride: TransportRoute? = null): TransportCaps {
        val (route, reason) = resolveRoute(p, debugOverride)
        return TransportCaps(
            api = p.api,
            root = p.root,
            xposed = p.xposed,
            vdm = p.vdm,
            injectPerm = p.injectPerm,
            selinux = p.selinux,
            videoDevs = p.videoDevs,
            route = route,
            reason = reason,
        )
    }

    // ---- probe helpers (plain JVM; app wraps with timeouts/context) ------

    /** true when the `su` invocation succeeded — caller decides su vs magisk. */
    fun suWorks(exec: (String) -> Int?): Boolean = exec("id") == 0

    fun classifyRoot(suOk: Boolean, magiskDir: Boolean): String = when {
        suOk && magiskDir -> "magisk"
        suOk -> "su"
        else -> "none"
    }

    fun selinuxFromEnforceFile(enforce: File?): String {
        if (enforce == null || !enforce.exists()) return "unknown"
        return runCatching { enforce.readText().trim() }.getOrDefault("")?.let {
            if (it == "1") "Enforcing" else if (it == "0") "Permissive" else "unknown"
        } ?: "unknown"
    }

    fun countVideoDevs(devDir: File?): Int =
        devDir?.listFiles { _, name -> name.startsWith("video") }?.size ?: 0
}
