package com.vitra.mixin;

import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Minecraft's ShaderManager to prevent GLSL shader loading.
 * Since Vitra uses DirectX 11 with HLSL shaders, we don't need the GLSL pipeline.
 */
@Mixin(ShaderManager.class)
public class ShaderManagerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraShaderManagerMixin");

    /**
     * Intercept the prepare() method to return empty shader configurations.
     * This prevents Minecraft from trying to load GLSL shaders that don't exist.
     */
    @Inject(
        method = "prepare(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)Lnet/minecraft/client/renderer/ShaderManager$Configs;",
        at = @At("HEAD"),
        cancellable = true
    )
    @SuppressWarnings("unchecked")
    private void onPrepare(ResourceManager resourceManager, ProfilerFiller profiler, CallbackInfoReturnable cir) {
        LOGGER.info("Vitra: Bypassing GLSL shader loading - using DirectX 11 HLSL shaders instead");

        // Return empty shader configs - we don't need GLSL shaders
        // The DirectX 11 shaders are already loaded by D3D11ShaderManager
        try {
            // Get the EMPTY constant from ShaderManager$Configs
            Class<?> configsClass = Class.forName("net.minecraft.client.renderer.ShaderManager$Configs");
            Object emptyConfigs = configsClass.getField("EMPTY").get(null);
            cir.setReturnValue(emptyConfigs);
        } catch (Exception e) {
            LOGGER.error("Failed to get empty shader configs", e);
        }
    }
}
