package com.example.conexion

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class ThemePreset(
    val name: String,
    val subtitle: String,
    val primary: Color,
    val secondary: Color,
    val lightBackground: Color,
    val lightSurface: Color,
    val lightSurfaceVariant: Color,
    val darkBackground: Color,
    val darkSurface: Color,
    val darkSurfaceVariant: Color,
    val gradient: List<Color>
)

data class AvatarDesign(
    val emoji: String,
    val name: String,
    val role: String,
    val accessory: String,
    val bgGradients: List<Color>,
    val glowColor: Color,
    val badgeColor: Color,
    val contentColor: Color = Color.White
)

object AppThemeConfig {
    val THEME_PRESETS = listOf(
        ThemePreset(
            name = "iOS Frost",
            subtitle = "Cristal Blanco & Azul",
            primary = Color(0xFF007AFF),
            secondary = Color(0xFF5856D6),
            lightBackground = Color(0xFFF8FAFC),
            lightSurface = Color(0xFFFFFFFF),
            lightSurfaceVariant = Color(0xFFF1F5F9),
            darkBackground = Color(0xFF0B0F17),
            darkSurface = Color(0xFF161F2E),
            darkSurfaceVariant = Color(0xFF1E293B),
            gradient = listOf(Color(0xFF007AFF), Color(0xFF5856D6))
        ),
        ThemePreset(
            name = "WhatsApp",
            subtitle = "Esmeralda & Verde",
            primary = Color(0xFF00A884),
            secondary = Color(0xFF25D366),
            lightBackground = Color(0xFFF0F4F2),
            lightSurface = Color(0xFFFFFFFF),
            lightSurfaceVariant = Color(0xFFE2EBE5),
            darkBackground = Color(0xFF0B141A),
            darkSurface = Color(0xFF111B21),
            darkSurfaceVariant = Color(0xFF202C33),
            gradient = listOf(Color(0xFF00A884), Color(0xFF25D366))
        ),
        ThemePreset(
            name = "Instagram",
            subtitle = "Sunset Glow",
            primary = Color(0xFFE1306C),
            secondary = Color(0xFFFD1D1D),
            lightBackground = Color(0xFFFFF7F9),
            lightSurface = Color(0xFFFFFFFF),
            lightSurfaceVariant = Color(0xFFFCE7F3),
            darkBackground = Color(0xFF140D18),
            darkSurface = Color(0xFF1F1426),
            darkSurfaceVariant = Color(0xFF2C1E36),
            gradient = listOf(Color(0xFFE1306C), Color(0xFFFD1D1D), Color(0xFFF77737))
        ),
        ThemePreset(
            name = "Spotify",
            subtitle = "Electric Neon Green",
            primary = Color(0xFF1DB954),
            secondary = Color(0xFF1ED760),
            lightBackground = Color(0xFFF3FBF5),
            lightSurface = Color(0xFFFFFFFF),
            lightSurfaceVariant = Color(0xFFE3F7E8),
            darkBackground = Color(0xFF121212),
            darkSurface = Color(0xFF181818),
            darkSurfaceVariant = Color(0xFF242424),
            gradient = listOf(Color(0xFF1DB954), Color(0xFF10B981))
        ),
        ThemePreset(
            name = "iMessage",
            subtitle = "Titanio Espacial",
            primary = Color(0xFF0A84FF),
            secondary = Color(0xFF30D158),
            lightBackground = Color(0xFFF2F4F7),
            lightSurface = Color(0xFFFFFFFF),
            lightSurfaceVariant = Color(0xFFE5E7EB),
            darkBackground = Color(0xFF000000),
            darkSurface = Color(0xFF1C1C1E),
            darkSurfaceVariant = Color(0xFF2C2C2E),
            gradient = listOf(Color(0xFF0A84FF), Color(0xFF30D158))
        ),
        ThemePreset(
            name = "Cyber Aurora",
            subtitle = "Púrpura Vibrante",
            primary = Color(0xFF8B5CF6),
            secondary = Color(0xFFEC4899),
            lightBackground = Color(0xFFFAF5FF),
            lightSurface = Color(0xFFFFFFFF),
            lightSurfaceVariant = Color(0xFFF3E8FF),
            darkBackground = Color(0xFF0F172A),
            darkSurface = Color(0xFF1E293B),
            darkSurfaceVariant = Color(0xFF334155),
            gradient = listOf(Color(0xFF8B5CF6), Color(0xFFEC4899))
        )
    )

    // Modern 3D Persona Avatars with stylish themes, accessories, and glowing palettes
    val AVATAR_DESIGNS = listOf(
        AvatarDesign(
            emoji = "🧑‍💻",
            name = "Alex Cyber",
            role = "Hacker / Dev",
            accessory = "🎧",
            bgGradients = listOf(Color(0xFF007AFF), Color(0xFF5856D6)),
            glowColor = Color(0xFF38BDF8),
            badgeColor = Color(0xFF007AFF)
        ),
        AvatarDesign(
            emoji = "👩‍🎤",
            name = "Luna Neon",
            role = "Pop Vocalist",
            accessory = "⚡",
            bgGradients = listOf(Color(0xFFE1306C), Color(0xFF8B5CF6)),
            glowColor = Color(0xFFF472B6),
            badgeColor = Color(0xFFE1306C)
        ),
        AvatarDesign(
            emoji = "🧑‍🚀",
            name = "Leo Astro",
            role = "Cosmos Pilot",
            accessory = "🚀",
            bgGradients = listOf(Color(0xFF0F172A), Color(0xFF0284C7)),
            glowColor = Color(0xFF06B6D4),
            badgeColor = Color(0xFF0284C7)
        ),
        AvatarDesign(
            emoji = "👩‍💼",
            name = "Sofia Pro",
            role = "Design Lead",
            accessory = "💎",
            bgGradients = listOf(Color(0xFFFF5722), Color(0xFFFF4081)),
            glowColor = Color(0xFFFDA4AF),
            badgeColor = Color(0xFFFF4081)
        ),
        AvatarDesign(
            emoji = "🧑‍🎨",
            name = "Kai Emerald",
            role = "Creative Artist",
            accessory = "🎨",
            bgGradients = listOf(Color(0xFF059669), Color(0xFF10B981)),
            glowColor = Color(0xFF34D399),
            badgeColor = Color(0xFF10B981)
        ),
        AvatarDesign(
            emoji = "👩‍🔬",
            name = "Mia Quantum",
            role = "Techie Sci",
            accessory = "🔬",
            bgGradients = listOf(Color(0xFF7C3AED), Color(0xFFC084FC)),
            glowColor = Color(0xFFA855F7),
            badgeColor = Color(0xFF9333EA)
        ),
        AvatarDesign(
            emoji = "🧑‍🎤",
            name = "Dani Beats",
            role = "Urban Producer",
            accessory = "🎵",
            bgGradients = listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
            glowColor = Color(0xFFFBBF24),
            badgeColor = Color(0xFFF59E0B)
        ),
        AvatarDesign(
            emoji = "🕵️‍♂️",
            name = "Sam Cipher",
            role = "Sec Agent",
            accessory = "🕶️",
            bgGradients = listOf(Color(0xFF1E293B), Color(0xFF475569)),
            glowColor = Color(0xFF94A3B8),
            badgeColor = Color(0xFF334155)
        ),
        AvatarDesign(
            emoji = "🧕",
            name = "Maya Sunset",
            role = "Content Creator",
            accessory = "✨",
            bgGradients = listOf(Color(0xFFD946EF), Color(0xFFF97316)),
            glowColor = Color(0xFFFB923C),
            badgeColor = Color(0xFFD946EF)
        ),
        AvatarDesign(
            emoji = "👨‍🍳",
            name = "Marco Master",
            role = "Flavor Guru",
            accessory = "🔥",
            bgGradients = listOf(Color(0xFFDC2626), Color(0xFFF97316)),
            glowColor = Color(0xFFF87171),
            badgeColor = Color(0xFFDC2626)
        ),
        AvatarDesign(
            emoji = "🧙‍♂️",
            name = "Axel Mystic",
            role = "Algorithm Mage",
            accessory = "🔮",
            bgGradients = listOf(Color(0xFF4338CA), Color(0xFF6366F1)),
            glowColor = Color(0xFF818CF8),
            badgeColor = Color(0xFF4F46E5)
        ),
        AvatarDesign(
            emoji = "👩‍🚀",
            name = "Stella Orbit",
            role = "Space Explorer",
            accessory = "🌟",
            bgGradients = listOf(Color(0xFF0284C7), Color(0xFFEC4899)),
            glowColor = Color(0xFF38BDF8),
            badgeColor = Color(0xFF0284C7)
        )
    )
}

@Composable
fun AppTheme(
    themeIndex: Int,
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    val preset = AppThemeConfig.THEME_PRESETS.getOrElse(themeIndex) { AppThemeConfig.THEME_PRESETS[0] }
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = preset.primary,
            onPrimary = Color.White,
            primaryContainer = preset.primary.copy(alpha = 0.25f),
            onPrimaryContainer = Color.White,
            secondary = preset.secondary,
            onSecondary = Color.White,
            secondaryContainer = preset.secondary.copy(alpha = 0.25f),
            background = preset.darkBackground,
            onBackground = Color(0xFFF1F5F9),
            surface = preset.darkSurface,
            onSurface = Color(0xFFF8FAFC),
            surfaceVariant = preset.darkSurfaceVariant,
            onSurfaceVariant = Color(0xFFCBD5E1),
            outline = Color.White.copy(alpha = 0.12f)
        )
    } else {
        lightColorScheme(
            primary = preset.primary,
            onPrimary = Color.White,
            primaryContainer = preset.primary.copy(alpha = 0.12f),
            onPrimaryContainer = preset.primary,
            secondary = preset.secondary,
            onSecondary = Color.White,
            secondaryContainer = preset.secondary.copy(alpha = 0.12f),
            background = preset.lightBackground,
            onBackground = Color(0xFF0F172A),
            surface = preset.lightSurface,
            onSurface = Color(0xFF0F172A),
            surfaceVariant = preset.lightSurfaceVariant,
            onSurfaceVariant = Color(0xFF475569),
            outline = Color(0xFFE2E8F0)
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * Modern animated Persona Avatar bubble with continuous floating bobbing,
 * glowing shimmer ring, interactive spring wiggles, and accessory badge.
 */
@Composable
fun AvatarBubble(
    avatarIndex: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    isAnimated: Boolean = true,
    isFloating: Boolean = true,
    showAccessoryBadge: Boolean = true,
    showGlowRing: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val design = AppThemeConfig.AVATAR_DESIGNS.getOrElse(avatarIndex % AppThemeConfig.AVATAR_DESIGNS.size) { AppThemeConfig.AVATAR_DESIGNS[0] }
    val coroutineScope = rememberCoroutineScope()

    // Interactive wiggle animation state
    val wiggleRotation = remember { Animatable(0f) }
    val wiggleScale = remember { Animatable(1f) }

    // Subtle continuous idle float & breathing
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_idle")
    val idleOffsetY by infiniteTransition.animateFloat(
        initialValue = -2.5f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800 + (avatarIndex * 150) % 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_y"
    )

    val idleGlowScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_scale"
    )

    val triggerWiggle = {
        coroutineScope.launch {
            wiggleScale.animateTo(1.22f, tween(100, easing = FastOutSlowInEasing))
            wiggleRotation.animateTo(-14f, tween(80))
            wiggleRotation.animateTo(12f, tween(80))
            wiggleRotation.animateTo(-8f, tween(80))
            wiggleRotation.animateTo(6f, tween(80))
            wiggleRotation.animateTo(0f, tween(80))
            wiggleScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    val currentOffsetY = if (isFloating && isAnimated) idleOffsetY.dp else 0.dp

    Box(
        modifier = modifier
            .size(size + (if (showAccessoryBadge && size >= 40.dp) 6.dp else 0.dp))
            .offset(y = currentOffsetY)
            .scale(wiggleScale.value)
            .rotate(wiggleRotation.value)
            .let {
                if (onClick != null) {
                    it.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        triggerWiggle()
                        onClick()
                    }
                } else {
                    it.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        triggerWiggle()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer Glowing Aura / Ring
        if (showGlowRing) {
            Box(
                modifier = Modifier
                    .size(size + 10.dp)
                    .scale(idleGlowScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                design.glowColor.copy(alpha = 0.45f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Main Persona Avatar Container
        Box(
            modifier = Modifier
                .size(size)
                .shadow(
                    elevation = if (isAnimated) 6.dp else 2.dp,
                    shape = CircleShape,
                    ambientColor = design.glowColor.copy(alpha = 0.35f),
                    spotColor = design.glowColor.copy(alpha = 0.5f)
                )
                .clip(CircleShape)
                .background(Brush.linearGradient(design.bgGradients))
                .border(
                    width = (size.value * 0.045f).coerceIn(1.5f, 3.5f).dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.95f),
                            Color.White.copy(alpha = 0.40f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Human Emoji / Persona
            Text(
                text = design.emoji,
                fontSize = (size.value * 0.52f).sp
            )
        }

        // Persona Accessory Mini Badge (e.g. 🎧, ⚡, 🚀, 🎨)
        if (showAccessoryBadge && size >= 40.dp) {
            val badgeSize = (size * 0.36f).coerceAtLeast(18.dp)
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(design.badgeColor, design.glowColor)
                        )
                    )
                    .border(1.5.dp, Color.White, CircleShape)
                    .shadow(2.dp, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = design.accessory,
                    fontSize = (badgeSize.value * 0.60f).sp
                )
            }
        }
    }
}

/**
 * Reusable Wiggle Box that delivers delightful spring bounces and wobbles
 * whenever clicked or triggered.
 */
@Composable
fun WiggleBox(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val rotation = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }

    val triggerWiggle = {
        coroutineScope.launch {
            scale.animateTo(1.08f, tween(70))
            rotation.animateTo(-6f, tween(60))
            rotation.animateTo(6f, tween(60))
            rotation.animateTo(-3f, tween(60))
            rotation.animateTo(0f, tween(60))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    Box(
        modifier = modifier
            .scale(scale.value)
            .rotate(rotation.value)
            .let {
                if (enabled && onClick != null) {
                    it.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        triggerWiggle()
                        onClick()
                    }
                } else if (enabled) {
                    it.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        triggerWiggle()
                    }
                } else it
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    isDark: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val bg = if (isDark) {
        Color(0xFF1E293B).copy(alpha = 0.70f)
    } else {
        Color.White.copy(alpha = 0.90f)
    }
    val borderColor = if (isDark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color(0xFFE2E8F0).copy(alpha = 0.70f)))
    }

    val baseModifier = modifier
        .clip(shape)
        .background(bg)
        .border(1.2.dp, borderColor, shape)
        .let { if (onClick != null) it.clickable { onClick() } else it }
        .padding(16.dp)

    Column(modifier = baseModifier) {
        content()
    }
}
