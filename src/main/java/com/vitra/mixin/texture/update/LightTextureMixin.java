package com.vitra.mixin.texture.update;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.texture.D3D11TextureSelector;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CRITICAL MIXIN: Ensures lightmap texture is properly bound to shader slot 2
 *
 * VulkanMod Pattern: MLightTexture.java
 * Without this, GUI elements render BLACK because they sample from unbound SRV slot 2
 *
 * DirectX 11 Behavior: Sampling from unbound SRV returns (0,0,0,0) = black
 * Result: Invisible GUI text, black buttons, broken lighting
 */
@Mixin(LightTexture.class)
public class LightTextureMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/LightTextureMixin");

    @Shadow @Final private DynamicTexture lightTexture;

    /**
     * CRITICAL: Override turnOnLightLayer() to bind lightmap to slot 2
     *
     * VulkanMod Pattern: MLightTexture.java:50-52
     * This is called before GUI rendering to enable lighting calculations
     *
     * @author Vitra
     * @reason Ensure lightmap texture is bound to D3D11 shader slot 2
     */
    @Overwrite
    public void turnOnLightLayer() {
        int lightmapId = this.lightTexture.getId();

        // Standard RenderSystem call (sets shader texture slot 2)
        RenderSystem.setShaderTexture(2, lightmapId);

        // CRITICAL: Also register in D3D11TextureSelector
        // This ensures the texture is tracked and bound to D3D11 GPU
        D3D11TextureSelector.bindTextureToUnit(2, lightmapId);

        // Immediately bind to GPU (VulkanMod pattern)
        // This prevents race condition where shader is applied before texture binding
        D3D11TextureSelector.bindShaderTextures();

        LOGGER.debug("[LIGHTMAP_BIND] Bound lightmap texture ID={} to slot 2", lightmapId);
    }
}
