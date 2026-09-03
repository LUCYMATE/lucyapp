package com.soltini.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Soltini Pinky-White Luxury Light Palette ──────────────────────────────────
val PinkyWhiteBackground = Color(0xFFFFF5F8) // Soft luminous pearl pink-white
val PinkyWhiteBackgroundEnd = Color(0xFFFFF0F5) // Subtle gradient finish (Lavender Blush)
val PearlWhiteSurface = Color(0xFFFFFFFF)    // Pure white for cards & elevated panels
val BlushSurfaceElevated = Color(0xFFFDF2F8) // Pink 50 subtle elevation
val PinkyBorder = Color(0xFFFCE7F3)          // Pink 100 soft card border
val PinkyBorderSubtle = Color(0xFFFDF2F8)    // Subtle outline
val PinkyBorderFocused = Color(0xFFF472B6)   // Pink 400 focused accent

// Accents
val RosePink = Color(0xFFF43F8E)            // Primary Radiant Rose Pink
val RosePinkDeep = Color(0xFFE11D48)        // Rose 600
val RosePinkLight = Color(0xFFFDF2F8)       // Pink 50
val SoftBlush = Color(0xFFFB7185)           // Rose 400 Coral Blush
val SoftViolet = Color(0xFF8B5CF6)          // Soft Violet 500
val VioletLight = Color(0xFFF5F3FF)         // Violet 50

// Status & Alerts
val EmeraldLive = Color(0xFF10B981)         // Emerald 500 Live Status
val AmberConnecting = Color(0xFFF59E0B)     // Amber 500 Connecting / Warning
val RubyShield = Color(0xFFE11D48)          // Rose/Ruby 600 Banking Guard Shield
val RedError = Color(0xFFEF4444)            // Red 500

// High-Legibility Typography
val TextPrimary = Color(0xFF1E1926)         // Rich Deep Plum Charcoal
val TextSecondary = Color(0xFF6B7280)       // Slate 500
val TextMuted = Color(0xFF9CA3AF)           // Slate 400

// ── Aliases & Backward Compatibility ──────────────────────────────────────────
val AccentPink = RosePink
val AccentTeal = EmeraldLive
val AccentBlue = Color(0xFF3B82F6)
val AccentPurple = SoftViolet
val AccentIndigo = Color(0xFF6366F1)
val GreenLive = EmeraldLive

// Light theme aliases
val LightBackground = PinkyWhiteBackground
val LightSurface = PearlWhiteSurface
val LightSurfaceVariant = BlushSurfaceElevated
val LightBorder = PinkyBorder
val LightTextPrimary = TextPrimary
val LightTextSecondary = TextSecondary

// Dark theme aliases mapped to crisp light tones to eliminate dark theme completely
val DarkBackground = PinkyWhiteBackground
val DarkSurface = PearlWhiteSurface
val DarkSurfaceVariant = BlushSurfaceElevated
val DarkBorder = PinkyBorder
val DarkTextPrimary = TextPrimary
val DarkTextSecondary = TextSecondary

