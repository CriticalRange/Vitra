package com.vitra.mixin.texture;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.render.Dx11Texture;
import com.vitra.VitraMod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

// DirectX 11 format constants (replacing Vulkan VK10 constants)

@Mixin(TextureUtil.class)
public class MTextureUtil {

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int generateTextureId() {
        RenderSystem.assertOnRenderThreadOrInit();
        return Dx11Texture.genTextureId();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void prepareImage(NativeImage.InternalGlFormat internalGlFormat, int id, int mipLevels, int width, int height) {
        RenderSystem.assertOnRenderThreadOrInit();
        Dx11Texture.bindTexture(id);
        Dx11Texture dx11Texture = Dx11Texture.getTexture(id);

        if (dx11Texture == null || dx11Texture.needsRecreation(mipLevels, width, height)) {
            if (dx11Texture != null) {
                dx11Texture.release();
            }

            // Create DirectX 11 texture with specified parameters
            Dx11Texture.createTexture(id, width, height, mipLevels + 1, convertFormat(internalGlFormat));

            System.out.println("[Vitra] Created DirectX 11 texture: " + id + " (" + width + "x" + height + ", mipLevels=" + (mipLevels + 1) + ")");
        }

        if (mipLevels > 0) {
            // Set mipmap parameters for DirectX 11
            Dx11Texture.setTextureParameter(id, GL30.GL_TEXTURE_MAX_LEVEL, mipLevels);
            Dx11Texture.setTextureParameter(id, GL30.GL_TEXTURE_MIN_LOD, 0);
            Dx11Texture.setTextureParameter(id, GL30.GL_TEXTURE_MAX_LOD, mipLevels);
            Dx11Texture.setTextureParameter(id, GL30.GL_TEXTURE_LOD_BIAS, 0);
        }
    }

    @Unique
    private static int convertFormat(NativeImage.InternalGlFormat format) {
        return switch (format) {
            case RGBA -> Dx11Texture.FORMAT_RGBA8_UNORM;
            case RED -> Dx11Texture.FORMAT_R8_UNORM;
            default -> throw new IllegalArgumentException(String.format("Unexpected format: %s", format));
        };
    }
}
