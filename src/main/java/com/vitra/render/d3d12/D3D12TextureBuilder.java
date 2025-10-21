package com.vitra.render.d3d12;

import com.vitra.render.jni.VitraD3D12Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DirectX 12 Texture Builder inspired by VulkanMod's VulkanImage.Builder
 * Provides flexible texture creation with proper D3D12 resource state management
 */
public class D3D12TextureBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vitra/D3D12TextureBuilder");

    // DXGI Texture Formats
    public static final int DXGI_FORMAT_R8G8B8A8_UNORM = 87;
    public static final int DXGI_FORMAT_R8G8B8A8_UNORM_SRGB = 90;
    public static final int DXGI_FORMAT_B8G8R8A8_UNORM = 87; // Actually 87, corrected below
    public static final int DXGI_FORMAT_B8G8R8A8_UNORM_SRGB = 91;
    public static final int DXGI_FORMAT_R32_FLOAT = 41;
    public static final int DXGI_FORMAT_D32_FLOAT = 126;
    public static final int DXGI_FORMAT_D24_UNORM_S8_UINT = 129;
    public static final int DXGI_FORMAT_R16G16B16A16_FLOAT = 54;
    public static final int DXGI_FORMAT_R11G11B10_FLOAT = 50;
    public static final int DXGI_FORMAT_BC1_UNORM = 71;
    public static final int DXGI_FORMAT_BC1_UNORM_SRGB = 72;
    public static final int DXGI_FORMAT_BC2_UNORM = 74;
    public static final int DXGI_FORMAT_BC2_UNORM_SRGB = 75;
    public static final int DXGI_FORMAT_BC3_UNORM = 77;
    public static final int DXGI_FORMAT_BC3_UNORM_SRGB = 78;
    public static final int DXGI_FORMAT_BC4_UNORM = 80;
    public static final int DXGI_FORMAT_BC5_UNORM = 83;
    public static final int DXGI_FORMAT_BC6H_UF16 = 95;
    public static final int DXGI_FORMAT_BC7_UNORM = 98;
    public static final int DXGI_FORMAT_BC7_UNORM_SRGB = 99;

    // D3D12 Resource States
    public static final int D3D12_RESOURCE_STATE_COMMON = 0;
    public static final int D3D12_RESOURCE_STATE_VERTEX_AND_CONSTANT_BUFFER = 0x1;
    public static final int D3D12_RESOURCE_STATE_INDEX_BUFFER = 0x2;
    public static final int D3D12_RESOURCE_STATE_CONSTANT_BUFFER = 0x4;
    public static final int D3D12_RESOURCE_STATE_SHADER_RESOURCE = 0x2;
    public static final int D3D12_RESOURCE_STATE_STREAM_OUT = 0x4;
    public static final int D3D12_RESOURCE_STATE_INDIRECT_ARGUMENT = 0x4;
    public static final int D3D12_RESOURCE_STATE_COPY_DEST = 0x4;
    public static final int D3D12_RESOURCE_STATE_COPY_SOURCE = 0x1;
    public static final int D3D12_RESOURCE_STATE_RESOLVE_DEST = 0x4;
    public static final int D3D12_RESOURCE_STATE_UNORDERED_ACCESS = 0x8;
    public static final int D3D12_RESOURCE_STATE_DEPTH_WRITE = 0x20;
    public static final int D3D12_RESOURCE_STATE_DEPTH_READ = 0x20 | 0x2;
    public static final int D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE = 0x2;
    public static final int D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE = 0x2;
    public static final int D3D12_RESOURCE_STATE_STREAM_OUT = 0x4;
    public static final int D3D12_RESOURCE_STATE_RENDER_TARGET = 0x4;
    public static final int D3D12_RESOURCE_STATE_PRESENT = 0;
    public static final int D3D12_RESOURCE_STATE_PREDICATION = 0x1;

    // D3D12 Resource Flags
    public static final int D3D12_RESOURCE_FLAG_NONE = 0x0;
    public static final int D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET = 0x400;
    public static final int D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL = 0x20;
    public static final int D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS = 0x200;
    public static final int D3D12_RESOURCE_FLAG_DENY_SHADER_RESOURCE = 0x80;
    public static final int D3D12_RESOURCE_FLAG_ALLOW_CROSS_ADAPTER = 0x1000;
    public static final int D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS = 0x2000;
    public static final int D3D12_RESOURCE_FLAG_VIDEO_DECODE_READ_ONLY = 0x40000;

    // D3D12MA Heap Types
    public static final int D3D12_HEAP_TYPE_DEFAULT = D3D12MemoryManager.HEAP_TYPE_DEFAULT;
    public static final int D3D12_HEAP_TYPE_UPLOAD = D3D12MemoryManager.HEAP_TYPE_UPLOAD;
    public static final int D3D12_HEAP_TYPE_READBACK = D3D12MemoryManager.HEAP_TYPE_READBACK;

    // Texture properties
    private int width = 1;
    private int height = 1;
    private int depth = 1;
    private int mipLevels = 1;
    private int arraySize = 1;
    private int format = DXGI_FORMAT_R8G8B8A8_UNORM;
    private int sampleCount = 1;
    private int sampleQuality = 0;

    // Resource state and flags
    private int initialState = D3D12_RESOURCE_STATE_COPY_DEST;
    private int resourceFlags = D3D12_RESOURCE_FLAG_NONE;
    private int heapType = D3D12_HEAP_TYPE_DEFAULT;
    private int allocationFlags = D3D12MemoryManager.ALLOC_FLAG_NONE;

    // Texture data
    private byte[] initialData;
    private boolean generateMipmaps = false;
    private boolean isCubemap = false;
    private boolean isRenderTarget = false;
    private boolean isDepthStencil = false;
    private boolean isUnorderedAccess = false;

    // Debug and naming
    private String debugName;
    private long memoryManager;

    public D3D12TextureBuilder() {
        // Default configuration for Minecraft textures
        this.format = DXGI_FORMAT_R8G8B8A8_UNORM;
        this.heapType = D3D12_HEAP_TYPE_DEFAULT;
        this.initialState = D3D12_RESOURCE_STATE_COPY_DEST;
    }

    /**
     * Set texture dimensions
     */
    public D3D12TextureBuilder setDimensions(int width, int height) {
        return setDimensions(width, height, 1);
    }

    public D3D12TextureBuilder setDimensions(int width, int height, int depth) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.depth = Math.max(1, depth);
        return this;
    }

    /**
     * Set texture format
     */
    public D3D12TextureBuilder setFormat(int format) {
        this.format = format;
        return this;
    }

    /**
     * Set mip levels
     */
    public D3D12TextureBuilder setMipLevels(int mipLevels) {
        this.mipLevels = Math.max(1, mipLevels);
        return this;
    }

    /**
     * Set array size (for arrays and cubemaps)
     */
    public D3D12TextureBuilder setArraySize(int arraySize) {
        this.arraySize = Math.max(1, arraySize);
        return this;
    }

    /**
     * Enable/disable mipmap generation
     */
    public D3D12TextureBuilder setMipmapGeneration(boolean generateMipmaps) {
        this.generateMipmaps = generateMipmaps;
        if (generateMipmaps && this.mipLevels == 1) {
            this.mipLevels = calculateMaxMipLevels();
        }
        return this;
    }

    /**
     * Set as cubemap
     */
    public D3D12TextureBuilder setCubemap(boolean isCubemap) {
        this.isCubemap = isCubemap;
        if (isCubemap) {
            this.arraySize = 6; // Cubemap has 6 faces
        }
        return this;
    }

    /**
     * Set initial texture data
     */
    public D3D12TextureBuilder setData(byte[] data) {
        this.initialData = data;
        return this;
    }

    /**
     * Set resource flags for specific usage
     */
    public D3D12TextureBuilder setResourceFlags(int flags) {
        this.resourceFlags = flags;
        return this;
    }

    public D3D12TextureBuilder enableRenderTarget(boolean enable) {
        if (enable) {
            this.resourceFlags |= D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
            this.initialState = D3D12_RESOURCE_STATE_RENDER_TARGET;
        } else {
            this.resourceFlags &= ~D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET;
        }
        this.isRenderTarget = enable;
        return this;
    }

    public D3D12TextureBuilder enableDepthStencil(boolean enable) {
        if (enable) {
            this.resourceFlags |= D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL;
            this.initialState = D3D12_RESOURCE_STATE_DEPTH_WRITE;
        } else {
            this.resourceFlags &= ~D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL;
        }
        this.isDepthStencil = enable;
        return this;
    }

    public D3D12TextureBuilder enableUnorderedAccess(boolean enable) {
        if (enable) {
            this.resourceFlags |= D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
            this.initialState = D3D12_RESOURCE_STATE_UNORDERED_ACCESS;
        } else {
            this.resourceFlags &= ~D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
        }
        this.isUnorderedAccess = enable;
        return this;
    }

    /**
     * Set heap type for memory allocation
     */
    public D3D12TextureBuilder setHeapType(int heapType) {
        this.heapType = heapType;
        return this;
    }

    /**
     * Set allocation flags for D3D12MA
     */
    public D3D12TextureBuilder setAllocationFlags(int flags) {
        this.allocationFlags = flags;
        return this;
    }

    /**
     * Set initial resource state
     */
    public D3D12TextureBuilder setInitialState(int state) {
        this.initialState = state;
        return this;
    }

    /**
     * Set debug name for the texture
     */
    public D3D12TextureBuilder setDebugName(String name) {
        this.debugName = name;
        return this;
    }

    /**
     * Set memory manager for the texture
     */
    public D3D12TextureBuilder setMemoryManager(long memoryManager) {
        this.memoryManager = memoryManager;
        return this;
    }

    /**
     * Configure for Minecraft texture
     */
    public D3D12TextureBuilder forMinecraftTexture() {
        return setFormat(DXGI_FORMAT_R8G8B8A8_UNORM)
                .setMipmapGeneration(true)
                .enableUnorderedAccess(false)
                .enableRenderTarget(false)
                .enableDepthStencil(false);
    }

    /**
     * Configure for render target
     */
    public D3D12TextureBuilder forRenderTarget() {
        return enableRenderTarget(true)
                .setMipmapGeneration(false)
                .setInitialState(D3D12_RESOURCE_STATE_RENDER_TARGET);
    }

    /**
     * Configure for depth buffer
     */
    public D3D12TextureBuilder forDepthBuffer() {
        return setFormat(DXGI_FORMAT_D32_FLOAT)
                .enableDepthStencil(true)
                .setMipmapGeneration(false)
                .setInitialState(D3D12_RESOURCE_STATE_DEPTH_WRITE);
    }

    /**
     * Configure for unordered access view (UAV)
     */
    public D3D12TextureBuilder forUAV() {
        return enableUnorderedAccess(true)
                .setMipmapGeneration(false)
                .setInitialState(D3D12_RESOURCE_STATE_UNORDERED_ACCESS);
    }

    /**
     * Build the texture
     */
    public D3D12Texture build() {
        validateConfiguration();

        long handle;
        if (memoryManager != 0) {
            // Use memory manager
            handle = VitraD3D12Native.createManagedTexture(
                initialData, width, height, format, heapType, allocationFlags);
        } else {
            // Use direct native call
            handle = VitraD3D12Native.createTexture(width, height, format, heapType, initialData);
        }

        if (handle != 0) {
            D3D12Texture texture = new D3D12Texture(handle, width, height, format, this);

            // Set debug name if provided
            if (debugName != null && memoryManager != 0) {
                VitraD3D12Native.setResourceDebugName(handle, debugName);
            }

            // Generate mipmaps if requested
            if (generateMipmaps && mipLevels > 1) {
                VitraD3D12Native.generateMipmaps(handle);
            }

            LOGGER.debug("Created texture: {}x{}, format={}, handle={}, mips={}",
                width, height, formatName(format), Long.toHexString(handle), mipLevels);

            return texture;
        } else {
            LOGGER.error("Failed to create texture: {}x{}, format={}", width, height, formatName(format));
            return null;
        }
    }

    /**
     * Validate configuration before building
     */
    private void validateConfiguration() {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Texture dimensions must be positive");
        }

        if (mipLevels < 1 || mipLevels > calculateMaxMipLevels()) {
            throw new IllegalArgumentException("Invalid mip level count: " + mipLevels);
        }

        if (isCubemap && arraySize < 6) {
            throw new IllegalArgumentException("Cubemap requires array size of at least 6");
        }

        // Validate format and flags combination
        if (isDepthStencil && !isDepthFormat(format)) {
            LOGGER.warn("Using depth stencil flags with non-depth format: {}", formatName(format));
        }

        if (isRenderTarget && isDepthFormat(format)) {
            LOGGER.warn("Using render target flags with depth format: {}", formatName(format));
        }
    }

    /**
     * Calculate maximum mip levels for current dimensions
     */
    private int calculateMaxMipLevels() {
        int maxSize = Math.max(Math.max(width, height), depth);
        return Integer.SIZE - Integer.numberOfLeadingZeros(maxSize);
    }

    /**
     * Check if format is a depth format
     */
    private boolean isDepthFormat(int format) {
        return format == DXGI_FORMAT_D32_FLOAT || format == DXGI_FORMAT_D24_UNORM_S8_UINT;
    }

    /**
     * Get format name for logging
     */
    private static String formatName(int format) {
        switch (format) {
            case DXGI_FORMAT_R8G8B8A8_UNORM: return "R8G8B8A8_UNORM";
            case DXGI_FORMAT_R8G8B8A8_UNORM_SRGB: return "R8G8B8A8_UNORM_SRGB";
            case DXGI_FORMAT_B8G8R8A8_UNORM: return "B8G8R8A8_UNORM";
            case DXGI_FORMAT_B8G8R8A8_UNORM_SRGB: return "B8G8R8A8_UNORM_SRGB";
            case DXGI_FORMAT_R32_FLOAT: return "R32_FLOAT";
            case DXGI_FORMAT_D32_FLOAT: return "D32_FLOAT";
            case DXGI_FORMAT_D24_UNORM_S8_UINT: return "D24_UNORM_S8_UINT";
            case DXGI_FORMAT_BC1_UNORM: return "BC1_UNORM";
            case DXGI_FORMAT_BC1_UNORM_SRGB: return "BC1_UNORM_SRGB";
            case DXGI_FORMAT_R11G11B10_FLOAT: return "R11G11B10_FLOAT";
            default: return "UNKNOWN(" + format + ")";
        }
    }

    /**
     * Get format size in bytes per pixel
     */
    public static int getFormatSize(int format) {
        switch (format) {
            case DXGI_FORMAT_R8G8B8A8_UNORM:
            case DXGI_FORMAT_R8G8B8A8_UNORM_SRGB:
            case DXGI_FORMAT_B8G8R8A8_UNORM:
            case DXGI_FORMAT_B8G8R8A8_UNORM_SRGB:
            case DXGI_FORMAT_BC1_UNORM:
            case DXGI_FORMAT_BC1_UNORM_SRGB:
            case DXGI_FORMAT_BC4_UNORM:
                return 4;

            case DXGI_FORMAT_R32_FLOAT:
            case DXGI_FORMAT_D32_FLOAT:
                return 4;

            case DXGI_FORMAT_R11G11B10_FLOAT:
                return 4;

            case DXGI_FORMAT_R16G16B16A16_FLOAT:
            case DXGI_FORMAT_BC5_UNORM:
                return 8;

            case DXGI_FORMAT_BC2_UNORM:
            case DXGI_FORMAT_BC2_UNORM_SRGB:
            case DXGI_FORMAT_BC3_UNORM:
            case DXGI_FORMAT_BC3_UNORM_SRGB:
            case DXGI_FORMAT_BC6H_UF16:
                return 16;

            case DXGI_FORMAT_BC7_UNORM:
            case DXGI_FORMAT_BC7_UNORM_SRGB:
                return 8;

            case DXGI_FORMAT_D24_UNORM_S8_UINT:
                return 4;

            default:
                return 4; // Default assumption
        }
    }

    /**
     * Check if format is block compressed
     */
    public static boolean isBlockCompressed(int format) {
        switch (format) {
            case DXGI_FORMAT_BC1_UNORM:
            case DXGI_FORMAT_BC1_UNORM_SRGB:
            case DXGI_FORMAT_BC2_UNORM:
            case DXGI_FORMAT_BC2_UNORM_SRGB:
            case DXGI_FORMAT_BC3_UNORM:
            case DXGI_FORMAT_BC3_UNORM_SRGB:
            case DXGI_FORMAT_BC4_UNORM:
            case DXGI_FORMAT_BC5_UNORM:
            case DXGI_FORMAT_BC6H_UF16:
            case DXGI_FORMAT_BC7_UNORM:
            case DXGI_FORMAT_BC7_UNORM_SRGB:
                return true;

            default:
                return false;
        }
    }

    /**
     * Get block size for compressed formats
     */
    public static int getBlockSize(int format) {
        if (!isBlockCompressed(format)) {
            return 1;
        }

        switch (format) {
            case DXGI_FORMAT_BC1_UNORM:
            case DXGI_FORMAT_BC1_UNORM_SRGB:
            case DXGI_FORMAT_BC4_UNORM:
                return 8; // 4x4 block, 1 byte per pixel on average

            case DXGI_FORMAT_BC2_UNORM:
            case DXGI_FORMAT_BC2_UNORM_SRGB:
            case DXGI_FORMAT_BC3_UNORM:
            case DXGI_FORMAT_BC3_UNORM_SRGB:
            case DXGI_FORMAT_BC5_UNORM:
            case DXGI_FORMAT_BC6H_UF16:
            case DXGI_FORMAT_BC7_UNORM:
            case DXGI_FORMAT_BC7_UNORM_SRGB:
                return 16; // 4x4 block, 1 byte per pixel on average

            default:
                return 8;
        }
    }

    // Getters for introspection
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    public int getMipLevels() { return mipLevels; }
    public int getArraySize() { return arraySize; }
    public int getFormat() { return format; }
    public int getResourceFlags() { return resourceFlags; }
    public int getHeapType() { return heapType; }
    public int getInitialState() { return initialState; }
    public boolean isCubemap() { return isCubemap; }
    public boolean isRenderTarget() { return isRenderTarget; }
    public boolean isDepthStencil() { return isDepthStencil; }
    public boolean isUnorderedAccess() { return isUnorderedAccess; }
    public boolean isMipmapGenerationEnabled() { return generateMipmaps; }
    public String getDebugName() { return debugName; }
}