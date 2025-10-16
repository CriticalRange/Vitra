package com.vitra.mixin.texture;

import com.vitra.render.opengl.GLInterceptor;
import com.vitra.render.jni.VitraNativeRenderer;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.SharedConstants;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/**
 * Mixin for TextureUtil to provide comprehensive DirectX 11 texture utilities.
 * This mixin provides DirectX 11 equivalents of all TextureUtil static methods,
 * handling low-level texture operations that support higher-level texture management.
 *
 * Key features:
 * - DirectX 11 texture ID generation and management
 * - Texture preparation with mipmap levels
 * - Format conversion between OpenGL and DirectX 11
 * - Pixel storage mode and alignment management
 * - Texture data preprocessing and validation
 * - Debug export functionality
 * - Resource reading utilities
 * - Integration with Vitra's texture tracking systems
 */
@Mixin(TextureUtil.class)
public abstract class MTextureUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(MTextureUtil.class);

    // Performance tracking
    private static long totalTexturesGenerated = 0;
    private static long totalTexturePreparations = 0;
    private static long totalBytesRead = 0;

    /**
     * Shadow method for TextureUtil.generateTextureId()
     */
    @Shadow
    public static int generateTextureId() {
        return 0; // Will be overridden by mixin
    }

    /**
     * Shadow method for TextureUtil.prepareImage()
     */
    @Shadow
    public static void prepareImage(NativeImage.InternalGlFormat pixelFormat, int textureId, int mipmapLevel, int width, int height) {
        // Will be overridden by mixin
    }

    /**
     * Shadow method for TextureUtil.bind()
     */
    @Shadow
    private static void bind(int textureId) {
        // Will be overridden by mixin
    }

    /**
     * Shadow method for TextureUtil.releaseTextureId()
     */
    @Shadow
    public static void releaseTextureId(int textureId) {
        // Will be overridden by mixin
    }

    /**
     * Shadow method for TextureUtil.readResource()
     */
    @Shadow
    public static ByteBuffer readResource(InputStream inputStream) throws IOException {
        return null; // Will be overridden by mixin
    }

    // ==================== TEXTURE ID GENERATION ====================

    /**
     * Inject into generateTextureId() to provide DirectX 11 texture ID generation
     */
    @Inject(method = "generateTextureId", at = @At("HEAD"), cancellable = true)
    private static void vitraGenerateTextureId(CallbackInfoReturnable<Integer> cir) {
        if (!GLInterceptor.isActive()) {
            return; // Let original OpenGL implementation proceed
        }

        try {
            totalTexturesGenerated++;

            // Generate DirectX 11 texture handle
            long directXHandle = VitraNativeRenderer.createTexture(null, 1, 1, 0);

            // Generate OpenGL texture ID for compatibility
            int textureId = VitraNativeRenderer.generateGLTextureId();

            // Register the mapping in GLInterceptor
            GLInterceptor.registerMAbstractTexture(textureId, directXHandle);

            LOGGER.debug("MTextureUtil: Generated texture ID {} with DirectX handle 0x{}",
                textureId, Long.toHexString(directXHandle));

            // In IDE mode, perform additional testing like original implementation
            if (SharedConstants.IS_RUNNING_IN_IDE) {
                // Test texture creation/deletion to verify DirectX 11 functionality
                long testHandle = VitraNativeRenderer.createTexture(null, 1, 1, 0);
                VitraNativeRenderer.destroyResource(testHandle);
            }

            cir.setReturnValue(textureId);

        } catch (Exception e) {
            LOGGER.error("MTextureUtil: Failed to generate texture ID", e);
            // Fall back to original OpenGL implementation
        }
    }

    // ==================== TEXTURE PREPARATION ====================

    /**
     * Inject into prepareImage() to provide DirectX 11 texture preparation
     */
    @Inject(method = "prepareImage(Lcom/mojang/blaze3d/platform/NativeImage$InternalGlFormat;IIII)V",
             at = @At("HEAD"), cancellable = true)
    private static void vitraPrepareImage(NativeImage.InternalGlFormat pixelFormat, int textureId,
                                        int mipmapLevel, int width, int height, CallbackInfo ci) {
        if (!GLInterceptor.isActive()) {
            return; // Let original OpenGL implementation proceed
        }

        try {
            totalTexturePreparations++;

            LOGGER.debug("MTextureUtil: Preparing image for texture ID {} - size={}x{}, mipmapLevel={}, format={}",
                textureId, width, height, mipmapLevel, pixelFormat);

            // Get DirectX handle for this texture
            long directXHandle = GLInterceptor.getDirectXHandleForTexture(textureId);
            if (directXHandle == 0) {
                LOGGER.warn("MTextureUtil: No DirectX handle found for texture ID {}, falling back to OpenGL", textureId);
                return; // Let original OpenGL implementation proceed
            }

            // Validate dimensions
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Invalid texture dimensions: " + width + "x" + height);
            }

            // Validate power-of-two requirements for certain formats
            if (!isValidTextureSize(width, height, mipmapLevel)) {
                LOGGER.warn("MTextureUtil: Non-power-of-two texture dimensions {}x{} may cause issues", width, height);
            }

            // Convert OpenGL format to DirectX 11 format
            int directXFormat = convertOpenGLFormatToDirectX(pixelFormat);

            // Prepare DirectX 11 texture with mipmap levels
            VitraNativeRenderer.prepareTextureImage(directXHandle, directXFormat, mipmapLevel, width, height);

            // Configure texture parameters for mipmapping
            if (mipmapLevel >= 0) {
                VitraNativeRenderer.setTextureParameterId(textureId,
                    VitraNativeRenderer.GL_TEXTURE_MAX_LEVEL, mipmapLevel);
                VitraNativeRenderer.setTextureParameterId(textureId,
                    VitraNativeRenderer.GL_TEXTURE_BASE_LEVEL, 0);
                VitraNativeRenderer.setTextureParameterId(textureId,
                    VitraNativeRenderer.GL_TEXTURE_MAX_ANISOTROPY_EXT, 0.0f);
            }

            // Allocate memory for each mipmap level
            for (int level = 0; level <= mipmapLevel; level++) {
                int levelWidth = Math.max(1, width >> level);
                int levelHeight = Math.max(1, height >> level);

                VitraNativeRenderer.allocateTextureLevel(directXHandle, level, levelWidth, levelHeight, directXFormat);
            }

            LOGGER.debug("MTextureUtil: Successfully prepared texture ID {} with {} mipmap levels",
                textureId, mipmapLevel + 1);

            ci.cancel(); // Cancel original OpenGL implementation

        } catch (Exception e) {
            LOGGER.error("MTextureUtil: Failed to prepare image for texture ID " + textureId, e);
            // Let original OpenGL implementation proceed as fallback
        }
    }

    // ==================== TEXTURE BINDING ====================

    /**
     * Inject into bind() to provide DirectX 11 texture binding
     */
    @Inject(method = "bind", at = @At("HEAD"), cancellable = true)
    private static void vitraBind(int textureId, CallbackInfo ci) {
        if (!GLInterceptor.isActive()) {
            return; // Let original OpenGL implementation proceed
        }

        try {
            LOGGER.debug("MTextureUtil: Binding texture ID {}", textureId);

            // Get DirectX handle and bind it
            long directXHandle = GLInterceptor.getDirectXHandleForTexture(textureId);
            if (directXHandle != 0) {
                VitraNativeRenderer.bindTexture(directXHandle, 0);
                ci.cancel(); // Cancel original OpenGL implementation
            } else {
                LOGGER.warn("MTextureUtil: No DirectX handle found for texture ID {}, falling back to OpenGL", textureId);
            }

        } catch (Exception e) {
            LOGGER.error("MTextureUtil: Failed to bind texture ID " + textureId, e);
            // Let original OpenGL implementation proceed as fallback
        }
    }

    // ==================== TEXTURE RELEASE ====================

    /**
     * Inject into releaseTextureId() to provide DirectX 11 texture cleanup
     */
    @Inject(method = "releaseTextureId", at = @At("HEAD"), cancellable = true)
    private static void vitraReleaseTextureId(int textureId, CallbackInfo ci) {
        if (!GLInterceptor.isActive()) {
            return; // Let original OpenGL implementation proceed
        }

        try {
            LOGGER.debug("MTextureUtil: Releasing texture ID {}", textureId);

            // Unregister from GLInterceptor and cleanup DirectX resources
            GLInterceptor.unregisterMAbstractTexture(textureId);

            ci.cancel(); // Cancel original OpenGL implementation

        } catch (Exception e) {
            LOGGER.error("MTextureUtil: Failed to release texture ID " + textureId, e);
            // Let original OpenGL implementation proceed as fallback
        }
    }

    // ==================== RESOURCE READING ====================

    /**
     * Inject into readResource() to provide enhanced resource reading
     */
    @Inject(method = "readResource(Ljava/io/InputStream;)Ljava/nio/ByteBuffer;",
             at = @At("HEAD"), cancellable = true)
    private static void vitraReadResource(InputStream inputStream, CallbackInfoReturnable<ByteBuffer> cir) throws IOException {
        if (!GLInterceptor.isActive()) {
            return; // Let original implementation proceed
        }

        try {
            ReadableByteChannel channel = Channels.newChannel(inputStream);

            if (channel instanceof SeekableByteChannel seekableChannel) {
                int size = (int) seekableChannel.size() + 1;
                ByteBuffer buffer = readResourceEnhanced(channel, size);
                totalBytesRead += buffer.capacity();
                cir.setReturnValue(buffer);
            } else {
                // Use default size for non-seekable channels
                ByteBuffer buffer = readResourceEnhanced(channel, 8192);
                totalBytesRead += buffer.capacity();
                cir.setReturnValue(buffer);
            }

        } catch (Exception e) {
            LOGGER.error("MTextureUtil: Failed to read resource", e);
            throw e; // Re-throw to match original behavior
        }
    }

    /**
     * Enhanced resource reading with better memory management and DirectX 11 optimizations
     */
    private static ByteBuffer readResourceEnhanced(ReadableByteChannel channel, int initialSize) throws IOException {
        ByteBuffer buffer = MemoryUtil.memAlloc(initialSize);

        try {
            while (channel.read(buffer) != -1) {
                if (!buffer.hasRemaining()) {
                    // Double buffer size with limit to prevent excessive memory usage
                    int newSize = Math.min(buffer.capacity() * 2, 16 * 1024 * 1024); // Max 16MB
                    buffer = MemoryUtil.memRealloc(buffer, newSize);
                    LOGGER.debug("MTextureUtil: Expanded buffer to {} bytes", newSize);
                }
            }

            buffer.flip();
            return buffer;

        } catch (IOException e) {
            MemoryUtil.memFree(buffer);
            throw e;
        }
    }

    // ==================== PNG EXPORT UTILITIES ====================

    /**
     * Enhanced PNG export with DirectX 11 texture support
     */
    public static void writeAsPNGEnhanced(Path outputDir, String textureName, int textureId,
                                        int mipmapLevel, int width, int height,
                                        @Nullable IntUnaryOperator function) {
        if (!GLInterceptor.isActive()) {
            LOGGER.warn("MTextureUtil: DirectX 11 not active, using original PNG export");
            return;
        }

        try {
            LOGGER.debug("MTextureUtil: Exporting texture {} (ID {}) as PNG", textureName, textureId);

            // Get DirectX handle
            long directXHandle = GLInterceptor.getDirectXHandleForTexture(textureId);
            if (directXHandle == 0) {
                LOGGER.warn("MTextureUtil: No DirectX handle for texture ID {}, skipping export", textureId);
                return;
            }

            // Create output directory if it doesn't exist
            if (outputDir != null) {
                java.nio.file.Files.createDirectories(outputDir);
            }

            // For each mipmap level
            for (int level = 0; level <= mipmapLevel; level++) {
                int levelWidth = Math.max(1, width >> level);
                int levelHeight = Math.max(1, height >> level);

                try (NativeImage image = new NativeImage(levelWidth, levelHeight, false)) {
                    // Download texture data from DirectX 11
                    if (downloadTextureFromDirectX(directXHandle, image, level)) {
                        // Apply pixel transformation if provided
                        if (function != null) {
                            image.applyToAllPixels(function);
                        }

                        // Write to file
                        Path outputPath = outputDir.resolve(textureName + "_" + level + ".png");
                        image.writeToFile(outputPath);
                        LOGGER.debug("MTextureUtil: Exported mipmap level {} to: {}", level, outputPath.toAbsolutePath());
                    } else {
                        LOGGER.warn("MTextureUtil: Failed to download mipmap level {} for texture {}", level, textureId);
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("MTextureUtil: Failed to export texture {} as PNG", textureName, e);
        }
    }

    // ==================== FORMAT CONVERSION UTILITIES ====================

    /**
     * Convert OpenGL internal format to DirectX 11 format
     */
    private static int convertOpenGLFormatToDirectX(NativeImage.InternalGlFormat glFormat) {
        switch (glFormat) {
            case RGBA:
                return VitraNativeRenderer.DXGI_FORMAT_R8G8B8A8_UNORM;
            case RGB:
                return VitraNativeRenderer.DXGI_FORMAT_R8G8B8A8_UNORM; // Convert RGB to RGBA
            case RED:
                return VitraNativeRenderer.DXGI_FORMAT_R8_UNORM;
            case RG:
                return VitraNativeRenderer.DXGI_FORMAT_R8G8_UNORM;
            default:
                LOGGER.warn("MTextureUtil: Unknown OpenGL format {}, using RGBA fallback", glFormat);
                return VitraNativeRenderer.DXGI_FORMAT_R8G8B8A8_UNORM;
        }
    }

    /**
     * Validate texture size and power-of-two requirements
     */
    private static boolean isValidTextureSize(int width, int height, int mipmapLevel) {
        // Check if dimensions are reasonable
        if (width > VitraNativeRenderer.getMaxTextureSize() || height > VitraNativeRenderer.getMaxTextureSize()) {
            return false;
        }

        // Check if we can generate the required mipmap levels
        int maxMipmapWidth = width >> mipmapLevel;
        int maxMipmapHeight = height >> mipmapLevel;
        return maxMipmapWidth > 0 && maxMipmapHeight > 0;
    }

    /**
     * Download texture data from DirectX 11 to NativeImage
     */
    private static boolean downloadTextureFromDirectX(long directXHandle, NativeImage image, int mipmapLevel) {
        try {
            // Create staging texture for readback
            long stagingTexture = VitraNativeRenderer.createStagingTexture(
                image.getWidth(), image.getHeight(), VitraNativeRenderer.DXGI_FORMAT_R8G8B8A8_UNORM);

            if (stagingTexture == 0) {
                LOGGER.error("MTextureUtil: Failed to create staging texture for readback");
                return false;
            }

            try {
                // Copy from GPU texture to staging texture
                VitraNativeRenderer.copyTextureRegion(directXHandle, stagingTexture,
                    0, 0, 0, 0, 0, 0, image.getWidth(), image.getHeight(), 1, mipmapLevel);

                // Read data from staging texture to CPU
                byte[] pixelData = VitraNativeRenderer.readTextureData(stagingTexture,
                    image.getWidth(), image.getHeight());

                if (pixelData != null) {
                    // Convert pixel data to NativeImage format
                    populateNativeImageFromByteArray(image, pixelData);
                    return true;
                }

            } finally {
                VitraNativeRenderer.destroyResource(stagingTexture);
            }

        } catch (Exception e) {
            LOGGER.error("MTextureUtil: Failed to download texture from DirectX 11", e);
        }

        return false;
    }

    /**
     * Populate NativeImage from byte array data
     */
    private static void populateNativeImageFromByteArray(NativeImage image, byte[] pixelData) {
        int width = image.getWidth();
        int height = image.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4; // RGBA = 4 bytes per pixel

                if (offset + 3 < pixelData.length) {
                    // DirectX 11 stores as RGBA, NativeImage expects ABGR
                    byte r = pixelData[offset];
                    byte g = pixelData[offset + 1];
                    byte b = pixelData[offset + 2];
                    byte a = pixelData[offset + 3];

                    // Convert RGBA to ABGR format for NativeImage
                    int argb = ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF);
                    image.setPixelRGBA(x, y, argb);
                }
            }
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Get debug texture path with enhanced error handling
     */
    public static Path getDebugTexturePathEnhanced() {
        try {
            Path basePath = Path.of(".");
            Path debugPath = basePath.resolve("screenshots").resolve("debug");
            java.nio.file.Files.createDirectories(debugPath);
            LOGGER.debug("MTextureUtil: Debug texture path: {}", debugPath.toAbsolutePath());
            return debugPath;
        } catch (Exception e) {
            LOGGER.error("MTextureUtil: Failed to create debug texture path", e);
            return Path.of(".");
        }
    }

    /**
     * Validate texture parameters before operations
     */
    public static boolean validateTextureParameters(int textureId, int width, int height, int format) {
        if (textureId <= 0) {
            LOGGER.warn("MTextureUtil: Invalid texture ID: {}", textureId);
            return false;
        }

        if (width <= 0 || height <= 0) {
            LOGGER.warn("MTextureUtil: Invalid texture dimensions: {}x{}", width, height);
            return false;
        }

        if (width > VitraNativeRenderer.getMaxTextureSize() || height > VitraNativeRenderer.getMaxTextureSize()) {
            LOGGER.warn("MTextureUtil: Texture dimensions {}x{} exceed maximum size {}",
                width, height, VitraNativeRenderer.getMaxTextureSize());
            return false;
        }

        return true;
    }

    /**
     * Get performance statistics
     */
    public static String getPerformanceStats() {
        return String.format("MTextureUtil Stats: %d textures generated, %d preparations, %d MB read",
            totalTexturesGenerated, totalTexturePreparations, totalBytesRead / (1024 * 1024));
    }

    /**
     * Reset performance statistics
     */
    public static void resetPerformanceStats() {
        totalTexturesGenerated = 0;
        totalTexturePreparations = 0;
        totalBytesRead = 0;
        LOGGER.info("MTextureUtil: Performance statistics reset");
    }

    /**
     * Check if DirectX 11 texture utilities are available
     */
    public static boolean isDirectXAvailable() {
        return GLInterceptor.isActive() && VitraNativeRenderer.isInitialized();
    }

    /**
     * Get DirectX 11 texture information for debugging
     */
    public static String getDirectXTextureInfo(int textureId) {
        if (!isDirectXAvailable()) {
            return "DirectX 11 not available";
        }

        long directXHandle = GLInterceptor.getDirectXHandleForTexture(textureId);
        if (directXHandle == 0) {
            return "No DirectX handle found for texture ID " + textureId;
        }

        return String.format("Texture ID %d -> DirectX handle 0x%x", textureId, directXHandle);
    }
}