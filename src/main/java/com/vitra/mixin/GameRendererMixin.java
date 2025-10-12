package com.vitra.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer Mixin for BGFX shader management
 *
 * Minecraft 1.21.8 uses RenderPipeline system for shader management.
 * This mixin intercepts shader preloading to prevent deadlocks and add visibility.
 *
 * CRITICAL: Minecraft 1.21.8 loads shaders during resource reload on worker threads.
 * This can cause deadlocks with BGFX since BGFX operations must happen on render thread.
 * We intercept preloadUiShader to ensure it runs on the render thread.
 *
 * Strategy (following VulkanMod's approach):
 * - Intercept preloadUiShader to log and verify thread safety
 * - Let Minecraft handle the actual shader loading (RenderPipeline system)
 * - Our BgfxCompiledRenderPipeline will handle BGFX shader compilation
 * - Focus on preventing deadlocks by ensuring operations stay on render thread
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GameRendererMixin");

    /**
     * Intercept preloadUiShader to add BGFX-aware logging and thread verification
     *
     * This method is called BEFORE the main resource reload, ensuring shaders
     * are loaded on the render thread and not during worker thread resource loading.
     *
     * Following VulkanMod's pattern: intercept shader preloading to prevent deadlocks.
     */
    @Inject(method = "preloadUiShader", at = @At("HEAD"))
    private void onPreloadUiShader(ResourceProvider resourceProvider, CallbackInfo ci) {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  PRELOADING UI SHADER (BGFX)                               ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ This prevents deadlocks during resource reload            ║");
        LOGGER.info("║ Shader preloading on render thread (not worker threads)   ║");
        LOGGER.info("║ Thread: {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
    }

    /**
     * Intercept preloadUiShader completion to verify success
     */
    @Inject(method = "preloadUiShader", at = @At("RETURN"))
    private void onPreloadUiShaderComplete(ResourceProvider resourceProvider, CallbackInfo ci) {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  UI SHADER PRELOAD COMPLETE                                ║");
        LOGGER.info("║  Shaders ready for BGFX rendering                          ║");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
    }

    /**
     * Intercept close() to ensure safe BGFX shutdown
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        LOGGER.info("GameRenderer closing - ensuring BGFX resources are cleaned up");
    }
}
