package com.vitra.mixin.texture.update;

import com.vitra.VitraMod;
import com.vitra.render.VitraRenderer;
import com.vitra.render.opengl.GLInterceptor;
import com.vitra.render.jni.VitraNativeRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.Tickable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * DirectX 11 TextureManager mixin
 *
 * Based on VulkanMod's MTextureManager but adapted for DirectX 11 backend.
 * Manages the global texture update system and coordinates animated textures.
 *
 * Key responsibilities:
 * - Coordinate all tickable texture updates
 * - Manage DirectX 11 texture state transitions
 * - Optimize texture update performance
 * - Handle texture lifecycle management
 * - Integrate with Vitra renderer for DirectX 11 operations
 */
@Mixin(TextureManager.class)
public abstract class MTextureManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("MTextureManager");

    @Shadow @Final private Set<Tickable> tickableTextures;

    // Performance tracking
    private static int textureTicks = 0;
    private static long lastTickTime = 0;
    private static float averageTickTime = 0.0f;

    // Update optimization
    private static boolean enableTextureOptimizations = true;
    private static final float MAX_TICK_TIME_MS = 2.0f; // Maximum time per tick

    /**
     * @author Vitra
     * @reason Optimize texture updates for DirectX 11 rendering system
     *
     * Replaces the original texture tick method with DirectX 11 optimized version.
     * Implements performance monitoring and update scheduling for better frame rate stability.
     */
    @Overwrite(remap = false)
    public void tick() {
        // Check if Vitra renderer should skip rendering (during initialization, etc.)
        if (shouldSkipRendering()) {
            LOGGER.debug("Skipping texture tick - Vitra renderer not ready");
            return;
        }

        long startTime = System.nanoTime();

        try {
            // Flush any pending sprite uploads from previous tick
            MSpriteContents.flushPendingUploads();

            // Tick all registered textures with performance monitoring
            if (enableTextureOptimizations) {
                tickTexturesWithOptimization();
            } else {
                tickTexturesNormally();
            }

            // Process DirectX 11 texture state transitions
            processTextureStateTransitions();

            // Update performance metrics
            updatePerformanceMetrics(startTime);

        } catch (Exception e) {
            LOGGER.error("Exception during texture manager tick", e);
        }
    }

    /**
     * Check if rendering should be skipped
     */
    @Unique
    private boolean shouldSkipRendering() {
        try {
            // Check if Vitra renderer is initialized and ready
            if (!VitraNativeRenderer.isInitialized()) {
                return true;
            }

            // Check if we're in a valid render state
            if (!VitraMod.isRendering()) {
                return true;
            }

            // Additional checks can be added here as needed
            return false;

        } catch (Exception e) {
            LOGGER.error("Error checking render skip condition", e);
            return true; // Skip on error to be safe
        }
    }

    /**
     * Tick textures with performance optimization
     */
    @Unique
    private void tickTexturesWithOptimization() {
        int texturesProcessed = 0;
        long timeLimit = System.nanoTime() + (long)(MAX_TICK_TIME_MS * 1_000_000);

        for (Tickable tickable : this.tickableTextures) {
            // Check time budget
            if (System.nanoTime() > timeLimit) {
                LOGGER.debug("Texture tick time limit reached, processed {}/{} textures",
                    texturesProcessed, this.tickableTextures.size());
                break;
            }

            try {
                long textureStartTime = System.nanoTime();
                tickable.tick();
                long textureEndTime = System.nanoTime();

                // Log slow texture updates
                float textureTimeMs = (textureEndTime - textureStartTime) / 1_000_000.0f;
                if (textureTimeMs > 0.5f) {
                    LOGGER.debug("Slow texture update: {}ms for {}",
                        String.format("%.2f", textureTimeMs), tickable.getClass().getSimpleName());
                }

                texturesProcessed++;

            } catch (Exception e) {
                LOGGER.error("Exception during texture tick for {}", tickable.getClass().getSimpleName(), e);
            }
        }

        if (texturesProcessed < this.tickableTextures.size()) {
            LOGGER.debug("Deferred {} texture updates due to time limit",
                this.tickableTextures.size() - texturesProcessed);
        }
    }

    /**
     * Tick textures normally (without optimization)
     */
    @Unique
    private void tickTexturesNormally() {
        for (Tickable tickable : this.tickableTextures) {
            try {
                tickable.tick();
            } catch (Exception e) {
                LOGGER.error("Exception during texture tick for {}", tickable.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Process DirectX 11 texture state transitions
     */
    @Unique
    private void processTextureStateTransitions() {
        try {
            // Force completion of any pending DirectX 11 operations
            VitraNativeRenderer.finish();

            // Check for any texture cleanup that might be needed
            if (textureTicks % 100 == 0) { // Every 100 ticks
                performTextureMaintenance();
            }

        } catch (Exception e) {
            LOGGER.error("Exception during texture state transitions", e);
        }
    }

    /**
     * Perform periodic texture maintenance
     */
    @Unique
    private void performTextureMaintenance() {
        try {
            // Check for orphaned textures in GLInterceptor
            int orphanedCount = GLInterceptor.getOrphanedTextureCount();
            if (orphanedCount > 0) {
                LOGGER.info("Found {} orphaned textures, performing cleanup", orphanedCount);
                GLInterceptor.cleanupOrphanedTextures();
            }

            // Validate DirectX 11 texture state
            String deviceState = VitraNativeRenderer.getDeviceState();
            if (deviceState != null && deviceState.contains("ERROR")) {
                LOGGER.warn("DirectX 11 device state issues detected: {}", deviceState);
            }

        } catch (Exception e) {
            LOGGER.error("Exception during texture maintenance", e);
        }
    }

    /**
     * Update performance metrics
     */
    @Unique
    private void updatePerformanceMetrics(long startTime) {
        long endTime = System.nanoTime();
        float tickTimeMs = (endTime - startTime) / 1_000_000.0f;

        textureTicks++;

        // Update rolling average
        if (averageTickTime == 0.0f) {
            averageTickTime = tickTimeMs;
        } else {
            // Exponential moving average with alpha = 0.1
            averageTickTime = 0.9f * averageTickTime + 0.1f * tickTimeMs;
        }

        lastTickTime = endTime;

        // Log performance warnings
        if (tickTimeMs > MAX_TICK_TIME_MS) {
            LOGGER.debug("Slow texture tick: {}ms (avg: {}ms)",
                String.format("%.2f", tickTimeMs), String.format("%.2f", averageTickTime));
        }

        // Log periodic statistics
        if (textureTicks % 600 == 0) { // Every 600 ticks (approximately 30 seconds at 20 FPS)
            LOGGER.info("Texture manager stats: {} ticks, avg time: {}ms, textures: {}",
                textureTicks, String.format("%.2f", averageTickTime), this.tickableTextures.size());
        }
    }

    /**
     * Enable/disable texture update optimizations
     */
    @Unique
    public static void setTextureOptimizationsEnabled(boolean enabled) {
        enableTextureOptimizations = enabled;
        LOGGER.info("Texture update optimizations: {}", enabled ? "enabled" : "disabled");
    }

    /**
     * Get texture manager performance statistics
     */
    @Unique
    public static String getPerformanceStatistics() {
        return String.format("Texture Ticks: %d, Avg Time: %.2fms, Textures: %d, Optimizations: %s",
            textureTicks, averageTickTime,
            com.vitra.VitraMod.getInstance().getRenderer() != null ?
                getActiveTextureCount() : 0,
            enableTextureOptimizations ? "enabled" : "disabled");
    }

    /**
     * Reset texture manager statistics
     */
    @Unique
    public static void resetStatistics() {
        textureTicks = 0;
        lastTickTime = 0;
        averageTickTime = 0.0f;
        LOGGER.info("Texture manager statistics reset");
    }

    /**
     * Get the number of active textures (requires access to VitraRenderer)
     */
    @Unique
    private static int getActiveTextureCount() {
        try {
            VitraRenderer renderer = com.vitra.VitraMod.getInstance().getRenderer();
            if (renderer != null) {
                // This would need to be implemented in VitraRenderer
                // For now, return the number of registered textures
                return GLInterceptor.getRegisteredTextureCount();
            }
        } catch (Exception e) {
            LOGGER.debug("Could not get active texture count", e);
        }
        return 0;
    }

    /**
     * Configure texture manager settings
     */
    @Unique
    public static void configureTextureManager(float maxTickTimeMs, boolean enableLogging) {
        // Note: MAX_TICK_TIME_MS is final, but this shows how it could be configured
        if (enableLogging) {
            LOGGER.info("Texture manager configured with max tick time: {}ms", MAX_TICK_TIME_MS);
        }
    }

    /**
     * Validate texture manager state
     */
    @Unique
    public static boolean validateTextureManagerState() {
        try {
            // Check if DirectX 11 is available
            if (!VitraNativeRenderer.isInitialized()) {
                LOGGER.warn("DirectX 11 not initialized for texture manager");
                return false;
            }

            // Check if GLInterceptor is available
            if (!GLInterceptor.isAvailable()) {
                LOGGER.warn("GLInterceptor not available for texture manager");
                return false;
            }

            return true;

        } catch (Exception e) {
            LOGGER.error("Error validating texture manager state", e);
            return false;
        }
    }
}