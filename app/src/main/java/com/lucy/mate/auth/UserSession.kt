package com.lucy.mate.auth

/**
 * Soltini User Authentication Session Model
 * Compatible with Soltini Desktop App and Neon PostgreSQL Database
 */
data class UserSession(
    val email: String,
    val name: String,
    val avatarUrl: String? = null,
    val tier: String = "free",
    val status: String = "active", // "active", "revoked"
    val token: String,
    val expiresAt: Long = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000,
    val isTrial: Boolean = false,
    val daysRemaining: Int = 0,
    val periodEnd: String? = null
) {
    /**
     * Determines whether the user has active Pro access on Android Mobile.
     * Evaluates to true ONLY if tier is 'mobile_pro', 'bundle_pro', or legacy 'pro' (and not revoked).
     * Single Windows PC Pro ('pc_pro') is strictly excluded from Android Pro entitlements.
     */
    val isPro: Boolean
        get() = status == "active" && (
            tier.equals("mobile_pro", ignoreCase = true) ||
            tier.equals("bundle_pro", ignoreCase = true) ||
            tier.equals("pro", ignoreCase = true)
        )

    val isFree: Boolean
        get() = !isPro

    /**
     * True if the user purchased Windows PC Pro, but is accessing via Android (Free mode on mobile).
     */
    val isPcOnlyPro: Boolean
        get() = status == "active" && tier.equals("pc_pro", ignoreCase = true)

    val isBundlePro: Boolean
        get() = status == "active" && tier.equals("bundle_pro", ignoreCase = true)

    val isMobilePro: Boolean
        get() = status == "active" && tier.equals("mobile_pro", ignoreCase = true)

    val planDisplayName: String
        get() = when (tier.lowercase()) {
            "bundle_pro" -> if (isTrial) "LUCY Ultimate Bundle (10-Day Trial)" else "LUCY Ultimate Bundle"
            "mobile_pro" -> "LUCY Android Mobile Pro"
            "pc_pro" -> "LUCY Windows PC Pro (Free on Mobile)"
            "pro" -> "LUCY Pro (All-Access)"
            else -> "LUCY Free Companion"
        }

    val isRevoked: Boolean
        get() = status == "revoked"

    val isExpired: Boolean
        get() = System.currentTimeMillis() > expiresAt
}
