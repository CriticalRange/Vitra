package com.vitra.mixin.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vitra.render.VitraRenderer;
import com.vitra.core.VitraConfig;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD renderer mixin for DirectX 11 optimization
 * Handles heads-up display rendering performance improvements
 */
@Mixin(targets = "net.minecraft.client.gui.Gui")
public class HudRendererMixin {

    private static long hudRenderStart = 0;
    private static int hudElementCount = 0;

    @Inject(method = "render", at = @At("HEAD"))
    private void onHudRenderStart(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            hudRenderStart = System.nanoTime();
            hudElementCount = 0;
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onHudRenderEnd(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            long renderTime = System.nanoTime() - hudRenderStart;
            VitraRenderer.logDebug(String.format(
                "[HUD] Rendered %d elements in %.2fms",
                hudElementCount, renderTime / 1_000_000.0
            ));
        }
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"))
    private void onRenderCrosshair(PoseStack poseStack, CallbackInfo ci) {
        hudElementCount++;
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize crosshair rendering for DirectX 11
            VitraRenderer.optimizeCrosshairRendering();
        }
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"))
    private void onRenderHotbar(float partialTick, PoseStack poseStack, CallbackInfo ci) {
        hudElementCount++;
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"))
    private void onRenderExperienceBar(PoseStack poseStack, int x, CallbackInfo ci) {
        hudElementCount++;
    }

    @Inject(method = "renderHealth", at = @At("HEAD"))
    private void onRenderHealth(PoseStack poseStack, CallbackInfo ci) {
        hudElementCount++;
    }

    @Inject(method = "renderArmor", at = @At("HEAD"))
    private void onRenderArmor(PoseStack poseStack, CallbackInfo ci) {
        hudElementCount++;
    }

    @Inject(method = "renderFood", at = @At("HEAD"))
    private void onRenderFood(PoseStack poseStack, CallbackInfo ci) {
        hudElementCount++;
    }

    @Inject(method = "renderAir", at = @At("HEAD"))
    private void onRenderAir(int width, int height, CallbackInfo ci) {
        hudElementCount++;
    }

    @Inject(method = "renderEffects", at = @At("HEAD"))
    private void onRenderEffects(PoseStack poseStack, CallbackInfo ci) {
        hudElementCount++;
    }

    @Inject(method = "renderVignette", at = @At("HEAD"))
    private void onRenderVignette(PoseStack poseStack, CallbackInfo ci) {
        hudElementCount++;
    }

    public static int getHudElementCount() {
        return hudElementCount;
    }
}