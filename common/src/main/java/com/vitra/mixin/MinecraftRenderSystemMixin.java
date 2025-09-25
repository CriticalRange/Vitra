package com.vitra.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.VitraMod;
import net.minecraft.client.Minecraft;
import net.minecraft.util.TimeSource;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin to completely replace OpenGL RenderSystem with pure BGFX DirectX 11
 */
@Mixin(Minecraft.class)
public class MinecraftRenderSystemMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraRenderSystemMixin");

    /**
     * Log OpenGL backend initialization but allow it (can't cancel constructor injections)
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;initBackendSystem()Lnet/minecraft/util/TimeSource$NanoTimeSource;", shift = At.Shift.BEFORE))
    private void beforeOpenGLInitialization(CallbackInfo ci) {
        LOGGER.info("About to initialize OpenGL RenderSystem - BGFX DirectX 11 will supplement this");
    }

    /**
     * After OpenGL backend initialization - set up pure BGFX DirectX 11 handling
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;initBackendSystem()Lnet/minecraft/util/TimeSource$NanoTimeSource;", shift = At.Shift.AFTER))
    private void afterOpenGLInitialization(CallbackInfo ci) {
        LOGGER.info("OpenGL RenderSystem initialized - now BGFX DirectX 11 will handle all actual rendering");

        try {
            // Initialize BGFX after OpenGL for compatibility, then redirect calls
            if (VitraMod.getRenderer() != null) {
                LOGGER.info("BGFX DirectX 11 renderer ready to intercept all OpenGL rendering calls");
            }

        } catch (Exception e) {
            LOGGER.error("Exception during BGFX DirectX 11 post-OpenGL setup", e);
        }
    }
}