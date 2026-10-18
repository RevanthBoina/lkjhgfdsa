package com.aniob.core.perception

/**
 * Chooses how an action reaches its target in the compact observe conditions.
 *
 * A compact observation keeps a live node handle per prompt id, so a tap can be performed on the
 * node itself instead of replaying a coordinate gesture. That is both faster (no gesture
 * dispatch and no window-manager round trip) and more reliable on dense layouts where the centre
 * of two overlapping bounds can land on the wrong element.
 *
 * The coordinate recorded at capture time stays the fallback for everything else: no retained
 * handle, a stale handle after a recycle, a label that is not itself clickable, or a disabled
 * control.
 */
object ObserveDispatchPolicy {

    /** True when the retained node should receive the action before any gesture is dispatched. */
    fun preferNodeAction(nodeId: Int?, capable: Boolean, enabled: Boolean): Boolean =
        nodeId != null && nodeId >= 0 && capable && enabled
}
