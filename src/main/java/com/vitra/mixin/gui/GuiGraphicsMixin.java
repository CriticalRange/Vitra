package com.vitra.mixin.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vitra.render.VitraRenderer;
import com.vitra.core.VitraConfig;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GUI graphics mixin for DirectX 11 rendering optimization
 * Optimizes GUI rendering operations for better performance
 */
@Mixin(GuiGraphics.class)
public class GuiGraphicsMixin {

    private static int guiDrawCalls = 0;
    private static long guiRenderStart = 0;

    @Inject(method = "drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V", at = @At("HEAD"))
    private void onDrawStringStart(PoseStack poseStack, net.minecraft.client.gui.Font font,
                                   String text, int x, int y, int color, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            guiRenderStart = System.nanoTime();
        }
    }

    @Inject(method = "drawString(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V", at = @At("RETURN"))
    private void onDrawStringEnd(PoseStack poseStack, net.minecraft.client.gui.Font font,
                                 String text, int x, int y, int color, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            long renderTime = System.nanoTime() - guiRenderStart;
            VitraRenderer.logDebug(String.format(
                "[GUI] Text render: '%s' at (%d,%d) took %.2fμs",
                text, x, y, renderTime / 1000.0
            ));
        }
    }

    @Inject(method = "blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIIIII)V", at = @At("HEAD"))
    private void onBlitStart(PoseStack poseStack, int x, int y, int uOffset, int vOffset,
                             int width, int height, int textureWidth, int textureHeight, CallbackInfo ci) {
        guiDrawCalls++;
    }

    @Inject(method = "blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIII)V", at = @At("HEAD"))
    private void onSimpleBlitStart(PoseStack poseStack, int x, int y, int width, int height, CallbackInfo ci) {
        guiDrawCalls++;
    }

    @Inject(method = "fill(Lcom/mojang/blaze3d/vertex/PoseStack;IIIII)V", at = @At("HEAD"))
    private void onFillStart(PoseStack poseStack, int minX, int minY, int maxX, int maxY, int color, CallbackInfo ci) {
        guiDrawCalls++;
    }

    @Inject(method = "flush", at = @At("HEAD"))
    private void onFlush(CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode() && guiDrawCalls > 0) {
            VitraRenderer.logDebug("[GUI] Flushing " + guiDrawCalls + " draw calls");
            guiDrawCalls = 0;
        }
    }

    public static int getGuiDrawCalls() {
        return guiDrawCalls;
    }

    public static void resetGuiDrawCalls() {
        guiDrawCalls = 0;
    }
}