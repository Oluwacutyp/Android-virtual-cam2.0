// Round 49: control channel main <-> :ai. Payloads ride the AiRing shared
// memory (binder carries only slot indices and scalars).
package com.vcamstudio.app.ai;

// The aidl tool in build-tools 34 resolves cross-file interface types ONLY
// via explicit imports — same-package auto-import is newer-aidl behavior
// (classic AIDL samples import their callback interface from the same
// package; r49 CI run 36301385974 failed compileDebugAidl without this).
import com.vcamstudio.app.ai.IAiDetectorCallback;

interface IAiDetector {
    oneway void registerCallback(IAiDetectorCallback cb);
    oneway void unregisterCallback(IAiDetectorCallback cb);

    /** Child opens both rings; returns false when it could not. */
    boolean attachRings(String frameRingPath, int frameSlotBytes, int frameSlots,
                        String boxRingPath, int boxSlotBytes, int boxSlots);

    oneway void setModel(String path);

    /** Frame payload already in frame ring slot [slot]; child copies + releases. */
    oneway void submitFrame(int slot, int width, int height, int rotationDeg, long frameId);
}
