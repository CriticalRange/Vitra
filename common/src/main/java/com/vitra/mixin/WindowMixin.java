package com.vitra.mixin;

import com.vitra.util.WindowUtil;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(Minecraft.class)
public class WindowMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowMixin.class);

    @Shadow
    private com.mojang.blaze3d.platform.Window window;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onMinecraftInit(CallbackInfo ci) {
        if (this.window != null) {
            // Use reflection to get the window handle
            try {
                java.lang.reflect.Field handleField = this.window.getClass().getDeclaredField("window");
                handleField.setAccessible(true);
                long windowHandle = (long) handleField.get(this.window);

                LOGGER.info("Minecraft window handle captured: 0x{}", Long.toHexString(windowHandle));
                WindowUtil.setMinecraftWindowHandle(windowHandle);
            } catch (Exception e) {
                LOGGER.error("Failed to get window handle via reflection", e);
            }
        }
    }
}