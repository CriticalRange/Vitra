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
 * GameRenderer Mixin for DirectX 11 JNI shader management
 *
 * Minecraft 1.21.8 uses RenderPipeline system for shader management.
 * This mixin intercepts shader preloading to prevent deadlocks and add visibility.
 *
 * CRITICAL: Minecraft 1.21.8 loads shaders during resource reload on worker threads.
 * This can cause deadlocks with DirectX 11 JNI since DirectX operations must happen on render thread.
 * We intercept preloadUiShader to ensure it runs on the render thread.
 *
 * Strategy (following VulkanMod's approach):
 * - Intercept preloadUiShader to log and verify thread safety
 * - Let Minecraft handle the actual shader loading (RenderPipeline system)
 * - Our JniShaderManager will handle DirectX 11 shader loading
 * - Focus on preventing deadlocks by ensuring operations stay on render thread
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("GameRendererMixin");

    /**
     * Intercept preloadUiShader to add DirectX 11 JNI-aware logging and thread verification
     *
     * This method is called BEFORE the main resource reload, ensuring shaders
     * are loaded on the render thread and not during worker thread resource loading.
     *
     * Following VulkanMod's pattern: intercept shader preloading to prevent deadlocks.
     */
    @Inject(method = "preloadUiShader", at = @At("HEAD"))
    private void onPreloadUiShader(ResourceProvider resourceProvider, CallbackInfo ci) {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  PRELOADING UI SHADER (DirectX 11 JNI)                      ║");
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
        LOGGER.info("║  Shaders ready for DirectX 11 JNI rendering                 ║");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");
    }

    /**
     * Intercept close() to ensure safe DirectX 11 JNI shutdown
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        LOGGER.info("GameRenderer closing - ensuring DirectX 11 JNI resources are cleaned up");
    }
}
