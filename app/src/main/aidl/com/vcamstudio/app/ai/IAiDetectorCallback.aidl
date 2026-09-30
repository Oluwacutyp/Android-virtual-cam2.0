// Round 49: child -> main results. Both oneway: the child must never block
// on the main process (and vice versa).
package com.vcamstudio.app.ai;

oneway interface IAiDetectorCallback {
    void onResult(long frameId, int boxSlot, boolean hasFace, long preprocessMs, long inferMs);
    void onState(int state, String detail);   // 0 idle, 1 model-missing, 2 session-failed, 3 running

    // r52a ADDITIVE: 5 SCRFD keypoints in upright PIXEL coordinates (order
    // left eye, right eye, nose, left mouth corner, right mouth corner).
    // The boxes ring stays 8x64 - landmarks ride the binder only.
    void onKps(long frameId, in float[] kps, int uprightW, int uprightH);

    // r61 ADDITIVE (live swap): the swapped 128x128 crop as JPEG bytes +
    // its normalized box in upright-frame coordinates. Small payload
    // (tens of KB), oneway like the rest. Drives the preview overlay.
    void onSwapFrame(in byte[] jpeg, int w, int h, in float[] boxNorm);
}
