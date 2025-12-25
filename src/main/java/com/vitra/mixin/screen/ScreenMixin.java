package com.vitra.mixin.screen;

import com.vitra.render.VitraRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screen mixin for Minecraft 26.1.
 * Based on VulkanMod's ScreenM.java - clears depth buffer after blurred background.
 * 
 * 26.1 API Change: renderBlurredBackground now takes GuiGraphics parameter.
 */
@Mixin(Screen.class)
public class ScreenMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/Screen");

    /**
     * Clear depth buffer after rendering blurred background.
     * Workaround to fix hardcoded z value on PostPass blit shader
     * that conflicts with DirectX depth range [0.0, 1.0].
     * This is critical for UI elements to render correctly after blur!
     * 
     * 26.1 API Change: Method now takes GuiGraphics instead of float
     */
    @Inject(method = "renderBlurredBackground(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("RETURN"))
    private void clearDepthAfterBlur(GuiGraphics guiGraphics, CallbackInfo ci) {
        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer != null) {
            // Clear depth buffer (256 = GL_DEPTH_BUFFER_BIT)
            renderer.clearAttachments(256);
            LOGGER.trace("[Screen] Cleared depth buffer after blurred background");
        }
    }
}
