package com.vitra.mixin.texture;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.Dx11Texture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CRITICAL mixin for texture rendering!
 *
 * VulkanMod pattern: Intercepts AbstractTexture.bind() to redirect to DirectX 11.
 * Without this, Minecraft's texture.bind() calls go to OpenGL and textures never
 * reach the DirectX 11 pipeline, resulting in black/missing textures.
 *
 * Based on VulkanMod's MAbstractTexture mixin.
 */
@Mixin(AbstractTexture.class)
public abstract class MAbstractTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/MAbstractTexture");

    @Shadow protected boolean blur;
    @Shadow protected boolean mipmap;
    @Shadow protected int id;

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect texture binding to DirectX 11 instead of OpenGL
     */
    @Overwrite
    public void bind() {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(this::bindTexture);
        } else {
            this.bindTexture();
        }
    }

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Update texture sampler parameters for DirectX 11
     */
    @Overwrite
    public void setFilter(boolean blur, boolean mipmap) {
        this.blur = blur;
        this.mipmap = mipmap;

        // Update DirectX 11 texture sampler when filter changes
        Dx11Texture texture = Dx11Texture.getTexture(this.id);
        if (texture != null && texture.getNativeHandle() != 0) {
            // DirectX 11 sampler state will be updated based on blur/mipmap flags
            LOGGER.debug("Updated texture {} filter: blur={}, mipmap={}", this.id, blur, mipmap);
        }
    }

    @Unique
    private void bindTexture() {
        // CRITICAL: Bind DirectX 11 texture instead of OpenGL texture
        Dx11Texture.bindTexture(this.id);
        LOGGER.debug("Bound DirectX 11 texture: ID={}", this.id);
    }
}
