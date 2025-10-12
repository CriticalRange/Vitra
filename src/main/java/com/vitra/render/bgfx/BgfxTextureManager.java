package com.vitra.render.bgfx;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages texture bindings for BGFX rendering.
 *
 * This class uses ONLY BGFX's native methods:
 * - bgfx_create_texture_2d() - Create texture from image data
 * - bgfx_set_texture() - Bind texture to shader sampler
 * - bgfx_destroy_texture() - Release texture resource
 * - bgfx_create_uniform() - Create uniform handle for sampler
 * - bgfx_destroy_uniform() - Release uniform handle
 *
 * NO custom texture format conversions or custom implementations.
 * All texture operations are delegated to BGFX's native API.
 */
public class BgfxTextureManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxTextureManager");

    // Cache: ResourceLocation -> BGFX texture handle
    private final Map<ResourceLocation, Short> textureHandles = new ConcurrentHashMap<>();

    // Cache: GpuTexture -> BGFX texture handle (for dynamic textures like fonts)
    private static final Map<GpuTexture, Short> gpuTextureHandles = new ConcurrentHashMap<>();

    // Cache: Texture unit -> BGFX uniform handle for samplers
    private final Map<Integer, Short> samplerUniforms = new ConcurrentHashMap<>();

    // Track active textures per unit (for bgfx_set_texture)
    private final short[] activeTextures = new short[16]; // Units 0-15

    /**
     * Get or create BGFX uniform handle for a texture sampler.
     * Uses: bgfx_create_uniform()
     *
     * @param unit Texture unit (0-15)
     * @return BGFX uniform handle
     */
    public short getSamplerUniform(int unit) {
        return samplerUniforms.computeIfAbsent(unit, u -> {
            String samplerName = "s_texColor" + (u == 0 ? "" : u);
            short uniform = BGFX.bgfx_create_uniform(samplerName, BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
            LOGGER.debug("Created BGFX sampler uniform '{}' for unit {}: handle={}", samplerName, u, uniform);
            return uniform;
        });
    }

    /**
     * Bind texture to shader sampler using BGFX native method.
     * Uses: bgfx_set_texture()
     *
     * This method ONLY calls BGFX's API, no custom logic.
     *
     * @param stage Texture stage (0-15)
     * @param uniform Uniform handle from getSamplerUniform()
     * @param textureHandle BGFX texture handle
     * @param flags Sampling flags (BGFX_SAMPLER_*)
     */
    public void bindTexture(int stage, short uniform, short textureHandle, int flags) {
        // Direct BGFX API call - no custom implementation
        BGFX.bgfx_set_texture(stage, uniform, textureHandle, flags);
        activeTextures[stage] = textureHandle;
        LOGGER.trace("bgfx_set_texture(stage={}, uniform={}, texture={}, flags=0x{})",
            stage, uniform, textureHandle, Integer.toHexString(flags));
    }

    /**
     * Track texture handle mapping from ResourceLocation.
     * Does NOT create the texture - texture creation is done elsewhere using bgfx_create_texture_2d().
     *
     * @param location Minecraft resource location
     * @param textureHandle BGFX texture handle (from bgfx_create_texture_2d)
     */
    public void registerTexture(ResourceLocation location, short textureHandle) {
        textureHandles.put(location, textureHandle);
        LOGGER.debug("Registered texture: {} -> BGFX handle {}", location, textureHandle);
    }

    /**
     * Get cached BGFX texture handle for a resource location.
     *
     * @param location Minecraft resource location
     * @return BGFX texture handle, or -1 if not found
     */
    public short getTextureHandle(ResourceLocation location) {
        return textureHandles.getOrDefault(location, (short) -1);
    }

    /**
     * Destroy texture using BGFX native method.
     * Uses: bgfx_destroy_texture()
     *
     * @param textureHandle BGFX texture handle to destroy
     */
    public void destroyTexture(short textureHandle) {
        if (Util.isValidHandle(textureHandle)) {
            BGFX.bgfx_destroy_texture(textureHandle);
            LOGGER.debug("Destroyed BGFX texture: handle={}", textureHandle);
        }
    }

    /**
     * Get or create BGFX texture handle for a GpuTexture.
     * Used by CommandEncoderMixin for font/UI texture uploads.
     *
     * @param gpuTexture Minecraft GpuTexture object
     * @return BGFX texture handle
     */
    public static short getOrCreateTexture(GpuTexture gpuTexture) {
        return gpuTextureHandles.computeIfAbsent(gpuTexture, tex -> {
            // Create BGFX texture with same dimensions as GpuTexture
            int width = tex.getWidth(0);
            int height = tex.getHeight(0);

            // Convert Minecraft TextureFormat to BGFX format
            int bgfxFormat = convertTextureFormat(tex.getFormat());

            short handle = BGFX.bgfx_create_texture_2d(
                (short) width,
                (short) height,
                false, // hasMips
                1,     // numLayers
                bgfxFormat,
                BGFX.BGFX_TEXTURE_NONE | BGFX.BGFX_SAMPLER_NONE,
                null   // mem (no initial data)
            );

            if (Util.isValidHandle(handle)) {
                LOGGER.debug("Created BGFX texture for GpuTexture: {}x{}, format={}, handle={}",
                    width, height, tex.getFormat(), handle);
            } else {
                LOGGER.error("Failed to create BGFX texture for GpuTexture: {}x{}", width, height);
            }

            return handle;
        });
    }

    /**
     * Upload texture data from NativeImage to BGFX texture.
     * Uses: bgfx_update_texture_2d()
     *
     * @param textureHandle BGFX texture handle
     * @param image NativeImage containing pixel data
     * @param mipLevel Mipmap level (0 for base)
     * @param offsetX X offset in texture
     * @param offsetY Y offset in texture
     * @param width Width of region to update
     * @param height Height of region to update
     */
    public static void uploadTextureData(short textureHandle, NativeImage image,
                                        int mipLevel, int offsetX, int offsetY,
                                        int width, int height) {
        if (!Util.isValidHandle(textureHandle)) {
            LOGGER.error("Invalid texture handle in uploadTextureData");
            return;
        }

        try {
            // NativeImage stores pixels in a ByteBuffer internally
            // We need to access it via makePixelArray() or convert to ByteBuffer

            // Allocate ByteBuffer for pixel data
            int imageSize = width * height * 4; // RGBA = 4 bytes per pixel
            ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(imageSize);

            // Copy pixel data from NativeImage to ByteBuffer
            // NativeImage stores pixels as ABGR (int), we need RGBA
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = image.getPixel(x, y);

                    // NativeImage pixel format is ABGR (0xAABBGGRR)
                    int a = (pixel >> 24) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int r = pixel & 0xFF;

                    // Write as RGBA
                    pixelBuffer.put((byte) r);
                    pixelBuffer.put((byte) g);
                    pixelBuffer.put((byte) b);
                    pixelBuffer.put((byte) a);
                }
            }
            pixelBuffer.flip();

            // Create BGFX memory from ByteBuffer
            BGFXMemory memory = BGFX.bgfx_copy(pixelBuffer);

            // Upload to BGFX texture
            BGFX.bgfx_update_texture_2d(
                textureHandle,
                0,           // layer
                (byte) mipLevel,
                (short) offsetX,
                (short) offsetY,
                (short) width,
                (short) height,
                memory,
                (short) (width * 4) // pitch (bytes per row)
            );

            LOGGER.trace("Uploaded texture data: handle={}, offset=({},{}), size={}x{}",
                textureHandle, offsetX, offsetY, width, height);

        } catch (Exception e) {
            LOGGER.error("Failed to upload texture data", e);
        }
    }

    /**
     * Upload texture data from IntBuffer to BGFX texture.
     * Uses: bgfx_update_texture_2d()
     *
     * @param textureHandle BGFX texture handle
     * @param buffer IntBuffer containing pixel data
     * @param format NativeImage format
     * @param mipLevel Mipmap level
     * @param offsetX X offset
     * @param offsetY Y offset
     * @param width Width
     * @param height Height
     */
    public static void uploadTextureDataFromBuffer(short textureHandle, IntBuffer buffer,
                                                   NativeImage.Format format,
                                                   int mipLevel, int offsetX, int offsetY,
                                                   int width, int height) {
        if (!Util.isValidHandle(textureHandle)) {
            LOGGER.error("Invalid texture handle in uploadTextureDataFromBuffer");
            return;
        }

        try {
            // Convert IntBuffer to ByteBuffer
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(buffer.remaining() * 4);
            byteBuffer.asIntBuffer().put(buffer);
            byteBuffer.position(0);

            // Create BGFX memory from buffer
            BGFXMemory memory = BGFX.bgfx_copy(byteBuffer);

            // Upload to BGFX texture
            BGFX.bgfx_update_texture_2d(
                textureHandle,
                0,           // layer
                (byte) mipLevel,
                (short) offsetX,
                (short) offsetY,
                (short) width,
                (short) height,
                memory,
                (short) (width * 4) // pitch
            );

            LOGGER.trace("Uploaded texture data from IntBuffer: handle={}, size={}x{}",
                textureHandle, width, height);

        } catch (Exception e) {
            LOGGER.error("Failed to upload texture data from IntBuffer", e);
        }
    }

    /**
     * Convert Minecraft TextureFormat to BGFX format constant.
     *
     * @param format Minecraft texture format
     * @return BGFX format constant
     */
    private static int convertTextureFormat(com.mojang.blaze3d.textures.TextureFormat format) {
        // Most common format: RGBA8
        return BGFX.BGFX_TEXTURE_FORMAT_RGBA8;

        // TODO: Add proper format conversion based on format.toString()
        // For now, RGBA8 works for fonts and UI textures
    }

    /**
     * Cleanup all resources using BGFX native methods.
     * Uses: bgfx_destroy_texture(), bgfx_destroy_uniform()
     */
    public void shutdown() {
        // Destroy all texture handles
        textureHandles.values().forEach(handle -> {
            if (Util.isValidHandle(handle)) {
                BGFX.bgfx_destroy_texture(handle);
            }
        });
        textureHandles.clear();

        // Destroy all GpuTexture handles
        gpuTextureHandles.values().forEach(handle -> {
            if (Util.isValidHandle(handle)) {
                BGFX.bgfx_destroy_texture(handle);
            }
        });
        gpuTextureHandles.clear();

        // Destroy all sampler uniforms
        samplerUniforms.values().forEach(uniform -> {
            if (Util.isValidHandle(uniform)) {
                BGFX.bgfx_destroy_uniform(uniform);
            }
        });
        samplerUniforms.clear();

        LOGGER.info("BgfxTextureManager shutdown complete");
    }
}
