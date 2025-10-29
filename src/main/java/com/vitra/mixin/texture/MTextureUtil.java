package com.vitra.mixin.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.D3D11Texture;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin to replace OpenGL texture operations with D3D11 equivalents.
 * Intercepts TextureUtil methods and redirects to D3D11Texture.
 *
 * Based on VulkanMod's MTextureUtil mixin.
 */
@Mixin(TextureUtil.class)
public class MTextureUtil {

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL texture generation with D3D11 texture ID generation
     */
    @Overwrite(remap = false)
    public static int generateTextureId() {
        RenderSystem.assertOnRenderThreadOrInit();
        int id = D3D11Texture.genTextureId();

        // DEBUG: Log first 20 generateTextureId calls
        genTextureIdCount++;
        if (genTextureIdCount <= 20) {
            System.out.println("[GEN_TEXTURE_ID " + genTextureIdCount + "] Generated ID=" + id);
        }

        return id;
    }

    private static int genTextureIdCount = 0;

    /**
     * @author Vitra (adapted from VulkanMod)
     * @reason Replace OpenGL texture preparation with D3D11 texture creation
     */
    @Overwrite(remap = false)
    public static void prepareImage(NativeImage.InternalGlFormat internalGlFormat, int id, int mipLevels, int width, int height) {
        RenderSystem.assertOnRenderThreadOrInit();

        // DEBUG: Log first 20 prepareImage calls to trace texture ID flow
        prepareImageCount++;
        if (prepareImageCount <= 20) {
            System.out.println("[PREPARE_IMAGE " + prepareImageCount + "] ID=" + id + ", size=" + width + "x" + height + ", mips=" + mipLevels);
        }

        // CRITICAL FIX (VulkanMod pattern): Bind texture by ID to ensure it's tracked
        // This ensures the texture ID is associated with subsequent texImage2D calls
        D3D11Texture.bindTexture(id);

        // CRITICAL (VulkanMod pattern): Create texture here if needed
        // VulkanMod creates textures in prepareImage, not lazily in texImage2D
        // This ensures the texture exists before any upload operations
        D3D11Texture texture = D3D11Texture.getBoundTexture();
        if (texture == null) {
            System.out.println("[PREPARE_IMAGE_ERROR] No bound texture after binding ID=" + id);
            return;
        }

        // Convert GL format to DirectX format
        int dxFormat = D3D11Texture.convertGlFormat(
            internalGlFormat == NativeImage.InternalGlFormat.RGBA ? 0x1908 : 0x1903, // GL_RGBA : GL_RGB
            0x1401 // GL_UNSIGNED_BYTE
        );

        // Create or recreate texture if dimensions or format changed
        boolean needs = texture.needsRecreation(mipLevels, width, height);
        if (prepareImageCount <= 20) {
            System.out.println("[PREPARE_IMAGE_DEBUG] ID=" + id + ", needsRecreation=" + needs +
                ", nativeHandle=" + texture.getNativeHandle());
        }

        if (needs) {
            D3D11Texture.createTexture(id, width, height, mipLevels, dxFormat);
            if (prepareImageCount <= 20) {
                System.out.println("[PREPARE_IMAGE_CREATED] ID=" + id + ", nativeHandle=" + texture.getNativeHandle());
            }
        }
    }

    private static int prepareImageCount = 0;
}
