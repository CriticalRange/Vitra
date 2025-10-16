package com.vitra.mixin;

import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.VitraMod;
import com.vitra.render.jni.VitraNativeRenderer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Window Mixin for DirectX 11 (Minecraft 1.21.1)
 *
 * Following VulkanMod's proven approach for Minecraft 1.21.1:
 * - Block OpenGL context creation to prevent conflicts with DirectX 11
 * - Set GLFW_NO_API hint for DirectX 11 compatibility
 * - Capture window handle for DirectX 11 initialization
 * - Handle window resize and VSync operations
 *
 * This mixin ensures DirectX 11 can work alongside Minecraft's window management.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Final @Shadow private long window;
    @Shadow private boolean vsync;
    @Shadow protected abstract void updateFullscreen(boolean bl);
    @Shadow private boolean fullscreen;
    @Shadow @Final private static Logger LOGGER;
    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private int framebufferWidth;
    @Shadow private int framebufferHeight;
    @Shadow public abstract int getWidth();
    @Shadow public abstract int getHeight();

    /**
     * Block OpenGL window hints to prevent conflicts with DirectX 11
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private void redirect(int hint, int value) {
        // NO-OP: Block OpenGL window hints for DirectX 11
    }

    /**
     * Block OpenGL context creation to prevent conflicts with DirectX 11
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"))
    private void redirect2(long window) {
        // NO-OP: Block OpenGL context creation for DirectX 11
    }

    /**
     * Block OpenGL capabilities creation to prevent conflicts with DirectX 11
     */
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"))
    private GLCapabilities redirect2() {
        return null; // Block OpenGL capabilities for DirectX 11
    }

    /**
     * Set DirectX 11 compatible window hints
     */
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private void vulkanHint(WindowEventHandler windowEventHandler, ScreenManager screenManager, DisplayData displayData, String string, String string2, CallbackInfo ci) {
        GLFW.glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
    }

    /**
     * Capture window handle for DirectX 11 initialization
     */
    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void getHandle(WindowEventHandler windowEventHandler, ScreenManager screenManager, DisplayData displayData, String string, String string2, CallbackInfo ci) {
        LOGGER.info("Window created with handle: 0x{}", Long.toHexString(window));

        try {
            // Initialize DirectX 11 with the window handle
            VitraNativeRenderer.initializeDirectX(window, width, height, false);
            LOGGER.info("DirectX 11 initialized successfully with window handle");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize DirectX 11", e);
        }
    }

    /**
     * Handle VSync for DirectX 11
     */
    @Overwrite
    public void updateVsync(boolean vsync) {
        this.vsync = vsync;
        VitraNativeRenderer.setVsync(vsync);
    }

    /**
     * Handle fullscreen toggle for DirectX 11
     */
    @Overwrite
    public void toggleFullScreen() {
        this.fullscreen = !this.fullscreen;
        // TODO: Handle fullscreen toggle for DirectX 11
    }

    /**
     * Handle display updates for DirectX 11
     */
    @Overwrite
    public void updateDisplay() {
        RenderSystem.flipFrame(this.window);
        // TODO: Handle fullscreen updates if needed
    }

    /**
     * Handle window resize for DirectX 11 swap chain
     */
    @Overwrite
    private void onFramebufferResize(long window, int width, int height) {
        if (window == this.window) {
            if(width > 0 && height > 0) {
                this.framebufferWidth = width;
                this.framebufferHeight = height;

                // Resize DirectX 11 swap chain
                VitraNativeRenderer.resize(width, height);
                LOGGER.info("DirectX 11 resized to {}x{}", width, height);
            }
        }
    }

    /**
     * Handle window resize for DirectX 11
     */
    @Overwrite
    private void onResize(long window, int width, int height) {
        this.width = width;
        this.height = height;

        if(width > 0 && height > 0) {
            VitraNativeRenderer.resize(width, height);
        }
    }

  }
