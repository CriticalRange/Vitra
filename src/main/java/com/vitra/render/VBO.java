package com.vitra.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.vitra.render.jni.VitraD3D11Renderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Vertex Buffer Object wrapper for DirectX 11
 * Manages vertex and index buffers, handles uploads and drawing
 * Based on VulkanMod's VBO architecture
 */
@Environment(EnvType.CLIENT)
public class VBO {
    private static final Logger LOGGER = LoggerFactory.getLogger(VBO.class);

    // Buffer usage type
    private final BufferUsage usage;

    // DirectX 11 buffer handles
    private long vertexBufferHandle = 0;
    private long indexBufferHandle = 0;

    // Drawing state
    private VertexFormat.Mode mode;
    private VertexFormat vertexFormat;
    private boolean autoIndexed = false;
    private int indexCount;
    private int vertexCount;
    private int vertexSize;

    // Auto-indexing support (for QUADS, TRIANGLE_FAN, etc.)
    private AutoIndexBuffer autoIndexBuffer;

    public enum BufferUsage {
        STATIC,  // GPU memory for static geometry (world, etc.)
        DYNAMIC  // Host-visible memory for dynamic geometry (particles, UI, etc.)
    }

    public VBO(com.mojang.blaze3d.vertex.VertexBuffer.Usage usage) {
        this.usage = usage == com.mojang.blaze3d.vertex.VertexBuffer.Usage.STATIC
            ? BufferUsage.STATIC
            : BufferUsage.DYNAMIC;
    }

    /**
     * Upload mesh data (vertex + index buffers)
     */
    public void upload(MeshData meshData) {
        MeshData.DrawState parameters = meshData.drawState();

        this.indexCount = parameters.indexCount();
        this.vertexCount = parameters.vertexCount();
        this.mode = parameters.mode();
        this.vertexFormat = parameters.format();
        this.vertexSize = parameters.format().getVertexSize();

        this.uploadVertexBuffer(parameters, meshData.vertexBuffer());
        this.uploadIndexBuffer(meshData.indexBuffer());

        meshData.close();
    }

    /**
     * Upload vertex data to DirectX 11 vertex buffer
     */
    private void uploadVertexBuffer(MeshData.DrawState parameters, ByteBuffer data) {
        if (data == null) {
            return;
        }

        // Free old vertex buffer if exists
        if (this.vertexBufferHandle != 0) {
            VitraD3D11Renderer.deleteBuffer((int) this.vertexBufferHandle);
            this.vertexBufferHandle = 0;
        }

        int size = parameters.format().getVertexSize() * parameters.vertexCount();
        int stride = parameters.format().getVertexSize(); // Stride = size of one vertex

        // Create new DirectX 11 vertex buffer with explicit stride
        // Use GL_ARRAY_BUFFER constant (34962) for vertex buffers
        int bufferId = generateBufferId();
        VitraD3D11Renderer.bindBuffer(34962, bufferId); // GL_ARRAY_BUFFER
        VitraD3D11Renderer.bufferData(34962, data, 0x88E4, stride); // GL_STREAM_DRAW with stride

        this.vertexBufferHandle = bufferId;

        LOGGER.debug("Uploaded vertex buffer: handle={}, size={}, vertexCount={}, stride={}",
            bufferId, size, parameters.vertexCount(), stride);
    }

    /**
     * Upload index buffer - handles both explicit and auto-generated indices
     */
    public void uploadIndexBuffer(ByteBuffer data) {
        // Free old index buffer if not auto-indexed
        if (this.indexBufferHandle != 0 && !this.autoIndexed) {
            VitraD3D11Renderer.deleteBuffer((int) this.indexBufferHandle);
            this.indexBufferHandle = 0;
        }

        if (data == null) {
            // No explicit index data - use auto-indexing for certain draw modes
            handleAutoIndexing();
        } else {
            // Explicit index data provided
            uploadExplicitIndexBuffer(data);
        }
    }

    /**
     * Handle auto-indexing for draw modes that need index buffer conversion
     */
    private void handleAutoIndexing() {
        switch (this.mode) {
            case TRIANGLE_FAN -> {
                // Convert triangle fan to indexed triangles
                this.autoIndexBuffer = AutoIndexBuffer.getTriangleFanBuffer();
                this.indexCount = AutoIndexBuffer.getTriangleFanIndexCount(this.vertexCount);
            }
            case TRIANGLE_STRIP, LINE_STRIP -> {
                // Convert strip to indexed triangles/lines
                this.autoIndexBuffer = AutoIndexBuffer.getTriangleStripBuffer();
                this.indexCount = AutoIndexBuffer.getTriangleStripIndexCount(this.vertexCount);
            }
            case QUADS -> {
                // Convert quads to indexed triangles (most common case)
                this.autoIndexBuffer = AutoIndexBuffer.getQuadsBuffer();
                this.indexCount = (this.vertexCount / 4) * 6; // 4 vertices -> 6 indices
            }
            case LINES -> {
                this.autoIndexBuffer = AutoIndexBuffer.getLinesBuffer();
                this.indexCount = this.vertexCount;
            }
            case DEBUG_LINE_STRIP -> {
                this.autoIndexBuffer = AutoIndexBuffer.getDebugLineStripBuffer();
                this.indexCount = AutoIndexBuffer.getTriangleStripIndexCount(this.vertexCount);
            }
            case TRIANGLES, DEBUG_LINES -> {
                // Already in correct format - no index buffer needed
                this.autoIndexBuffer = null;
                this.indexBufferHandle = 0;
            }
            default -> throw new IllegalStateException("Unexpected draw mode: " + this.mode);
        }

        if (this.autoIndexBuffer != null) {
            this.autoIndexBuffer.ensureCapacity(this.vertexCount);
            this.indexBufferHandle = this.autoIndexBuffer.getBufferHandle();
        }

        this.autoIndexed = true;
        LOGGER.debug("Auto-indexed buffer: mode={}, vertexCount={}, indexCount={}",
            this.mode, this.vertexCount, this.indexCount);
    }

    /**
     * Upload explicit index buffer data
     */
    private void uploadExplicitIndexBuffer(ByteBuffer data) {
        int size = data.remaining();

        // Create new DirectX 11 index buffer
        // Use GL_ELEMENT_ARRAY_BUFFER constant (34963) for index buffers
        int bufferId = generateBufferId();
        VitraD3D11Renderer.bindBuffer(34963, bufferId); // GL_ELEMENT_ARRAY_BUFFER
        VitraD3D11Renderer.bufferData(34963, data, 0x88E4); // GL_STREAM_DRAW

        this.indexBufferHandle = bufferId;
        this.autoIndexed = false;

        LOGGER.debug("Uploaded index buffer: handle={}, size={}", bufferId, size);
    }

    /**
     * Draw with shader instance (Minecraft's shader system)
     */
    public void drawWithShader(Matrix4f modelView, Matrix4f projection, ShaderInstance shaderInstance) {
        if (this.indexCount == 0 || this.vertexBufferHandle == 0) {
            return;
        }

        RenderSystem.assertOnRenderThread();

        // Set shader
        RenderSystem.setShader(() -> shaderInstance);

        // Apply matrices
        VitraD3D11Renderer.setModelViewMatrix(matrixToFloatArray(modelView));
        VitraD3D11Renderer.setProjectionMatrix(matrixToFloatArray(projection));

        // Set primitive topology
        int glMode = this.mode.asGLMode;
        VitraD3D11Renderer.setPrimitiveTopology(glMode);

        // Apply shader uniforms
        shaderInstance.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection,
            Minecraft.getInstance().getWindow());
        shaderInstance.apply();

        // Encode vertex format for native
        int[] vertexFormatDesc = encodeVertexFormat(this.vertexFormat);

        // Draw
        if (this.indexBufferHandle != 0) {
            // Indexed draw
            VitraD3D11Renderer.drawWithVertexFormat(this.vertexBufferHandle, this.indexBufferHandle,
                0, 0, this.indexCount, 1, vertexFormatDesc);
        } else {
            // Non-indexed draw
            VitraD3D11Renderer.drawWithVertexFormat(this.vertexBufferHandle, 0,
                0, 0, this.vertexCount, 1, vertexFormatDesc);
        }

        LOGGER.debug("Drew with shader: vertices={}, indices={}, mode={}",
            this.vertexCount, this.indexCount, this.mode);
    }

    /**
     * Draw without shader (uses currently bound shader/pipeline)
     */
    public void draw() {
        if (this.indexCount == 0 || this.vertexBufferHandle == 0) {
            return;
        }

        // Encode vertex format for native
        int[] vertexFormatDesc = encodeVertexFormat(this.vertexFormat);

        // Draw using current shader state
        if (this.indexBufferHandle != 0) {
            VitraD3D11Renderer.drawWithVertexFormat(this.vertexBufferHandle, this.indexBufferHandle,
                0, 0, this.indexCount, 1, vertexFormatDesc);
        } else {
            VitraD3D11Renderer.drawWithVertexFormat(this.vertexBufferHandle, 0,
                0, 0, this.vertexCount, 1, vertexFormatDesc);
        }

        LOGGER.debug("Drew: vertices={}, indices={}, mode={}",
            this.vertexCount, this.indexCount, this.mode);
    }

    /**
     * Clean up DirectX 11 resources
     */
    public void close() {
        if (this.vertexCount <= 0) {
            return;
        }

        // Free vertex buffer
        if (this.vertexBufferHandle != 0) {
            VitraD3D11Renderer.deleteBuffer((int) this.vertexBufferHandle);
            this.vertexBufferHandle = 0;
        }

        // Free index buffer (if not auto-indexed)
        if (this.indexBufferHandle != 0 && !this.autoIndexed) {
            VitraD3D11Renderer.deleteBuffer((int) this.indexBufferHandle);
            this.indexBufferHandle = 0;
        }

        this.vertexCount = 0;
        this.indexCount = 0;

        LOGGER.debug("Closed VBO");
    }

    // Helper methods

    private static int bufferIdCounter = 1;

    private static synchronized int generateBufferId() {
        return bufferIdCounter++;
    }

    private static float[] matrixToFloatArray(Matrix4f matrix) {
        float[] array = new float[16];
        matrix.get(array);
        return array;
    }

    /**
     * Encode VertexFormat into int array for JNI transfer.
     * Format: [elementCount, usage1, type1, count1, offset1, usage2, type2, count2, offset2, ...]
     * Based on VulkanMod's approach to vertex format handling.
     */
    private static int[] encodeVertexFormat(VertexFormat format) {
        var elements = format.getElements();
        int elementCount = elements.size();

        // Array format: [count, usage1, type1, count1, offset1, ...]
        int[] encoded = new int[1 + elementCount * 4];
        encoded[0] = elementCount;

        int offset = 0;
        for (int i = 0; i < elementCount; i++) {
            var element = elements.get(i);
            int baseIdx = 1 + i * 4;

            // Encode usage (POSITION=0, COLOR=1, UV=2, NORMAL=3, etc.)
            encoded[baseIdx] = element.usage().ordinal();

            // Encode type (FLOAT=0, UBYTE=1, BYTE=2, USHORT=3, SHORT=4, UINT=5, INT=6)
            encoded[baseIdx + 1] = element.type().ordinal();

            // Element count (e.g., 3 for vec3, 4 for vec4)
            encoded[baseIdx + 2] = element.count();

            // Byte offset in vertex
            encoded[baseIdx + 3] = offset;

            // Update offset for next element
            offset += getElementByteSize(element.type(), element.count());
        }

        return encoded;
    }

    /**
     * Calculate byte size of a vertex element based on type and count.
     * Matches VulkanMod's approach of manually calculating sizes.
     */
    private static int getElementByteSize(com.mojang.blaze3d.vertex.VertexFormatElement.Type type, int count) {
        return switch (type) {
            case FLOAT -> 4 * count;   // 4 bytes per float
            case UBYTE -> 1 * count;   // 1 byte per ubyte
            case BYTE -> 1 * count;    // 1 byte per byte
            case USHORT -> 2 * count;  // 2 bytes per ushort
            case SHORT -> 2 * count;   // 2 bytes per short
            case UINT -> 4 * count;    // 4 bytes per uint
            case INT -> 4 * count;     // 4 bytes per int
        };
    }

    /**
     * Auto-indexing helper class - generates index buffers for primitive conversion
     * (QUADS -> TRIANGLES, TRIANGLE_FAN -> TRIANGLES, etc.)
     */
    private static class AutoIndexBuffer {
        private static AutoIndexBuffer quadsBuffer;
        private static AutoIndexBuffer triangleFanBuffer;
        private static AutoIndexBuffer triangleStripBuffer;
        private static AutoIndexBuffer linesBuffer;
        private static AutoIndexBuffer debugLineStripBuffer;

        private long bufferHandle;
        private int capacity;

        private AutoIndexBuffer(int initialCapacity) {
            this.capacity = initialCapacity;
            this.bufferHandle = 0;
        }

        void ensureCapacity(int vertexCount) {
            int requiredCapacity = calculateRequiredCapacity(vertexCount);
            if (requiredCapacity > this.capacity) {
                resize(requiredCapacity);
            }
        }

        private void resize(int newCapacity) {
            // Free old buffer
            if (this.bufferHandle != 0) {
                VitraD3D11Renderer.deleteBuffer((int) this.bufferHandle);
            }

            // Create new larger buffer
            ByteBuffer indexData = generateIndexData(newCapacity);
            int bufferId = generateBufferId();
            VitraD3D11Renderer.bindBuffer(34963, bufferId); // GL_ELEMENT_ARRAY_BUFFER
            VitraD3D11Renderer.bufferData(34963, indexData, 0x88E4); // GL_STREAM_DRAW

            this.bufferHandle = bufferId;
            this.capacity = newCapacity;
        }

        protected ByteBuffer generateIndexData(int capacity) {
            // Override in subclasses
            return ByteBuffer.allocateDirect(0);
        }

        protected int calculateRequiredCapacity(int vertexCount) {
            return vertexCount;
        }

        long getBufferHandle() {
            return this.bufferHandle;
        }

        static AutoIndexBuffer getQuadsBuffer() {
            if (quadsBuffer == null) {
                quadsBuffer = new AutoIndexBuffer(1024) {
                    @Override
                    protected ByteBuffer generateIndexData(int quadCount) {
                        ByteBuffer buffer = ByteBuffer.allocateDirect(quadCount * 6 * 4); // 6 indices per quad, 4 bytes per int
                        for (int i = 0; i < quadCount; i++) {
                            int base = i * 4;
                            buffer.putInt(base);
                            buffer.putInt(base + 1);
                            buffer.putInt(base + 2);
                            buffer.putInt(base + 2);
                            buffer.putInt(base + 3);
                            buffer.putInt(base);
                        }
                        buffer.flip();
                        return buffer;
                    }

                    @Override
                    protected int calculateRequiredCapacity(int vertexCount) {
                        return vertexCount / 4; // 4 vertices per quad
                    }
                };
            }
            return quadsBuffer;
        }

        static AutoIndexBuffer getTriangleFanBuffer() {
            if (triangleFanBuffer == null) {
                triangleFanBuffer = new AutoIndexBuffer(1024);
            }
            return triangleFanBuffer;
        }

        static AutoIndexBuffer getTriangleStripBuffer() {
            if (triangleStripBuffer == null) {
                triangleStripBuffer = new AutoIndexBuffer(1024);
            }
            return triangleStripBuffer;
        }

        static AutoIndexBuffer getLinesBuffer() {
            if (linesBuffer == null) {
                linesBuffer = new AutoIndexBuffer(1024);
            }
            return linesBuffer;
        }

        static AutoIndexBuffer getDebugLineStripBuffer() {
            if (debugLineStripBuffer == null) {
                debugLineStripBuffer = new AutoIndexBuffer(1024);
            }
            return debugLineStripBuffer;
        }

        static int getTriangleFanIndexCount(int vertexCount) {
            return (vertexCount - 2) * 3; // N vertices -> (N-2) triangles -> (N-2)*3 indices
        }

        static int getTriangleStripIndexCount(int vertexCount) {
            return (vertexCount - 2) * 3; // N vertices -> (N-2) triangles -> (N-2)*3 indices
        }
    }
}
