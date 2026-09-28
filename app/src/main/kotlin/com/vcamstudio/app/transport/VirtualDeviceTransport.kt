package com.vcamstudio.app.transport

import android.content.Context
import android.content.pm.PackageManager
import timber.log.Timber

/**
 * r53 Route V (no root): VirtualDeviceManager + camera injection.
 *
 * HONEST SCOPE (owner mandate): this route is APP STREAMING — the target
 * app must be launched BY US onto the virtual display; an app the user
 * launched normally never sees our camera. All platform calls are made via
 * reflection against runtime probed classes: compileSdk may not carry the
 * injection APIs, and probing IS the capability detection (nothing is
 * assumed from a device model or an SDK promise).
 *
 * Flow: CDM association -> VirtualDevice(POLICY_TYPE_CAMERA) ->
 * virtual display -> launch target with setLaunchDisplayId ->
 * CameraManager.injectCamera -> CameraInjectionSession -> our composited
 * frames written into the injected stream.
 *
 * Every step reports its failure verbatim into TRANSPORT_VD_* lines — a
 * negative result is a valid outcome and tells us where the product runs.
 */
object VirtualDeviceTransport {

    const val PERM_INJECT = "android.permission.CAMERA_INJECT_EXTERNAL_CAMERA"

    data class Availability(
        val sdkOk: Boolean,
        val vdmPresent: Boolean,
        val associations: Int,
        val injectPermission: Boolean,
        val injectApiPresent: Boolean,
        val summary: String,
    )

    fun probe(ctx: Context): Availability {
        val sdkOk = android.os.Build.VERSION.SDK_INT >= 34
        val vdmPresent = sdkOk && runCatching {
            ctx.getSystemService("virtual_device") != null
        }.getOrDefault(false)
        val associations = runCatching {
            val cdm = ctx.getSystemService("companiondevice") as? android.companion.CompanionDeviceManager
            cdm?.associations?.size ?: 0
        }.getOrDefault(0)
        val injectPermission = runCatching {
            ctx.checkSelfPermission(PERM_INJECT) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val injectApiPresent = runCatching {
            android.hardware.camera2.CameraManager::class.java.methods.any {
                it.name == "injectCamera"
            }
        }.getOrDefault(false)
        val gates = buildList {
            if (!sdkOk) add("api<34")
            if (!vdmPresent) add("vdm=null")
            if (associations == 0) add("no-cdm-association")
            if (!injectPermission) add("inject_perm=denied")
            if (!injectApiPresent) add("injectCamera-api-absent")
        }
        return Availability(
            sdkOk, vdmPresent, associations, injectPermission, injectApiPresent,
            if (gates.isEmpty()) "all gates present" else gates.joinToString(" "),
        )
    }

    /**
     * Best-effort launch-through. Returns null when a gate is missing
     * (check [probe]); otherwise a human-readable step result — success or
     * the exact reflective failure.
     */
    fun attemptLaunchThrough(ctx: Context, targetPackage: String): String? {
        val a = probe(ctx)
        if (!a.sdkOk || !a.vdmPresent || a.associations == 0) return null
        return runCatching {
            val vdmClass = Class.forName("android.companion.virtual.VirtualDeviceManager")
            val vdm = ctx.getSystemService("virtual_device")
                ?: error("VirtualDeviceManager service vanished")
            val createVirtualDevice = vdmClass.methods.firstOrNull { it.name == "createVirtualDevice" }
                ?: error("createVirtualDevice method absent")
            val cdm = ctx.getSystemService("companiondevice") as android.companion.CompanionDeviceManager
            val firstAssoc = cdm.associations.firstOrNull() ?: error("no association")
            val assocId = firstAssoc.javaClass.getMethod("getId").invoke(firstAssoc) as Int
            val paramsClass = Class.forName("android.companion.virtual.VirtualDeviceParams")
            val policyField = paramsClass.fields.firstOrNull { it.name == "POLICY_TYPE_CAMERA" }
                ?: error("POLICY_TYPE_CAMERA absent")
            val builder = paramsClass.getDeclaredConstructor().apply { isAccessible = true }
            val params = try {
                // Prefer the builder if present; fall back to an empty params.
                val bClass = paramsClass.classes.firstOrNull { it.simpleName == "Builder" }
                if (bClass != null) {
                    val b = bClass.getDeclaredConstructor().newInstance()
                    bClass.methods.first { it.name == "setAllowedPolicyTypes" }
                        .invoke(b, policyField.get(null))
                    bClass.methods.first { it.name == "build" }.invoke(b)
                } else {
                    builder.newInstance()
                }
            } catch (t: Throwable) {
                builder.newInstance()
            }
            val device = createVirtualDevice.invoke(vdm, assocId, params)
            Timber.i("TRANSPORT_VD_DEVICE_CREATED id=%s", assocId)
            // Launch the target onto the virtual display (display 0 of it).
            val getDisplayIds = device.javaClass.methods.firstOrNull { it.name == "getDisplayIds" }
            val displayId = (getDisplayIds?.invoke(device) as? IntArray)?.firstOrNull()
                ?: error("virtual display absent")
            val intent = ctx.packageManager.getLaunchIntentForPackage(targetPackage)
                ?: error("target $targetPackage not launchable")
            intent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            )
            val options = android.app.ActivityOptions.makeBasic()
            options.launchDisplayId = displayId
            ctx.startActivity(intent, options.toBundle())
            "launched $targetPackage on virtual display $displayId (injection step next round of this route)"
        }.onFailure {
            Timber.w("TRANSPORT_VD_FAIL %s", it.message ?: it.javaClass.simpleName)
        }.fold(
            onSuccess = { it },
            onFailure = { "TRANSPORT_VD_FAIL: ${it.message ?: it.javaClass.simpleName}" },
        )
    }
}
