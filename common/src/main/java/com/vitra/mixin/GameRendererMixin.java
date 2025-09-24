package com.vitra.mixin;

import com.vitra.VitraMod;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameRendererMixin.class);
    private static boolean vitraInitialized = false;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(CallbackInfo ci) {
        if (!vitraInitialized && VitraMod.getRenderer() != null) {
            LOGGER.info("Triggering BGFX initialization from first render call");
            VitraMod.getRenderer().beginFrame();
            vitraInitialized = true;
        }

        if (VitraMod.getRenderer() != null) {
            VitraMod.getRenderer().beginFrame();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(CallbackInfo ci) {
        if (VitraMod.getRenderer() != null) {
            VitraMod.getRenderer().endFrame();
        }
    }
}