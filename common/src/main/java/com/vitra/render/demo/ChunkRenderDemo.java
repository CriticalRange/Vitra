package com.vitra.render.demo;

import com.vitra.render.RenderContext;
import com.vitra.render.VertexLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chunk rendering demonstration
 * Shows how to render a simple Minecraft-style chunk using the Vitra rendering system
 */
public class ChunkRenderDemo {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkRenderDemo.class);

    private final RenderContext renderContext;
    private int vertexBuffer = -1;
    private int indexBuffer = -1;
    private int shaderProgram = -1;

    // Simple chunk data - 16x16x16 blocks
    private static final int CHUNK_SIZE = 16;
    private float[] chunkVertices;
    private int[] chunkIndices;

    public ChunkRenderDemo(RenderContext renderContext) {
        this.renderContext = renderContext;
        generateChunkMesh();
    }

    /**
     * Generate a simple chunk mesh for demonstration
     */
    private void generateChunkMesh() {
        LOGGER.info("Generating demo chunk mesh...");

        // For simplicity, create a flat plane of blocks
        int vertexCount = CHUNK_SIZE * CHUNK_SIZE * 6 * 4; // 6 faces per block, 4 vertices per face
        int indexCount = CHUNK_SIZE * CHUNK_SIZE * 6 * 6;  // 6 faces per block, 6 indices per face

        chunkVertices = new float[vertexCount * 8]; // Position(3) + Normal(3) + TexCoord(2)
        chunkIndices = new int[indexCount];

        int vertexIndex = 0;
        int indexIndex = 0;
        int baseIndex = 0;

        // Generate a simple flat terrain with some height variation
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                // Simple height calculation
                float height = 1 + (float)Math.sin(x * 0.5f) * (float)Math.cos(z * 0.5f) * 2;
                int blockHeight = Math.max(1, (int)height);

                for (int y = 0; y < blockHeight; y++) {
                    // Only render the top face for this demo
                    if (y == blockHeight - 1) {
                        // Top face vertices
                        float[] faceVertices = {
                            // Position                      Normal      TexCoord
                            x,     y + 1, z,               0, 1, 0,    0, 0,
                            x + 1, y + 1, z,               0, 1, 0,    1, 0,
                            x + 1, y + 1, z + 1,           0, 1, 0,    1, 1,
                            x,     y + 1, z + 1,           0, 1, 0,    0, 1
                        };

                        System.arraycopy(faceVertices, 0, chunkVertices, vertexIndex, faceVertices.length);
                        vertexIndex += faceVertices.length;

                        // Top face indices
                        int[] faceIndices = {
                            baseIndex, baseIndex + 1, baseIndex + 2,
                            baseIndex, baseIndex + 2, baseIndex + 3
                        };

                        System.arraycopy(faceIndices, 0, chunkIndices, indexIndex, faceIndices.length);
                        indexIndex += faceIndices.length;
                        baseIndex += 4;
                    }
                }
            }
        }

        // Trim arrays to actual size
        float[] trimmedVertices = new float[vertexIndex];
        System.arraycopy(chunkVertices, 0, trimmedVertices, 0, vertexIndex);
        chunkVertices = trimmedVertices;

        int[] trimmedIndices = new int[indexIndex];
        System.arraycopy(chunkIndices, 0, trimmedIndices, 0, indexIndex);
        chunkIndices = trimmedIndices;

        LOGGER.info("Generated chunk mesh with {} vertices, {} indices", vertexIndex / 8, indexIndex);
    }

    /**
     * Initialize the chunk demo
     */
    public boolean initialize() {
        if (renderContext == null || !renderContext.isValid()) {
            LOGGER.error("Invalid render context");
            return false;
        }

        LOGGER.info("Initializing ChunkRender demo...");

        try {
            // Create vertex buffer
            VertexLayout layout = VertexLayout.createPositionNormalTexture();
            vertexBuffer = renderContext.createVertexBuffer(chunkVertices, layout);
            if (vertexBuffer < 0) {
                LOGGER.error("Failed to create vertex buffer");
                return false;
            }

            // Create index buffer
            indexBuffer = renderContext.createIndexBuffer(chunkIndices);
            if (indexBuffer < 0) {
                LOGGER.error("Failed to create index buffer");
                return false;
            }

            // Create shader program
            String vertexShaderCode = """
                #version 330 core
                layout (location = 0) in vec3 position;
                layout (location = 1) in vec3 normal;
                layout (location = 2) in vec2 texCoord;

                uniform mat4 mvpMatrix;
                uniform mat4 modelMatrix;

                out vec3 worldPos;
                out vec3 worldNormal;
                out vec2 uv;

                void main() {
                    vec4 worldPosition = modelMatrix * vec4(position, 1.0);
                    worldPos = worldPosition.xyz;
                    worldNormal = normalize((modelMatrix * vec4(normal, 0.0)).xyz);
                    uv = texCoord;
                    gl_Position = mvpMatrix * worldPosition;
                }
                """;

            String fragmentShaderCode = """
                #version 330 core
                in vec3 worldPos;
                in vec3 worldNormal;
                in vec2 uv;

                uniform vec3 lightDirection;

                out vec4 fragColor;

                void main() {
                    // Simple checkerboard pattern based on UV coordinates
                    float checker = mod(floor(uv.x * 8.0) + floor(uv.y * 8.0), 2.0);
                    vec3 color1 = vec3(0.8, 0.6, 0.4); // Light brown
                    vec3 color2 = vec3(0.4, 0.8, 0.4); // Light green
                    vec3 baseColor = mix(color1, color2, checker);

                    // Simple lighting
                    float lightFactor = max(0.3, dot(worldNormal, -lightDirection));
                    vec3 finalColor = baseColor * lightFactor;

                    fragColor = vec4(finalColor, 1.0);
                }
                """;

            shaderProgram = renderContext.createShaderProgram(vertexShaderCode, fragmentShaderCode);
            if (shaderProgram < 0) {
                LOGGER.warn("Failed to create shader program - using default rendering");
            }

            LOGGER.info("ChunkRender demo initialized successfully");
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to initialize ChunkRender demo", e);
            cleanup();
            return false;
        }
    }

    /**
     * Render the chunk
     * @param mvpMatrix Model-View-Projection matrix
     * @param modelMatrix Model matrix
     * @param lightDirection Light direction vector
     */
    public void render(float[] mvpMatrix, float[] modelMatrix, float[] lightDirection) {
        if (renderContext == null || !renderContext.isValid()) return;

        // Clear the screen
        renderContext.clear(0.6f, 0.8f, 1.0f, 1.0f, 1.0f); // Sky blue background

        // Bind shader and set uniforms
        if (shaderProgram >= 0) {
            renderContext.bindShaderProgram(shaderProgram);
            renderContext.setShaderUniform(shaderProgram, "mvpMatrix", mvpMatrix);
            renderContext.setShaderUniform(shaderProgram, "modelMatrix", modelMatrix);
            renderContext.setShaderUniform(shaderProgram, "lightDirection", lightDirection);
        }

        // Bind buffers and draw
        renderContext.bindVertexBuffer(vertexBuffer);
        renderContext.bindIndexBuffer(indexBuffer);
        renderContext.drawIndexed(chunkIndices.length, 0, 0);
    }

    /**
     * Simple render method with default matrices
     */
    public void render() {
        // Identity matrices and default light direction for demo
        float[] identityMatrix = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };

        float[] lightDirection = {0.5f, -1.0f, 0.3f};

        render(identityMatrix, identityMatrix, lightDirection);
    }

    /**
     * Cleanup demo resources
     */
    public void cleanup() {
        if (renderContext == null) return;

        LOGGER.info("Cleaning up ChunkRender demo...");

        if (vertexBuffer >= 0) {
            renderContext.deleteBuffer(vertexBuffer);
            vertexBuffer = -1;
        }

        if (indexBuffer >= 0) {
            renderContext.deleteBuffer(indexBuffer);
            indexBuffer = -1;
        }

        if (shaderProgram >= 0) {
            renderContext.deleteShaderProgram(shaderProgram);
            shaderProgram = -1;
        }

        LOGGER.info("ChunkRender demo cleanup complete");
    }

    public boolean isInitialized() {
        return vertexBuffer >= 0 && indexBuffer >= 0;
    }

    public int getVertexCount() {
        return chunkVertices.length / 8;
    }

    public int getIndexCount() {
        return chunkIndices.length;
    }
}