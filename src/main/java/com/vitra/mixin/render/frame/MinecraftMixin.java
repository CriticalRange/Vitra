package com.vitra.mixin.render.frame;

import com.vitra.VitraMod;
import com.vitra.render.IVitraRenderer;
import com.vitra.render.VitraRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.main.GameConfig;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft mixin for frame lifecycle and DirectX initialization.
 * Critical for proper frame management, resize handling, and cleanup.
 * 
 * Updated for Minecraft 26.1.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/Minecraft");

    @Shadow @Final public Options options;

    @Nullable
    private static VitraRenderer getRenderer() {
        IVitraRenderer baseRenderer = VitraMod.getRenderer();
        if (baseRenderer instanceof VitraRenderer renderer) {
            return renderer;
        }
        return null;
    }

    /**
     * Log when Minecraft is fully initialized.
     * Note: GraphicsMode API changed in 26.1 (now graphicsPreset)
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(GameConfig gameConfig, CallbackInfo ci) {
        LOGGER.info("[Minecraft] Vitra DirectX renderer initialized");
    }

    /**
     * Handle resize events for DirectX swap chain recreation.
     */
    @Inject(method = "resizeDisplay", at = @At("HEAD"))
    private void onResizeDisplay(CallbackInfo ci) {
        IVitraRenderer renderer = VitraMod.getRenderer();
        if (renderer != null) {
            Minecraft mc = (Minecraft)(Object)this;
            renderer.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            LOGGER.debug("[Minecraft] Resize: {}x{}", mc.getWindow().getWidth(), mc.getWindow().getHeight());
        }
    }

    /**
     * Wait for GPU commands before close.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        IVitraRenderer renderer = VitraMod.getRenderer();
        if (renderer != null) {
            LOGGER.info("[Minecraft] Waiting for GPU commands before close");
            renderer.waitForGpuCommands();
        }
    }

    /**
     * Prevent noRender field modification on setScreen (fixes minimize crash).
     */
    @Redirect(method = "setScreen", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;noRender:Z", opcode = Opcodes.PUTFIELD))
    private void preventNoRenderSet(Minecraft instance, boolean value) {
        // No-op: prevents crash when minimizing window before setScreen
    }
}
