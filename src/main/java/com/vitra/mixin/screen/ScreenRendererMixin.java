package com.vitra.mixin.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vitra.render.VitraRenderer;
import com.vitra.core.VitraConfig;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screen rendering mixin for DirectX 11 optimization
 * Handles general screen rendering performance improvements
 */
@Mixin(Screen.class)
public class ScreenRendererMixin {

    private static long screenRenderStart = 0;
    private static String currentScreenName = "";

    @Inject(method = "render", at = @At("HEAD"))
    private void onScreenRenderStart(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            screenRenderStart = System.nanoTime();
            currentScreenName = ((Screen)(Object)this).getClass().getSimpleName();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onScreenRenderEnd(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            long renderTime = System.nanoTime() - screenRenderStart;
            VitraRenderer.logDebug(String.format(
                "[SCREEN] %s rendered in %.2fms",
                currentScreenName, renderTime / 1_000_000.0
            ));
        }
    }

    @Inject(method = "renderBackground", at = @At("HEAD"))
    private void onRenderBackground(PoseStack poseStack, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize background rendering for DirectX 11
            VitraRenderer.optimizeScreenBackground();
        }
    }

    @Inject(method = "renderDirtBackground", at = @At("HEAD"))
    private void onRenderDirtBackground(int vOffset, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize dirt background rendering for DirectX 11
            VitraRenderer.optimizeDirtBackground(vOffset);
        }
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"))
    private void onRenderTooltip(PoseStack poseStack, java.util.List<net.minecraft.network.chat.Component> components, int x, int y, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            VitraRenderer.logDebug(String.format(
                "[TOOLTIP] Rendering tooltip with %d lines at (%d,%d)",
                components.size(), x, y
            ));
        }
    }

    @Inject(method = "renderComponentTooltip", at = @At("HEAD"))
    private void onRenderComponentTooltip(PoseStack poseStack, java.util.List<net.minecraft.network.chat.Component> components, int x, int y, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize component tooltip rendering
            VitraRenderer.optimizeTooltipRendering(components.size());
        }
    }

    public static String getCurrentScreenName() {
        return currentScreenName;
    }
}