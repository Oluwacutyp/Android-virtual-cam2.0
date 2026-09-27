// Round 49: control channel main <-> :ai. Payloads ride the AiRing shared
// memory (binder carries only slot indices and scalars).
package com.vcamstudio.app.ai;

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
