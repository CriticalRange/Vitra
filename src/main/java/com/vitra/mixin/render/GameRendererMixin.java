package com.vitra.mixin.render;

import com.vitra.render.VitraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameRenderer mixin for Minecraft 26.1.
 * Handles DirectX depth far plane and shader lifecycle management.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/GameRenderer");

    @Shadow @Final Minecraft minecraft;

    /**
     * Inject into preloadUiShader to log DirectX UI shader loading.
     */
    @Inject(method = "preloadUiShader", at = @At("RETURN"))
    private void onPreloadUiShader(ResourceProvider resourceProvider, CallbackInfo ci) {
        LOGGER.info("[GameRenderer] UI shaders preloaded for DirectX");
    }

    /**
     * Inject at the start of tick to update DirectX state.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickStart(CallbackInfo ci) {
        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer != null && renderer.isFullyInitialized()) {
            renderer.markUniformsDirty();
        }
    }

    /**
     * Set depth far plane to infinity for better depth precision.
     * VulkanMod pattern - improves depth buffer utilization.
     *
     * @author Vitra (based on VulkanMod)
     * @reason DirectX benefits from infinite far plane for better depth precision
     */
    @Overwrite
    public float getDepthFar() {
        return Float.POSITIVE_INFINITY;
    }
}
