package com.vitra.mixin;

import com.mojang.blaze3d.platform.Window;
import com.vitra.render.backend.BgfxWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(Window.class)
public class WindowMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraWindowMixin");

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onWindowInit(CallbackInfo ci) {
        LOGGER.info("Window created - registering with BGFX wrapper");
        BgfxWindow.getInstance().wrapWindow((Window)(Object)this);
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void redirectUpdateDisplay(CallbackInfo ci) {
        LOGGER.debug("Intercepting updateDisplay - using BGFX frame submission");

        // Cancel the original OpenGL swap and use BGFX instead
        ci.cancel();
        BgfxWindow.getInstance().handleUpdateDisplay();
    }
}