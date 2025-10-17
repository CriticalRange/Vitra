package com.vitra.mixin.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.vitra.render.VitraRenderer;
import com.vitra.core.VitraConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menu screen mixin for DirectX 11 inventory optimization
 * Handles container and inventory screen rendering improvements
 */
@Mixin(AbstractContainerScreen.class)
public class MenuScreenMixin {

    private static long menuRenderStart = 0;
    private static int slotRenderCount = 0;

    @Inject(method = "render", at = @At("HEAD"))
    private void onMenuRenderStart(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            menuRenderStart = System.nanoTime();
            slotRenderCount = 0;
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void onMenuRenderEnd(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDebugMode()) {
            long renderTime = System.nanoTime() - menuRenderStart;
            VitraRenderer.logDebug(String.format(
                "[MENU] Rendered %d slots in %.2fms",
                slotRenderCount, renderTime / 1_000_000.0
            ));
        }
    }

    @Inject(method = "renderSlot", at = @At("HEAD"))
    private void onRenderSlot(PoseStack poseStack, net.minecraft.world.inventory.Slot slot, CallbackInfo ci) {
        slotRenderCount++;

        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize individual slot rendering for DirectX 11
            VitraRenderer.optimizeSlotRendering(slot.x, slot.y);
        }
    }

    @Inject(method = "renderSlotHighlight", at = @At("HEAD"))
    private void onRenderSlotHighlight(PoseStack poseStack, int x, int y, int z, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize slot highlight rendering
            VitraRenderer.optimizeSlotHighlight(x, y);
        }
    }

    @Inject(method = "renderLabels", at = @At("HEAD"))
    private void onRenderLabels(PoseStack poseStack, int mouseX, int mouseY, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize label rendering for containers
            VitraRenderer.optimizeContainerLabels();
        }
    }

    @Inject(method = "renderBg", at = @At("HEAD"))
    private void onRenderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        if (VitraConfig.getInstance().isDirectX11Enabled()) {
            // Optimize container background rendering
            VitraRenderer.optimizeContainerBackground();
        }
    }

    public static int getSlotRenderCount() {
        return slotRenderCount;
    }
}