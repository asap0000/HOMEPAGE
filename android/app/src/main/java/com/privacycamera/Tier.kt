package com.privacycamera

/**
 * Single source of truth for product-tier feature gating.
 *
 * Backed by [BuildConfig.IS_PRO], which is set per product flavor (lite/pro) in
 * app/build.gradle.kts. Gate Pro-only behaviour on [isPro] rather than referencing
 * BuildConfig directly throughout the codebase, so the tier boundary stays in one place.
 *
 * Tier capabilities (enforced incrementally as features land):
 *   Lite -> capped local storage, encrypted one-way export, no PII masking
 *   Pro  -> unlimited storage, cumulative import, PII masking, advanced editing
 */
object Tier {
    val isPro: Boolean get() = BuildConfig.IS_PRO
    val isLite: Boolean get() = !BuildConfig.IS_PRO

    /** Maximum photos Lite keeps on the device. Reached by deleting to make room. */
    const val LITE_SAVE_LIMIT = 30

    /**
     * Number of photos this tier may store, or null when unlimited (Pro).
     * Lite is intentionally capped to nudge upgrades; the cap is worked around by
     * deleting/swapping photos (and ferrying batches out via encrypted export).
     */
    val saveLimit: Int? get() = if (isPro) null else LITE_SAVE_LIMIT
}
