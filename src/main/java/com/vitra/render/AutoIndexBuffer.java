package com.vitra.render;

import com.vitra.render.jni.VitraD3D11Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Auto-Index Buffer Generator - Pre-generates index buffers for primitive conversion
 *
 * Based on VulkanMod's AutoIndexBuffer pattern.
 * Converts draw modes that don't map directly to DirectX primitives:
 * - QUADS → indexed TRIANGLES (pattern: 0,1,2, 2,3,0 for each quad)
 * - TRIANGLE_FAN → indexed TRIANGLES
 * - TRIANGLE_STRIP → indexed TRIANGLES
 * - LINES → indexed LINES
 *
 * Why needed:
 * - DirectX 11 doesn't support QUADS, TRIANGLE_FAN draw modes
 * - Most Minecraft UI uses QUADS (buttons, panels, backgrounds)
 * - Pre-generating index patterns is more efficient than per-draw generation
 *
 * Pattern example (QUADS):
 * Vertices: [v0, v1, v2, v3, v4, v5, v6, v7, ...]
 * Indices:  [0,1,2, 2,3,0, 4,5,6, 6,7,4, ...]
 *
 * Architecture:
 * - One singleton buffer per draw mode type
 * - Grows dynamically as needed (powers of 2)
 * - Uses 16-bit indices up to 65536 vertices, then switches to 32-bit
 * - GPU buffers managed by D3D11BufferManager
 */
public class AutoIndexBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoIndexBuffer.class);

    // Singleton instances for each draw mode
    private static AutoIndexBuffer quadsBuffer;
    private static AutoIndexBuffer triangleFanBuffer;
    private static AutoIndexBuffer triangleStripBuffer;
    private static AutoIndexBuffer linesBuffer;
    private static AutoIndexBuffer debugLineStripBuffer;

    // Buffer state
    private final DrawModeType type;
    private long indexBufferHandle = 0;
    private int maxVertexCount = 0;        // Current buffer capacity (in vertices)
    private boolean use32BitIndices = false;  // Use 32-bit indices for large buffers

    // Constants
    private static final int INITIAL_VERTEX_CAPACITY = 256;  // Start small, grow as needed
    private static final int MAX_16BIT_VERTICES = 65536;     // Max vertices for 16-bit indices

    /**
     * Draw mode types for auto-indexing
     */
    public enum DrawModeType {
        QUADS(6, 4),              // 6 indices per 4 vertices (2 triangles per quad)
        TRIANGLE_FAN(3, 1),       // 3 indices per 1 vertex (after first 2)
        TRIANGLE_STRIP(3, 1),     // 3 indices per 1 vertex (after first 2)
        LINES(2, 2),              // 2 indices per 2 vertices (1:1 mapping)
        DEBUG_LINE_STRIP(2, 1);   // 2 indices per 1 vertex (after first)

        public final int indicesPerPrimitive;
        public final int verticesPerPrimitive;

        DrawModeType(int indicesPerPrimitive, int verticesPerPrimitive) {
            this.indicesPerPrimitive = indicesPerPrimitive;
            this.verticesPerPrimitive = verticesPerPrimitive;
        }
    }

    private AutoIndexBuffer(DrawModeType type) {
        this.type = type;
    }

    // ==================== PUBLIC API ====================

    /**
     * Get QUADS auto-index buffer
     * Converts quad vertices to triangle indices: [0,1,2, 2,3,0, 4,5,6, 6,7,4, ...]
     */
    public static AutoIndexBuffer getQuadsBuffer() {
        if (quadsBuffer == null) {
            quadsBuffer = new AutoIndexBuffer(DrawModeType.QUADS);
            quadsBuffer.ensureCapacity(INITIAL_VERTEX_CAPACITY);
            LOGGER.info("Created QUADS auto-index buffer");
        }
        return quadsBuffer;
    }

    /**
     * Get TRIANGLE_FAN auto-index buffer
     */
    public static AutoIndexBuffer getTriangleFanBuffer() {
        if (triangleFanBuffer == null) {
            triangleFanBuffer = new AutoIndexBuffer(DrawModeType.TRIANGLE_FAN);
            triangleFanBuffer.ensureCapacity(INITIAL_VERTEX_CAPACITY);
            LOGGER.info("Created TRIANGLE_FAN auto-index buffer");
        }
        return triangleFanBuffer;
    }

    /**
     * Get TRIANGLE_STRIP auto-index buffer
     */
    public static AutoIndexBuffer getTriangleStripBuffer() {
        if (triangleStripBuffer == null) {
            triangleStripBuffer = new AutoIndexBuffer(DrawModeType.TRIANGLE_STRIP);
            triangleStripBuffer.ensureCapacity(INITIAL_VERTEX_CAPACITY);
            LOGGER.info("Created TRIANGLE_STRIP auto-index buffer");
        }
        return triangleStripBuffer;
    }

    /**
     * Get LINES auto-index buffer
     */
    public static AutoIndexBuffer getLinesBuffer() {
        if (linesBuffer == null) {
            linesBuffer = new AutoIndexBuffer(DrawModeType.LINES);
            linesBuffer.ensureCapacity(INITIAL_VERTEX_CAPACITY);
            LOGGER.info("Created LINES auto-index buffer");
        }
        return linesBuffer;
    }

    /**
     * Get DEBUG_LINE_STRIP auto-index buffer
     */
    public static AutoIndexBuffer getDebugLineStripBuffer() {
        if (debugLineStripBuffer == null) {
            debugLineStripBuffer = new AutoIndexBuffer(DrawModeType.DEBUG_LINE_STRIP);
            debugLineStripBuffer.ensureCapacity(INITIAL_VERTEX_CAPACITY);
            LOGGER.info("Created DEBUG_LINE_STRIP auto-index buffer");
        }
        return debugLineStripBuffer;
    }

    /**
     * Get index count for TRIANGLE_FAN given vertex count
     */
    public static int getTriangleFanIndexCount(int vertexCount) {
        if (vertexCount < 3) return 0;
        return (vertexCount - 2) * 3;  // N vertices = N-2 triangles = (N-2)*3 indices
    }

    /**
     * Get index count for TRIANGLE_STRIP given vertex count
     */
    public static int getTriangleStripIndexCount(int vertexCount) {
        if (vertexCount < 3) return 0;
        return (vertexCount - 2) * 3;  // N vertices = N-2 triangles = (N-2)*3 indices
    }

    /**
     * Ensure buffer can handle specified vertex count
     * Grows buffer if needed (powers of 2)
     */
    public void ensureCapacity(int vertexCount) {
        if (vertexCount <= maxVertexCount) {
            return;  // Already large enough
        }

        // Determine if we need 32-bit indices
        boolean need32Bit = vertexCount > MAX_16BIT_VERTICES;
        if (need32Bit != use32BitIndices) {
            // Index size changed - need to recreate buffer
            if (indexBufferHandle != 0) {
                VitraD3D11Renderer.deleteBuffer((int) indexBufferHandle);
                indexBufferHandle = 0;
            }
            use32BitIndices = need32Bit;
        }

        // Round up to next power of 2 for efficient growth
        int newCapacity = INITIAL_VERTEX_CAPACITY;
        while (newCapacity < vertexCount) {
            newCapacity *= 2;
        }

        // Cap at reasonable size to prevent OOM
        newCapacity = Math.min(newCapacity, MAX_16BIT_VERTICES * 4);

        // Generate and upload index buffer
        recreateBuffer(newCapacity);
    }

    /**
     * Get buffer handle for binding
     */
    public long getBufferHandle() {
        return indexBufferHandle;
    }

    /**
     * Cleanup buffer resources
     */
    public void cleanup() {
        if (indexBufferHandle != 0) {
            VitraD3D11Renderer.deleteBuffer((int) indexBufferHandle);
            indexBufferHandle = 0;
            maxVertexCount = 0;
        }
    }

    /**
     * Cleanup all singleton buffers
     */
    public static void cleanupAll() {
        if (quadsBuffer != null) {
            quadsBuffer.cleanup();
            quadsBuffer = null;
        }
        if (triangleFanBuffer != null) {
            triangleFanBuffer.cleanup();
            triangleFanBuffer = null;
        }
        if (triangleStripBuffer != null) {
            triangleStripBuffer.cleanup();
            triangleStripBuffer = null;
        }
        if (linesBuffer != null) {
            linesBuffer.cleanup();
            linesBuffer = null;
        }
        if (debugLineStripBuffer != null) {
            debugLineStripBuffer.cleanup();
            debugLineStripBuffer = null;
        }
        LOGGER.info("Cleaned up all auto-index buffers");
    }

    // ==================== PRIVATE IMPLEMENTATION ====================

    /**
     * Recreate buffer with new capacity
     */
    private void recreateBuffer(int vertexCapacity) {
        // Delete old buffer if exists
        if (indexBufferHandle != 0) {
            VitraD3D11Renderer.deleteBuffer((int) indexBufferHandle);
            indexBufferHandle = 0;
        }

        // Generate indices based on draw mode
        ByteBuffer indexData = generateIndices(vertexCapacity);
        if (indexData == null) {
            LOGGER.error("Failed to generate indices for {}", type);
            return;
        }

        // Create DirectX index buffer
        int bufferId = generateBufferId();
        VitraD3D11Renderer.bindBuffer(34963, bufferId); // GL_ELEMENT_ARRAY_BUFFER
        VitraD3D11Renderer.bufferData(34963, indexData, 0x88E4); // GL_STREAM_DRAW

        indexBufferHandle = bufferId;
        maxVertexCount = vertexCapacity;

        LOGGER.debug("Created {} auto-index buffer: handle={}, vertexCapacity={}, indices={}, {}",
            type, bufferId, vertexCapacity, indexData.remaining() / (use32BitIndices ? 4 : 2),
            use32BitIndices ? "32-bit" : "16-bit");
    }

    /**
     * Generate index data based on draw mode type
     */
    private ByteBuffer generateIndices(int vertexCapacity) {
        int indexCount = calculateIndexCount(vertexCapacity);
        int bytesPerIndex = use32BitIndices ? 4 : 2;

        ByteBuffer buffer = ByteBuffer.allocateDirect(indexCount * bytesPerIndex);
        buffer.order(ByteOrder.nativeOrder());

        switch (type) {
            case QUADS -> generateQuadsIndices(buffer, vertexCapacity);
            case TRIANGLE_FAN -> generateTriangleFanIndices(buffer, vertexCapacity);
            case TRIANGLE_STRIP -> generateTriangleStripIndices(buffer, vertexCapacity);
            case LINES -> generateLinesIndices(buffer, vertexCapacity);
            case DEBUG_LINE_STRIP -> generateDebugLineStripIndices(buffer, vertexCapacity);
            default -> {
                LOGGER.error("Unknown draw mode type: {}", type);
                return null;
            }
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Calculate index count for given vertex count
     */
    private int calculateIndexCount(int vertexCount) {
        return switch (type) {
            case QUADS -> (vertexCount / 4) * 6;  // 4 vertices → 6 indices (2 triangles)
            case TRIANGLE_FAN, TRIANGLE_STRIP -> Math.max(0, (vertexCount - 2) * 3);  // N-2 triangles
            case LINES -> vertexCount;  // 1:1 mapping
            case DEBUG_LINE_STRIP -> Math.max(0, (vertexCount - 1) * 2);  // N-1 lines
        };
    }

    /**
     * Generate QUADS indices: [0,1,2, 2,3,0, 4,5,6, 6,7,4, ...]
     * Each quad (4 vertices) becomes 2 triangles (6 indices)
     */
    private void generateQuadsIndices(ByteBuffer buffer, int vertexCapacity) {
        for (int i = 0; i < vertexCapacity; i += 4) {
            if (use32BitIndices) {
                // Triangle 1: v0, v1, v2
                buffer.putInt(i);
                buffer.putInt(i + 1);
                buffer.putInt(i + 2);
                // Triangle 2: v2, v3, v0
                buffer.putInt(i + 2);
                buffer.putInt(i + 3);
                buffer.putInt(i);
            } else {
                // Triangle 1: v0, v1, v2
                buffer.putShort((short) i);
                buffer.putShort((short) (i + 1));
                buffer.putShort((short) (i + 2));
                // Triangle 2: v2, v3, v0
                buffer.putShort((short) (i + 2));
                buffer.putShort((short) (i + 3));
                buffer.putShort((short) i);
            }
        }
    }

    /**
     * Generate TRIANGLE_FAN indices: [0,1,2, 0,2,3, 0,3,4, ...]
     * First vertex (center) is shared by all triangles
     */
    private void generateTriangleFanIndices(ByteBuffer buffer, int vertexCapacity) {
        if (vertexCapacity < 3) return;

        for (int i = 2; i < vertexCapacity; i++) {
            if (use32BitIndices) {
                buffer.putInt(0);           // Center vertex
                buffer.putInt(i - 1);       // Previous edge vertex
                buffer.putInt(i);           // Current edge vertex
            } else {
                buffer.putShort((short) 0);
                buffer.putShort((short) (i - 1));
                buffer.putShort((short) i);
            }
        }
    }

    /**
     * Generate TRIANGLE_STRIP indices: [0,1,2, 1,3,2, 2,3,4, 3,5,4, ...]
     * Alternating winding order to maintain correct face orientation
     */
    private void generateTriangleStripIndices(ByteBuffer buffer, int vertexCapacity) {
        if (vertexCapacity < 3) return;

        for (int i = 0; i < vertexCapacity - 2; i++) {
            if (i % 2 == 0) {
                // Even triangle: normal winding
                if (use32BitIndices) {
                    buffer.putInt(i);
                    buffer.putInt(i + 1);
                    buffer.putInt(i + 2);
                } else {
                    buffer.putShort((short) i);
                    buffer.putShort((short) (i + 1));
                    buffer.putShort((short) (i + 2));
                }
            } else {
                // Odd triangle: reversed winding
                if (use32BitIndices) {
                    buffer.putInt(i);
                    buffer.putInt(i + 2);
                    buffer.putInt(i + 1);
                } else {
                    buffer.putShort((short) i);
                    buffer.putShort((short) (i + 2));
                    buffer.putShort((short) (i + 1));
                }
            }
        }
    }

    /**
     * Generate LINES indices: [0,1, 2,3, 4,5, ...]
     * Simple 1:1 mapping of vertices to indices
     */
    private void generateLinesIndices(ByteBuffer buffer, int vertexCapacity) {
        for (int i = 0; i < vertexCapacity; i++) {
            if (use32BitIndices) {
                buffer.putInt(i);
            } else {
                buffer.putShort((short) i);
            }
        }
    }

    /**
     * Generate DEBUG_LINE_STRIP indices: [0,1, 1,2, 2,3, ...]
     * Each vertex connects to the next
     */
    private void generateDebugLineStripIndices(ByteBuffer buffer, int vertexCapacity) {
        if (vertexCapacity < 2) return;

        for (int i = 0; i < vertexCapacity - 1; i++) {
            if (use32BitIndices) {
                buffer.putInt(i);
                buffer.putInt(i + 1);
            } else {
                buffer.putShort((short) i);
                buffer.putShort((short) (i + 1));
            }
        }
    }

    /**
     * Generate unique buffer ID (thread-safe)
     */
    private static int nextBufferId = 1;
    private static synchronized int generateBufferId() {
        return nextBufferId++;
    }
}
