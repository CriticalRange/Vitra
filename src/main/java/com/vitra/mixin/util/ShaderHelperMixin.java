package com.vitra.mixin.util;

import com.vitra.render.VitraRenderer;
import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.core.VitraConfig;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shader helper mixin for DirectX 11 shader management
 * Provides shader compilation, caching, and optimization utilities
 */
@Mixin(ShaderInstance.class)
public class ShaderHelperMixin {

    private static int shaderCompileCount = 0;
    private static int shaderCacheHits = 0;
    private static long totalShaderCompileTime = 0;

    @Inject(method = "<init>", at = @At("HEAD"))
    private void onShaderInitStart(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            shaderCompileCount++;
            long startTime = System.nanoTime();

            // Pre-compile shader for DirectX 11 if needed
            VitraRenderer.precompileShaderForDirectX11((ShaderInstance)(Object)this);

            totalShaderCompileTime += System.nanoTime() - startTime;
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onShaderInitEnd(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode() && VitraConfig.getInstance().isDirectX11Enabled()) {
            VitraRenderer.logDebug(String.format(
                "[SHADER] Compiled shader #%d in %.2fμs",
                shaderCompileCount, totalShaderCompileTime / 1000.0
            ));
        }
    }

    @Inject(method = "apply", at = @At("HEAD"))
    private void onShaderApply(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Track shader application for optimization
            VitraNativeRenderer.trackShaderApplication((ShaderInstance)(Object)this);
        }
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void onShaderClear(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Cleanup DirectX 11 shader resources
            VitraNativeRenderer.cleanupShaderResources((ShaderInstance)(Object)this);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void onShaderClose(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Release DirectX 11 shader resources
            VitraNativeRenderer.releaseShaderResources((ShaderInstance)(Object)this);
        }
    }

    /**
     * Get shader compilation statistics
     */
    public static int getShaderCompileCount() {
        return shaderCompileCount;
    }

    public static int getShaderCacheHits() {
        return shaderCacheHits;
    }

    public static long getTotalShaderCompileTime() {
        return totalShaderCompileTime;
    }

    /**
     * Reset shader statistics
     */
    public static void resetShaderStats() {
        shaderCompileCount = 0;
        shaderCacheHits = 0;
        totalShaderCompileTime = 0;
    }

    /**
     * Check if shader is DirectX 11 compatible
     */
    public static boolean isDirectX11Compatible(ShaderInstance shader) {
        return VitraConfig.getInstance().isDirectX11Enabled() &&
               VitraRenderer.isShaderDirectX11Compatible(shader);
    }

    /**
     * Get optimized DirectX 11 shader
     */
    public static ShaderInstance getOptimizedDirectX11Shader(ShaderInstance original) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            return VitraRenderer.getOptimizedDirectX11Shader(original);
        }
        return original;
    }
}