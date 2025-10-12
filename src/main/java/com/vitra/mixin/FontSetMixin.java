package com.vitra.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.providers.UnihexProvider;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;

/**
 * TEMPORARY WORKAROUND: Prevent font loading deadlock
 *
 * This mixin intercepts Unicode .hex font loading (UnihexProvider$Definition) to prevent
 * the deadlock/access violation that occurs when worker threads load fonts
 * and trigger BGFX texture uploads on non-render threads.
 *
 * Minecraft 1.21.8 loads fonts asynchronously on worker threads during resource reload.
 * This causes problems because BGFX texture operations must happen on the render thread.
 *
 * The load() method is private and called via lambda (invokedynamic) from unpack().
 *
 * Strategy:
 * 1. Log when font loading starts and on which thread
 * 2. Identify the exact point of failure
 * 3. Later: Defer texture upload to render thread or synchronize properly
 */
@Mixin(value = net.minecraft.client.gui.font.providers.UnihexProvider.Definition.class)
public class FontSetMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("FontSetMixin");

    /**
     * Intercept GlyphProviderDefinition.Loader.load() method
     * This is called when UnihexProvider loads .hex font files
     *
     * Method signature: GlyphProvider load(ResourceManager) throws IOException
     * Descriptor: (Lnet/minecraft/server/packs/resources/ResourceManager;)Lcom/mojang/blaze3d/font/GlyphProvider;
     */
    @Inject(
        method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;)Lcom/mojang/blaze3d/font/GlyphProvider;",
        at = @At("HEAD")
    )
    private void onUnihexLoad(ResourceManager resourceManager, CallbackInfoReturnable<GlyphProvider> cir) {
        LOGGER.warn("╔════════════════════════════════════════════════════════════╗");
        LOGGER.warn("║  UNIHEX FONT LOADING INTERCEPTED                           ║");
        LOGGER.warn("╠════════════════════════════════════════════════════════════╣");
        LOGGER.warn("║ Thread: {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
        LOGGER.warn("║ Loading Unicode .hex font file                            ║");
        LOGGER.warn("║ ⚠️  This will likely cause texture upload on wrong thread ║");
        LOGGER.warn("╚════════════════════════════════════════════════════════════╝");
    }

    @Inject(
        method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;)Lcom/mojang/blaze3d/font/GlyphProvider;",
        at = @At("RETURN")
    )
    private void onUnihexLoadComplete(ResourceManager resourceManager, CallbackInfoReturnable<GlyphProvider> cir) {
        LOGGER.warn("╔════════════════════════════════════════════════════════════╗");
        LOGGER.warn("║  UNIHEX FONT LOADING COMPLETE                              ║");
        LOGGER.warn("║ Thread: {} (ID: {})", Thread.currentThread().getName(), Thread.currentThread().getId());
        LOGGER.warn("║ If you see this, font loading succeeded                   ║");
        LOGGER.warn("║ If you don't see this, it crashed during loading          ║");
        LOGGER.warn("╚════════════════════════════════════════════════════════════╝");
    }
}
