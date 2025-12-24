package com.vitra.mixin.texture;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.D3D11Texture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * CRITICAL MIXIN: Automatically wraps ALL AbstractTexture instances with D3D11Texture
 *
 * VulkanMod Pattern: MAbstractTexture.java
 * This ensures every Minecraft texture (gui, blocks, entities, etc.) is tracked by D3D11TextureSelector
 *
 * Without this mixin, only manually created textures in TextureManagerMixin are tracked,
 * leaving gaps that cause rendering artifacts (red triangles, missing textures).
 */
@Mixin(AbstractTexture.class)
public abstract class AbstractTextureMixin {
    @Shadow protected boolean blur;
    @Shadow protected boolean mipmap;
    @Shadow protected int id;

    /**
     * CRITICAL: Redirect AbstractTexture.bind() to D3D11Texture.bindTexture()
     * This ensures all texture bindings go through our tracking system.
     *
     * VulkanMod equivalent: VkGlTexture.bindTexture()
     *
     * @author Vitra
     * @reason Redirect all texture bindings to D3D11 backend
     */
    @Overwrite
    public void bind() {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(this::bindTextureImpl);
        } else {
            this.bindTextureImpl();
        }
    }

    /**
     * CRITICAL: Update texture sampler parameters (blur, mipmap)
     * This is called when texture parameters change (e.g., setFilter in options)
     *
     * VulkanMod Pattern: Updates VulkanImage sampler
     * Vitra: Updates D3D11 sampler state
     *
     * @author Vitra
     * @reason Update D3D11 sampler state for texture filtering
     */
    @Overwrite
    public void setFilter(boolean blur, boolean mipmap) {
        this.blur = blur;
        this.mipmap = mipmap;

        // Update D3D11 sampler state for this texture
        // VulkanMod equivalent: vulkanImage.updateTextureSampler(blur, clamp, mipmap)
        D3D11Texture texture = D3D11Texture.getTexture(this.id);
        if (texture != null && texture.isValid()) {
            // Update sampler: blur affects min/mag filter, mipmap affects mip filter
            D3D11Texture.updateTextureSampler(blur, false, mipmap);
        }
    }

    @Unique
    private void bindTextureImpl() {
        // Redirect to D3D11Texture.bindTexture which handles:
        // 1. Binding to native D3D11 SRV
        // 2. Registering with D3D11TextureSelector
        // 3. Setting active texture unit
        D3D11Texture.bindTexture(this.id);
    }
}
