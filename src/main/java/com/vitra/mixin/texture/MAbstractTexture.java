package com.vitra.mixin.texture;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.D3D11Texture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * CRITICAL mixin for texture rendering!
 *
 * VulkanMod pattern: Intercepts AbstractTexture.bind() to redirect to DirectX.
 * Without this, Minecraft's texture.bind() calls go to OpenGL and textures never
 * reach the DirectX pipeline, resulting in black/missing textures.
 *
 * Based on VulkanMod's MAbstractTexture mixin.
 */
@Mixin(AbstractTexture.class)
public abstract class MAbstractTexture {
    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("VitraTextureDebug");
    @Unique private static int setIdCount = 0;

    @Shadow protected boolean blur;
    @Shadow protected boolean mipmap;
    @Shadow protected int id;

    @Shadow public abstract int getId();

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Redirect texture binding to DirectX instead of OpenGL
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
     * @reason Update texture sampler parameters for DirectX
     */
    @Overwrite
    public void setFilter(boolean blur, boolean mipmap) {
        this.blur = blur;
        this.mipmap = mipmap;

        // Update texture sampler when filter changes (VulkanMod pattern)
        // D3D11Texture manages sampler states internally
    }

    @Unique
    private void bindTexture() {
        // CRITICAL FIX: Allocate texture ID if not yet allocated (VulkanMod pattern)
        // Some textures are constructed with id=-1 and expect lazy allocation on first bind
        if (this.id == -1) {
            this.id = D3D11Texture.genTextureId();
            if (setIdCount < 30) {
                LOGGER.info("[ABSTRACT_TEXTURE_BIND {}] Lazy allocated texture ID={}", setIdCount++, this.id);
            }
        } else {
            // DEBUG: Log first 30 bind calls to trace ID usage
            if (setIdCount < 30) {
                LOGGER.info("[ABSTRACT_TEXTURE_BIND {}] Binding texture with ID={}", setIdCount++, this.id);
            }
        }

        // CRITICAL: Bind texture using D3D11Texture directly (VulkanMod pattern)
        D3D11Texture.bindTexture(this.id);
    }
}
