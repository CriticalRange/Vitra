package com.vitra.mixin.texture;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.VitraMod;
import com.vitra.render.opengl.GLInterceptor;
import com.vitra.render.jni.VitraNativeRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * DirectX 11 Texture Utility mixin
 *
 * Based on VulkanMod's MTextureUtil but adapted for DirectX 11 backend.
 * Handles low-level texture operations including format conversion,
 * mipmap generation, and DirectX 11 resource management.
 *
 * Key responsibilities:
 * - Texture ID generation for DirectX 11
 * - Image preparation and format conversion
 * - Mipmap level management
 * - DirectX 11 texture resource lifecycle
 */
@Mixin(TextureUtil.class)
public class MTextureUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("MTextureUtil");

    // Statistics tracking
    private static int texturesGenerated = 0;
    private static int imagesPrepared = 0;

    /**
     * @author Vitra
     * @reason Generate texture ID for DirectX 11 backend
     *
     * Replaces OpenGL texture ID generation with DirectX 11 handle creation.
     * Uses GLInterceptor to maintain compatibility with existing OpenGL code paths.
     */
    @Overwrite(remap = false)
    public static int generateTextureId() {
        RenderSystem.assertOnRenderThreadOrInit();

        try {
            // Generate OpenGL texture ID (for compatibility with existing code)
            int glTextureId = GLInterceptor.glGenTextures();

            // Create corresponding DirectX 11 texture resource
            long directXHandle = VitraNativeRenderer.createTexture(null, 1, 1, 0);

            if (directXHandle != 0) {
                // Register the DirectX 11 handle with GLInterceptor
                GLInterceptor.registerTexture(glTextureId, directXHandle);

                texturesGenerated++;
                LOGGER.debug("Generated texture ID {} with DirectX 11 handle 0x{}",
                    glTextureId, Long.toHexString(directXHandle));

                return glTextureId;
            } else {
                LOGGER.error("Failed to create DirectX 11 texture for OpenGL texture ID {}", glTextureId);
                return glTextureId; // Return OpenGL ID as fallback
            }
        } catch (Exception e) {
            LOGGER.error("Exception in generateTextureId", e);
            return GLInterceptor.glGenTextures(); // Fallback to existing implementation
        }
    }

    /**
     * @author Vitra
     * @reason Prepare image with DirectX 11 format conversion and mipmap setup
     *
     * Replaces OpenGL image preparation with DirectX 11 equivalent operations.
     * Handles format conversion from OpenGL ABGR to DirectX 11 RGBA and sets up
     * mipmap levels for DirectX 11 textures.
     */
    @Overwrite(remap = false)
    public static void prepareImage(NativeImage.InternalGlFormat internalGlFormat, int id, int mipLevels, int width, int height) {
        RenderSystem.assertOnRenderThreadOrInit();

        imagesPrepared++;

        try {
            // Bind the texture to make it current
            GLInterceptor.glBindTexture(GL11.GL_TEXTURE_2D, id);

            // Get DirectX 11 handle for this texture
            Long directXHandleObj = GLInterceptor.getDirectXHandle(id);
            if (directXHandleObj == null) {
                LOGGER.warn("No DirectX 11 handle found for texture ID {}, creating new one", id);
                directXHandleObj = VitraNativeRenderer.createTexture(null, width, height, mipLevels);
                if (directXHandleObj != null) {
                    GLInterceptor.registerTexture(id, directXHandleObj);
                }
            }

            long directXHandle = directXHandleObj != null ? directXHandleObj : 0L;

            if (directXHandle == 0) {
                LOGGER.error("Failed to get DirectX 11 handle for texture ID {}", id);
                return;
            }

            // Set up mipmap levels if requested
            if (mipLevels > 0) {
                // DirectX 11 texture parameters
                VitraNativeRenderer.setTextureParameter(directXHandle,
                    GL30.GL_TEXTURE_MAX_LEVEL, mipLevels);
                VitraNativeRenderer.setTextureParameter(directXHandle,
                    GL30.GL_TEXTURE_MIN_LOD, 0);
                VitraNativeRenderer.setTextureParameter(directXHandle,
                    GL30.GL_TEXTURE_MAX_LOD, mipLevels);
                VitraNativeRenderer.setTextureParameter(directXHandle,
                    GL30.GL_TEXTURE_LOD_BIAS, 0.0f);

                LOGGER.debug("Set up {} mipmap levels for texture ID {} ({}x{})",
                    mipLevels, id, width, height);
            }

            // Convert OpenGL format to DirectX 11 format
            int directXFormat = convertGlFormatToDirectX(internalGlFormat);

            // Set texture format in DirectX 11 texture
            VitraNativeRenderer.setTextureFormat(directXHandle, directXFormat);

            // Create or recreate DirectX 11 texture if dimensions or format changed
            boolean needsRecreation = VitraNativeRenderer.needsTextureRecreation(
                directXHandle, width, height, directXFormat);

            if (needsRecreation) {
                LOGGER.info("Recreating DirectX 11 texture for ID {} ({}x{}, format={})",
                    id, width, height, internalGlFormat);

                // Release old texture and create new one
                VitraNativeRenderer.releaseTexture(directXHandle);
                long newHandle = VitraNativeRenderer.createTexture(null, width, height, mipLevels);

                if (newHandle != 0) {
                    GLInterceptor.registerTexture(id, newHandle);
                    VitraNativeRenderer.setTextureFormat(newHandle, directXFormat);

                    // Apply mipmap settings to new texture
                    if (mipLevels > 0) {
                        VitraNativeRenderer.setTextureParameter(newHandle,
                            GL30.GL_TEXTURE_MAX_LEVEL, mipLevels);
                    }
                } else {
                    LOGGER.error("Failed to recreate DirectX 11 texture for ID {}", id);
                }
            }

        } catch (Exception e) {
            LOGGER.error("Exception in prepareImage for texture ID {} ({}x{}, levels={})",
                id, width, height, mipLevels, e);
        }
    }

    /**
     * Convert OpenGL internal format to DirectX 11 format
     *
     * @param format OpenGL internal format
     * @return DirectX 11 format constant
     */
    @Unique
    private static int convertGlFormatToDirectX(NativeImage.InternalGlFormat format) {
        return switch (format) {
            case RGBA -> VitraNativeRenderer.DXGI_FORMAT_R8G8B8A8_UNORM;
            case RED -> VitraNativeRenderer.DXGI_FORMAT_R8_UNORM;
            case RGB -> VitraNativeRenderer.DXGI_FORMAT_R8G8B8A8_UNORM; // Convert RGB to RGBA
            case RG -> VitraNativeRenderer.DXGI_FORMAT_R8G8_UNORM;
            default -> {
                LOGGER.warn("Unsupported OpenGL format {}, using RGBA fallback", format);
                yield VitraNativeRenderer.DXGI_FORMAT_R8G8B8A8_UNORM;
            }
        };
    }

    /**
     * @author Vitra
     * @reason Release texture resources for DirectX 11
     *
     * Ensures proper cleanup of DirectX 11 resources when texture is no longer needed.
     */
    @Overwrite(remap = false)
    public static void releaseTextureId(int id) {
        RenderSystem.assertOnRenderThreadOrInit();

        try {
            // Get DirectX 11 handle for cleanup
            Long directXHandleObj = GLInterceptor.getDirectXHandle(id);
            if (directXHandleObj != null) {
                long directXHandle = directXHandleObj;

                // Release DirectX 11 texture resource
                VitraNativeRenderer.releaseTexture(directXHandle);

                // Unregister from GLInterceptor
                GLInterceptor.unregisterTexture(id);

                LOGGER.debug("Released DirectX 11 texture 0x{} for OpenGL texture ID {}",
                    Long.toHexString(directXHandle), id);
            }

            // Also release OpenGL resources via GLInterceptor
            GLInterceptor.glDeleteTextures(id);

        } catch (Exception e) {
            LOGGER.error("Exception in releaseTextureId for texture ID {}", id, e);
        }
    }

    /**
     * @author Vitra
     * @reason Validate texture dimensions for DirectX 11
     *
     * Ensures texture dimensions are within DirectX 11 limits and properly aligned.
     */
    @Overwrite(remap = false)
    public static boolean validateTextureSize(int width, int height) {
        // DirectX 11 minimum texture size is 1x1
        if (width < 1 || height < 1) {
            LOGGER.warn("Invalid texture size: {}x{}, must be at least 1x1", width, height);
            return false;
        }

        // DirectX 11 maximum texture size (usually 16384x16384 for D3D11)
        int maxTextureSize = VitraNativeRenderer.getMaxTextureSize();
        if (width > maxTextureSize || height > maxTextureSize) {
            LOGGER.warn("Texture size {}x{} exceeds maximum DirectX 11 texture size of {}x{}",
                width, height, maxTextureSize, maxTextureSize);
            return false;
        }

        // Check for power-of-two dimensions (recommended but not required for DirectX 11)
        boolean isWidthPowerOfTwo = (width & (width - 1)) == 0;
        boolean isHeightPowerOfTwo = (height & (height - 1)) == 0;

        if (!isWidthPowerOfTwo || !isHeightPowerOfTwo) {
            LOGGER.debug("Non-power-of-two texture size {}x{} (supported but may affect performance)",
                width, height);
        }

        return true;
    }

    /**
     * @author Vitra
     * @reason Get texture statistics for debugging
     *
     * Provides statistics about texture operations for performance monitoring.
     */
    @Unique
    public static String getTextureStatistics() {
        return String.format("Textures Generated: %d, Images Prepared: %d",
            texturesGenerated, imagesPrepared);
    }

    /**
     * @author Vitra
     * @reason Reset texture statistics
     *
     * Resets the internal statistics counters (useful for testing).
     */
    @Unique
    public static void resetStatistics() {
        texturesGenerated = 0;
        imagesPrepared = 0;
        LOGGER.info("Texture statistics reset");
    }
}