package com.vitra.mixin.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vitra.render.VitraRenderer;
import com.vitra.core.VitraConfig;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Title screen mixin for DirectX 11 optimization
 * Handles main menu rendering performance improvements
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    private static long titleRenderStart = 0;
    private static int logoRenderTime = 0;
    private static int buttonsRenderTime = 0;

    @Inject(method = "render", at = @At("HEAD"))
    private void onTitleRenderStart(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            titleRenderStart = System.nanoTime();
            VitraRenderer.logDebug("[TITLE] Starting title screen render");
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onTitleRenderEnd(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            long totalRenderTime = System.nanoTime() - titleRenderStart;
            VitraRenderer.logDebug(String.format(
                "[TITLE] Complete render: %.2fms (Logo: %.2fms, Buttons: %.2fms)",
                totalRenderTime / 1_000_000.0,
                logoRenderTime / 1_000_000.0,
                buttonsRenderTime / 1_000_000.0
            ));
        }
    }

    @Inject(method = "renderPanorama", at = @At("HEAD"))
    private void onRenderPanorama(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize panorama rendering for DirectX 11
            VitraRenderer.optimizePanoramaRendering();
        }
    }

    @Inject(method = "renderLogo", at = @At("HEAD"))
    private void onRenderLogoStart(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            logoRenderTime = (int) (System.nanoTime() - titleRenderStart);
        }
    }

    @Inject(method = "renderLogo", at = @At("RETURN"))
    private void onRenderLogoEnd(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize logo rendering
            VitraRenderer.optimizeLogoRendering();
        }
    }

    @Inject(method = "renderButtonBackgrounds", at = @At("HEAD"))
    private void onRenderButtonBackgroundsStart(PoseStack poseStack, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            buttonsRenderTime = (int) (System.nanoTime() - titleRenderStart);
        }
    }

    @Inject(method = "renderButtonBackgrounds", at = @At("RETURN"))
    private void onRenderButtonBackgroundsEnd(PoseStack poseStack, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize button background rendering
            VitraRenderer.optimizeButtonRendering();
        }
    }

    @Inject(method = "renderFadingMenuBackground", at = @At("HEAD"))
    private void onRenderFadingMenuBackground(PoseStack poseStack, int alpha, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize fading background
            VitraRenderer.optimizeFadingBackground(alpha);
        }
    }
}