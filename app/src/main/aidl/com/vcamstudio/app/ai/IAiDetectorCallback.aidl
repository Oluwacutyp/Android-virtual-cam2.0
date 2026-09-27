// Round 49: child -> main results. Both oneway: the child must never block
// on the main process (and vice versa).
package com.vcamstudio.app.ai;

oneway interface IAiDetectorCallback {
    void onResult(long frameId, int boxSlot, boolean hasFace, long preprocessMs, long inferMs);
    void onState(int state, String detail);   // 0 idle, 1 model-missing, 2 session-failed, 3 running
}
