package com.vitra.mixin.util;

import com.vitra.render.VitraRenderer;
import com.vitra.core.VitraConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Render utilities mixin for DirectX 11 helper functions
 * Provides common rendering utilities and optimization helpers
 */
@Mixin(Minecraft.class)
public class RenderUtilsMixin {

    private static boolean isInRenderThread = false;
    private static long renderThreadStart = 0;

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V"))
    private void onRenderFrameStart(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            isInRenderThread = true;
            renderThreadStart = System.nanoTime();

            // Prepare DirectX 11 rendering context
            VitraRenderer.prepareRenderContext();
        }
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V", shift = At.Shift.AFTER))
    private void onRenderFrameEnd(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            long renderFrameTime = System.nanoTime() - renderThreadStart;

            // Cleanup DirectX 11 rendering context
            VitraRenderer.cleanupRenderContext();

            isInRenderThread = false;

            if (VitraConfig.getInstance().isDebugMode()) {
                VitraRenderer.logDebug(String.format(
                    "[UTIL] Frame render time: %.2fms",
                    renderFrameTime / 1_000_000.0
                ));
            }
        }
    }

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void onGetFramerateLimit(CallbackInfoReturnable<Integer> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Apply DirectX 11 specific framerate optimization
            int optimizedLimit = VitraRenderer.getOptimalFramerateLimit();
            if (optimizedLimit > 0) {
                cir.setReturnValue(optimizedLimit);
            }
        }
    }

    @Inject(method = "resizeDisplay", at = @At("HEAD"))
    private void onResizeDisplay(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Handle DirectX 11 display resize
            VitraRenderer.handleDisplayResize();
        }
    }

    @Inject(method = "isWindowActive", at = @At("HEAD"))
    private void onIsWindowActive(CallbackInfoReturnable<Boolean> cir) {
        if (VitraConfig.getInstance().isDirectX11Enabled() && isInRenderThread) {
            // Optimize DirectX 11 rendering when window is inactive
            VitraRenderer.setWindowActiveState(cir.getReturnValue());
        }
    }

    /**
     * Check if current thread is render thread
     */
    public static boolean isInRenderThread() {
        return isInRenderThread;
    }

    /**
     * Get current render thread start time
     */
    public static long getRenderThreadStart() {
        return renderThreadStart;
    }

    /**
     * Safely execute render-related operations
     */
    public static void safeRenderOperation(Runnable operation) {
        if (isInRenderThread) {
            operation.run();
        } else {
            VitraRenderer.logDebug("[UTIL] Attempted render operation outside render thread");
        }
    }

    /**
     * Check if DirectX 11 is ready for rendering
     */
    public static boolean isDirectX11Ready() {
        return VitraConfig.getInstance().isDirectX11Enabled() &&
               VitraRenderer.isDirectX11Initialized();
    }
}