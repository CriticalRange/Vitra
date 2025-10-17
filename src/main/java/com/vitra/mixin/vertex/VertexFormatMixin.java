package com.vitra.mixin.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.vitra.render.jni.VitraNativeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * DirectX 11 VertexFormat mixin
 *
 * Based on VulkanMod's VertexFormatMixin but adapted for DirectX 11 backend.
 * Optimizes vertex format operations for DirectX 11 input layout creation
 * and vertex buffer management.
 *
 * Key responsibilities:
 * - Vertex format element access optimization
 * - DirectX 11 input layout creation and caching
 * - Vertex size calculation for DirectX 11 buffer operations
 * - Format compatibility validation for DirectX 11
 * - Fast vertex format element lookup
 */
@Mixin(VertexFormat.class)
public abstract class VertexFormatMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("VertexFormatMixin");

    @Shadow public abstract List<VertexFormatElement> getElements();
    @Shadow public abstract int getVertexSize();
    @Shadow public abstract int getOffset(VertexFormatElement element);
    @Shadow public abstract boolean contains(VertexFormatElement element);

    // DirectX 11 optimization data
    @Unique private long directXInputLayoutHandle = 0;
    @Unique private boolean isInputLayoutCreated = false;
    @Unique private int directXVertexSize = 0;
    @Unique private int directXFormatHash = 0;

    /**
     * @author Vitra
     * @reason Create DirectX 11 input layout for vertex format
     *
     * Creates and caches DirectX 11 input layout for this vertex format.
     * Essential for efficient vertex buffer rendering in DirectX 11.
     */
    @Overwrite
    public void setupBufferState() {
        try {
            // Calculate format hash for caching
            int currentHash = calculateFormatHash();

            if (directXFormatHash != currentHash || !isInputLayoutCreated) {
                LOGGER.debug("Creating DirectX 11 input layout for vertex format (hash: {})", currentHash);

                // Create DirectX 11 input layout
                directXInputLayoutHandle = createDirectXInputLayout();

                if (directXInputLayoutHandle != 0) {
                    isInputLayoutCreated = true;
                    directXFormatHash = currentHash;

                    // Calculate DirectX 11 vertex size (may differ from OpenGL)
                    directXVertexSize = calculateDirectXVertexSize();

                    LOGGER.debug("Created DirectX 11 input layout: handle=0x{}, vertexSize={}",
                        Long.toHexString(directXInputLayoutHandle), directXVertexSize);
                } else {
                    LOGGER.error("Failed to create DirectX 11 input layout for vertex format");
                }
            }

        } catch (Exception e) {
            LOGGER.error("Exception setting up DirectX 11 buffer state", e);
        }
    }

    /**
     * Get DirectX 11 optimized vertex size
     *
     * Returns vertex size optimized for DirectX 11 buffer layout.
     * May differ from OpenGL vertex size due to alignment requirements.
     */
    @Unique
    public int getDirectXVertexSize() {
        // Return DirectX 11 optimized size if available
        return directXVertexSize > 0 ? directXVertexSize : getVertexSize();
    }

    /**
     * Get element by index with DirectX 11 optimization
     *
     * Fast element lookup optimized for DirectX 11 input layout access.
     */
    @Unique
    public VertexFormatElement getDirectXElement(int index) {
        if (index < 0 || index >= getElements().size()) {
            throw new IndexOutOfBoundsException("Vertex format element index out of bounds: " + index);
        }

        VertexFormatElement element = getElements().get(index);

        // Validate element compatibility with DirectX 11
        validateDirectXCompatibility(element);

        return element;
    }

    /**
     * Get element offset optimized for DirectX 11
     *
     * Returns byte offset of element optimized for DirectX 11 buffer layout.
     */
    @Unique
    public int getDirectXOffset(int elementIndex) {
        if (elementIndex < 0 || elementIndex >= getElements().size()) {
            throw new IndexOutOfBoundsException("Vertex format element index out of bounds: " + elementIndex);
        }

        int offset = 0;
        for (int i = 0; i < elementIndex; i++) {
            offset += getDirectXElementSize(getElements().get(i));
        }

        return offset;
    }

    /**
     * Create DirectX 11 input layout for this vertex format
     */
    @Unique
    private long createDirectXInputLayout() {
        try {
            // Create input layout description from vertex format elements
            int[] elementDescriptions = createInputLayoutElements();

            // Create input layout through native method
            return VitraNativeRenderer.createInputLayout(elementDescriptions, getElements().size());

        } catch (Exception e) {
            LOGGER.error("Exception creating DirectX 11 input layout", e);
            return 0;
        }
    }

    /**
     * Create input layout element descriptions
     */
    @Unique
    private int[] createInputLayoutElements() {
        int[] descriptions = new int[getElements().size() * 5]; // 5 integers per element

        for (int i = 0; i < getElements().size(); i++) {
            VertexFormatElement element = getElements().get(i);
            int baseIndex = i * 5;

            // Convert element to DirectX 11 input layout description
            descriptions[baseIndex] = convertToDirectXFormat(element.type(), element.usage());
            descriptions[baseIndex + 1] = element.index();
            descriptions[baseIndex + 2] = getDirectXElementSize(element);
            descriptions[baseIndex + 3] = getOffset(element);
            descriptions[baseIndex + 4] = element.count();
        }

        return descriptions;
    }

    /**
     * Convert vertex format element to DirectX 11 format
     */
    @Unique
    private int convertToDirectXFormat(VertexFormatElement.Type type, VertexFormatElement.Usage usage) {
        return switch (type) {
            case FLOAT -> switch (usage) {
                case POSITION -> VitraNativeRenderer.DXGI_FORMAT_R32G32B32_FLOAT;
                case COLOR -> VitraNativeRenderer.DXGI_FORMAT_R32G32B32A32_FLOAT;
                case UV -> VitraNativeRenderer.DXGI_FORMAT_R32G32_FLOAT;
                case NORMAL -> VitraNativeRenderer.DXGI_FORMAT_R32G32B32_FLOAT;
                case GENERIC -> VitraNativeRenderer.DXGI_FORMAT_R32G32B32A32_FLOAT;
                default -> VitraNativeRenderer.DXGI_FORMAT_R32G32B32A32_FLOAT;
            };
            case UBYTE -> VitraNativeRenderer.DXGI_FORMAT_R8G8B8A8_UNORM;
            case BYTE -> VitraNativeRenderer.DXGI_FORMAT_R8G8B8A8_SNORM;
            case USHORT -> VitraNativeRenderer.DXGI_FORMAT_R16G16B16A16_UNORM;
            case SHORT -> VitraNativeRenderer.DXGI_FORMAT_R16G16B16A16_SNORM;
            case UINT -> VitraNativeRenderer.DXGI_FORMAT_R32G32B32A32_UINT;
            case INT -> VitraNativeRenderer.DXGI_FORMAT_R32G32B32A32_SINT;
            default -> VitraNativeRenderer.DXGI_FORMAT_R32G32B32A32_FLOAT;
        };
    }

    /**
     * Get DirectX 11 element size in bytes
     */
    @Unique
    private int getDirectXElementSize(VertexFormatElement element) {
        return switch (element.type()) {
            case FLOAT -> element.count() * 4; // 4 bytes per float
            case UBYTE, BYTE -> element.count() * 1; // 1 byte per component
            case USHORT, SHORT -> element.count() * 2; // 2 bytes per component
            case UINT, INT -> element.count() * 4; // 4 bytes per component
            default -> element.count() * 4; // Default to 4 bytes
        };
    }

    /**
     * Calculate DirectX 11 vertex size with alignment
     */
    @Unique
    private int calculateDirectXVertexSize() {
        int totalSize = 0;

        for (VertexFormatElement element : getElements()) {
            int elementSize = getDirectXElementSize(element);

            // Apply DirectX 11 alignment rules (typically 4-byte alignment)
            totalSize = alignTo(totalSize, 4);
            totalSize += elementSize;
        }

        // Final alignment for the entire vertex
        return alignTo(totalSize, 4);
    }

    /**
     * Align value to boundary
     */
    @Unique
    private int alignTo(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    /**
     * Calculate format hash for caching
     */
    @Unique
    private int calculateFormatHash() {
        int hash = 17;

        for (VertexFormatElement element : getElements()) {
            hash = hash * 31 + element.type().ordinal();
            hash = hash * 31 + element.usage().ordinal();
            hash = hash * 31 + element.index();
            hash = hash * 31 + element.count();
        }

        return hash;
    }

    /**
     * Validate element compatibility with DirectX 11
     */
    @Unique
    private void validateDirectXCompatibility(VertexFormatElement element) {
        // Check for unusual element combinations
        if (element.usage() == VertexFormatElement.Usage.NORMAL &&
            element.count() != 3) {
            LOGGER.warn("Non-standard normal element format: {} components", element.count());
        }
    }

    /**
     * Get DirectX 11 input layout handle
     */
    @Unique
    public long getDirectXInputLayoutHandle() {
        return directXInputLayoutHandle;
    }

    /**
     * Check if DirectX 11 input layout is created
     */
    @Unique
    public boolean isDirectXInputLayoutCreated() {
        return isInputLayoutCreated;
    }

    /**
     * Force recreation of DirectX 11 input layout
     */
    @Unique
    public void recreateDirectXInputLayout() {
        LOGGER.debug("Recreating DirectX 11 input layout");

        // Release old input layout
        if (directXInputLayoutHandle != 0) {
            VitraNativeRenderer.releaseInputLayout(directXInputLayoutHandle);
            directXInputLayoutHandle = 0;
        }

        isInputLayoutCreated = false;
        directXFormatHash = 0;

        // Create new input layout
        setupBufferState();
    }

    /**
     * Validate vertex format state
     */
    @Unique
    public boolean validateVertexFormatState() {
        if (getElements().isEmpty()) {
            LOGGER.warn("Vertex format has no elements");
            return false;
        }

        if (!isInputLayoutCreated) {
            LOGGER.warn("DirectX 11 input layout not created");
            return false;
        }

        if (directXInputLayoutHandle == 0) {
            LOGGER.warn("DirectX 11 input layout handle is null");
            return false;
        }

        return true;
    }

    /**
     * Get vertex format information for debugging
     */
    @Unique
    public String getVertexFormatInfo() {
        StringBuilder info = new StringBuilder();
        info.append("VertexFormat[elements=").append(getElements().size());
        info.append(", vertexSize=").append(getVertexSize());
        info.append(", directXVertexSize=").append(directXVertexSize);
        info.append(", inputLayoutHandle=0x").append(Long.toHexString(directXInputLayoutHandle));
        info.append(", inputLayoutCreated=").append(isInputLayoutCreated);
        info.append(", formatHash=").append(directXFormatHash);
        info.append("]");

        return info.toString();
    }

    /**
     * Cleanup DirectX 11 resources
     */
    @Unique
    public void cleanupDirectXResources() {
        if (directXInputLayoutHandle != 0) {
            VitraNativeRenderer.releaseInputLayout(directXInputLayoutHandle);
            directXInputLayoutHandle = 0;
        }

        isInputLayoutCreated = false;
        directXFormatHash = 0;
        directXVertexSize = 0;
    }
}