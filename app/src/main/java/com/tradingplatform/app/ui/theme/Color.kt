package com.tradingplatform.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── Indigo (primary) ─────────────────────────────────────────────────────────
val Indigo400 = Color(0xFF818CF8)
val Indigo600 = Color(0xFF4F46E5)
val Indigo800 = Color(0xFF3730A3)
val Indigo900 = Color(0xFF312E81)
val Indigo950 = Color(0xFF1E1B4B)
val Indigo100 = Color(0xFFE0E7FF)

// ── Slate ─────────────────────────────────────────────────────────────────────
val Slate50  = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Slate200 = Color(0xFFE2E8F0)
val Slate300 = Color(0xFFCBD5E1)
val Slate400 = Color(0xFF94A3B8)
val Slate500 = Color(0xFF64748B)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)
val Slate950 = Color(0xFF020617)

// ── Emerald (success / pnl positive) ─────────────────────────────────────────
val Emerald100 = Color(0xFFD1FAE5)
val Emerald200 = Color(0xFFA7F3D0)
val Emerald400 = Color(0xFF34D399)
val Emerald600 = Color(0xFF059669)
val Emerald900 = Color(0xFF064E3B)
val Emerald950 = Color(0xFF022C22)

// ── Rose (error / pnl negative) ──────────────────────────────────────────────
val Rose100 = Color(0xFFFFE4E6)
val Rose400 = Color(0xFFFB7185)
val Rose600 = Color(0xFFE11D48)
val Rose800 = Color(0xFF9F1239)
val Rose950 = Color(0xFF4C0519)

// ── Amber (warning) ───────────────────────────────────────────────────────────
val Amber100 = Color(0xFFFEF3C7)
val Amber400 = Color(0xFFFBBF24)
val Amber600 = Color(0xFFD97706)
val Amber900 = Color(0xFF78350F)
val Amber950 = Color(0xFF1C0A00)

// ── Sky (info) ────────────────────────────────────────────────────────────────
val Sky100  = Color(0xFFE0F2FE)
val Sky400  = Color(0xFF38BDF8)
val Sky600  = Color(0xFF0284C7)
val Sky900  = Color(0xFF0C4A6E)
val Sky950  = Color(0xFF082F49)

val White = Color(0xFFFFFFFF)
val Black = Color(0xFF000000)

// ── Dark Color Scheme (principal — app de trading sombre) ────────────────────
// Valeurs exactes depuis docs/design-system.md (tokens sémantiques → Material 3)
val DarkColorScheme = darkColorScheme(
    primary = Indigo400,                   // #818cf8 (indigo-400)
    onPrimary = Indigo950,                  // #1e1b4b (indigo-950)
    primaryContainer = Indigo800,          // #3730a3 (indigo-800)
    onPrimaryContainer = Indigo100,        // #e0e7ff (indigo-100)
    secondary = Slate400,                  // #94a3b8 (slate-400)
    onSecondary = Slate900,               // #0f172a (slate-900)
    secondaryContainer = Slate800,        // #1e293b (slate-800)
    onSecondaryContainer = Slate200,      // #e2e8f0 (slate-200)
    error = Rose400,                       // #fb7185 (rose-400)
    onError = Rose950,                    // #4c0519 (rose-950)
    errorContainer = Rose800,            // #9f1239 (rose-800)
    onErrorContainer = Rose100,          // #ffe4e6 (rose-100)
    background = Slate950,               // #020617 (slate-950)
    onBackground = Slate100,             // #f1f5f9 (slate-100)
    surface = Slate900,                  // #0f172a (slate-900)
    onSurface = Slate100,                // #f1f5f9 (slate-100)
    surfaceVariant = Slate800,           // #1e293b (slate-800)
    onSurfaceVariant = Slate400,         // #94a3b8 (slate-400)
    outline = Slate700,                  // #334155 (slate-700)
    outlineVariant = Slate800,           // #1e293b (slate-800)
    inverseSurface = Slate100,           // #f1f5f9 (slate-100)
    inverseOnSurface = Slate900,         // #0f172a (slate-900)
    inversePrimary = Indigo600,          // #4f46e5 (indigo-600)
    scrim = Black,                       // #000000
)

// ── Light Color Scheme ────────────────────────────────────────────────────────
// Valeurs exactes depuis docs/design-system.md (tokens sémantiques → Material 3)
val LightColorScheme = lightColorScheme(
    primary = Indigo600,                  // #4f46e5 (indigo-600)
    onPrimary = White,                    // #ffffff
    primaryContainer = Indigo100,        // #e0e7ff (indigo-100)
    onPrimaryContainer = Indigo900,      // #312e81 (indigo-900)
    secondary = Slate600,                // #475569 (slate-600)
    onSecondary = White,                 // #ffffff
    secondaryContainer = Slate100,       // #f1f5f9 (slate-100)
    onSecondaryContainer = Slate900,     // #0f172a (slate-900)
    error = Rose600,                     // #e11d48 (rose-600)
    onError = White,                     // #ffffff
    errorContainer = Rose100,           // #ffe4e6 (rose-100)
    onErrorContainer = Rose800,         // #881337 (rose-800)
    background = Slate50,               // #f8fafc (slate-50)
    onBackground = Slate900,            // #0f172a (slate-900)
    surface = White,                    // #ffffff
    onSurface = Slate900,               // #0f172a (slate-900)
    surfaceVariant = Slate100,          // #f1f5f9 (slate-100)
    onSurfaceVariant = Slate600,        // #475569 (slate-600)
    outline = Slate300,                 // #cbd5e1 (slate-300)
    outlineVariant = Slate200,          // #e2e8f0 (slate-200)
    inverseSurface = Slate800,          // #1e293b (slate-800)
    inverseOnSurface = Slate50,         // #f8fafc (slate-50)
    inversePrimary = Indigo400,         // #818cf8 (indigo-400)
    scrim = Slate900,                   // #0f172a (slate-900)
)
