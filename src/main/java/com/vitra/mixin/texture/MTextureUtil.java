package com.vitra.mixin.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.texture.IVitraTexture;
import com.vitra.render.texture.VitraTextureFactory;
import com.vitra.render.texture.D3D12Texture;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin to replace OpenGL texture operations with D3D11 equivalents.
 * Intercepts TextureUtil methods and redirects to D3D11Texture wrapper.
 */
@Mixin(TextureUtil.class)
public class MTextureUtil {

    /**
     * @author Vitra
     * @reason Replace OpenGL texture generation with renderer-agnostic texture ID generation
     */
    @Overwrite(remap = false)
    public static int generateTextureId() {
        RenderSystem.assertOnRenderThreadOrInit();
        return VitraTextureFactory.genTextureId();
    }

    /**
     * @author Vitra
     * @reason Replace OpenGL texture preparation with renderer-agnostic texture creation
     */
    @Overwrite(remap = false)
    public static void prepareImage(NativeImage.InternalGlFormat internalGlFormat, int id, int mipLevels, int width, int height) {
        RenderSystem.assertOnRenderThreadOrInit();
        VitraTextureFactory.bindTexture(id);
        IVitraTexture texture = VitraTextureFactory.getTexture(id);

        if (texture == null || texture.needsRecreation(mipLevels + 1, width, height)) {
            if (texture != null) {
                texture.destroy();
            }

            // Create texture with specified parameters using renderer-agnostic factory
            // Note: For D3D12, texture will be created during upload()
            // For D3D11, we need to create it explicitly
            texture = VitraTextureFactory.getTexture(id);
            if (texture != null) {
                // Upload empty texture to initialize it with correct dimensions
                byte[] emptyData = new byte[width * height * 4]; // RGBA
                texture.upload(emptyData, width, height, convertFormat(internalGlFormat));
            }
        }

        if (mipLevels > 0 && texture != null) {
            // Set mipmap parameters
            texture.setParameter(GL30.GL_TEXTURE_MAX_LEVEL, mipLevels);
            texture.setParameter(GL30.GL_TEXTURE_MIN_LOD, 0);
            texture.setParameter(GL30.GL_TEXTURE_MAX_LOD, mipLevels);
            texture.setParameter(GL30.GL_TEXTURE_LOD_BIAS, 0);
        }
    }

    @Unique
    private static int convertFormat(NativeImage.InternalGlFormat format) {
        return switch (format) {
            case RGBA -> D3D12Texture.FORMAT_RGBA8_UNORM;
            case RED -> D3D12Texture.FORMAT_R8_UNORM;
            default -> throw new IllegalArgumentException(String.format("Unexpected format: %s", format));
        };
    }
}
