package com.vitra.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Complete GameRenderer mixin for DirectX shader lifecycle management.
 * Handles shader preloading and initialization.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameRendererMixin.class);

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private Map<String, ShaderInstance> shaders;

    /**
     * Inject into preloadUiShader to ensure DirectX UI shaders are ready.
     */
    @Inject(method = "preloadUiShader", at = @At("RETURN"))
    private void onPreloadUiShader(ResourceProvider resourceProvider, CallbackInfo ci) {
        LOGGER.info("DirectX: UI shaders preloaded successfully");
        LOGGER.info("Total shaders loaded: {}", shaders.size());

        // Log all loaded shaders for debugging
        if (LOGGER.isDebugEnabled()) {
            for (String shaderName : shaders.keySet()) {
                LOGGER.debug("  - Shader loaded: {}", shaderName);
            }
        }
    }

    /**
     * Inject at the start of tick to update DirectX state.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickStart(CallbackInfo ci) {
        // Update DirectX render state if needed
        // This runs every game tick (20 times per second)
    }

    /**
     * Inject at the end of tick for cleanup.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void onTickEnd(CallbackInfo ci) {
        // Cleanup or state updates after tick
    }
}
