package com.vitra.mixin.gui;

import com.vitra.render.VitraRenderer;
import com.vitra.render.jni.VitraNativeRenderer;
import com.vitra.core.VitraConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Font rendering mixin for DirectX 11 GUI text optimization
 * Handles font rendering acceleration and batching for GUI elements
 */
@Mixin(Font.class)
public class FontRendererMixin {

    private static boolean batchTextRendering = true;
    private static long lastFontBindTime = 0;

    @Inject(method = "drawInBatch", at = @At("HEAD"), cancellable = true)
    private void onDrawInBatch(String text, float x, float y, int color, boolean shadow,
                              float matrixX, float matrixY, int matrix, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Batch text rendering for better DirectX 11 performance
            if (batchTextRendering) {
                batchTextRendering(text, x, y, color, shadow, matrixX, matrixY, matrix);
                ci.cancel();
            }
        }
    }

    private void batchTextRendering(String text, float x, float y, int color,
                                   boolean shadow, float matrixX, float matrixY, int matrix) {
        long currentTime = System.nanoTime();

        // Track font bind frequency for optimization
        if (currentTime - lastFontBindTime > 1_000_000) { // 1ms threshold
            VitraNativeRenderer.beginTextBatch();
            lastFontBindTime = currentTime;
        }

        try {
            // Direct DirectX 11 text rendering call
            VitraNativeRenderer.renderTextDirect(text, x, y, color, shadow,
                                                matrixX, matrixY, matrix);
        } catch (Exception e) {
            VitraRenderer.logDebug("Direct text rendering failed: " + e.getMessage());
            // Fall back to original rendering if direct call fails
            batchTextRendering = false;
        }
    }

    @Inject(method = "drawInBatch", at = @At("RETURN"))
    private void onDrawInBatchEnd(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Flush text batch if it's been too long
            long currentTime = System.nanoTime();
            if (currentTime - lastFontBindTime > 5_000_000) { // 5ms threshold
                VitraNativeRenderer.endTextBatch();
                lastFontBindTime = 0;
            }
        }
    }

    @Inject(method = "split", at = @At("HEAD"))
    private void onSplit(String text, int maxWidth, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            VitraRenderer.logDebug("Splitting text: '" + text + "' with max width: " + maxWidth);
        }
    }
}