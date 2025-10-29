package com.vitra.mixin.screen;

import com.vitra.render.VitraRenderer;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adapted from VulkanMod's ScreenM.java
 * Clears depth buffer after rendering blurred background to fix UI rendering issues.
 */
@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(method = "renderBlurredBackground", at = @At("RETURN"))
    private void clearDepth(float f, CallbackInfo ci) {
        // Workaround to fix hardcoded z value on PostPass blit shader,
        // that conflicts with DirectX depth range [0.0, 1.0]
        // This is critical for UI elements to render correctly!
        VitraRenderer renderer = VitraRenderer.getInstance();
        if (renderer != null) {
            // Clear depth buffer (256 = GL_DEPTH_BUFFER_BIT)
            renderer.clearAttachments(256);
        }
    }
}
