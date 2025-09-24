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
 * Mixin to intercept RenderSystem initialization and replace with BGFX D3D11
 */
@Mixin(Minecraft.class)
public class MinecraftRenderSystemMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraRenderSystemMixin");

    /**
     * Replace RenderSystem.initBackendSystem() with BGFX D3D11 initialization
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;initBackendSystem()Lnet/minecraft/util/TimeSource$NanoTimeSource;", shift = At.Shift.AFTER))
    private void afterInitBackendSystem(CallbackInfo ci) {
        LOGGER.info("RenderSystem initialized - setting GLFW_NO_API and preparing BGFX D3D11");

        try {
            // Set GLFW_NO_API globally before any window creation
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
            LOGGER.info("Successfully set GLFW_NO_API globally - all windows will be created without OpenGL context");
        } catch (Exception e) {
            LOGGER.error("Failed to set GLFW_NO_API globally", e);
        }

        try {
            // Prepare the renderer but don't initialize BGFX yet - wait for window handle
            if (VitraMod.getRenderer() != null) {
                LOGGER.info("BGFX D3D11 renderer prepared, waiting for window handle");
            }

        } catch (Exception e) {
            LOGGER.error("Exception during BGFX D3D11 preparation", e);
        }
    }
}