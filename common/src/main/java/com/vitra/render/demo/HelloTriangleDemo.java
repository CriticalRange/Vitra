package com.vitra.render.demo;

import com.vitra.render.RenderContext;
import com.vitra.render.VertexLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple "Hello Triangle" rendering demo
 * Demonstrates basic usage of the Vitra rendering system
 */
public class HelloTriangleDemo {
    private static final Logger LOGGER = LoggerFactory.getLogger(HelloTriangleDemo.class);

    private final RenderContext renderContext;
    private int vertexBuffer = -1;
    private int indexBuffer = -1;
    private int shaderProgram = -1;

    // Triangle vertices (position + color)
    private static final float[] TRIANGLE_VERTICES = {
        // Position (x, y, z), Color (r, g, b, a)
         0.0f,  0.5f, 0.0f,   1.0f, 0.0f, 0.0f, 1.0f, // Top vertex - Red
        -0.5f, -0.5f, 0.0f,   0.0f, 1.0f, 0.0f, 1.0f, // Bottom left - Green
         0.5f, -0.5f, 0.0f,   0.0f, 0.0f, 1.0f, 1.0f  // Bottom right - Blue
    };

    // Triangle indices
    private static final int[] TRIANGLE_INDICES = {
        0, 1, 2
    };

    public HelloTriangleDemo(RenderContext renderContext) {
        this.renderContext = renderContext;
    }

    /**
     * Initialize the demo - create buffers and shaders
     */
    public boolean initialize() {
        if (renderContext == null || !renderContext.isValid()) {
            LOGGER.error("Invalid render context");
            return false;
        }

        LOGGER.info("Initializing HelloTriangle demo...");

        try {
            // Create vertex buffer
            VertexLayout layout = VertexLayout.createPositionColor();
            vertexBuffer = renderContext.createVertexBuffer(TRIANGLE_VERTICES, layout);
            if (vertexBuffer < 0) {
                LOGGER.error("Failed to create vertex buffer");
                return false;
            }

            // Create index buffer
            indexBuffer = renderContext.createIndexBuffer(TRIANGLE_INDICES);
            if (indexBuffer < 0) {
                LOGGER.error("Failed to create index buffer");
                return false;
            }

            // Create shader program
            String vertexShaderCode = """
                #version 330 core
                layout (location = 0) in vec3 position;
                layout (location = 1) in vec4 color;
                out vec4 vertexColor;
                void main() {
                    gl_Position = vec4(position, 1.0);
                    vertexColor = color;
                }
                """;

            String fragmentShaderCode = """
                #version 330 core
                in vec4 vertexColor;
                out vec4 fragColor;
                void main() {
                    fragColor = vertexColor;
                }
                """;

            shaderProgram = renderContext.createShaderProgram(vertexShaderCode, fragmentShaderCode);
            if (shaderProgram < 0) {
                LOGGER.warn("Failed to create shader program - using default rendering");
            }

            LOGGER.info("HelloTriangle demo initialized successfully");
            return true;

        } catch (Exception e) {
            LOGGER.error("Failed to initialize HelloTriangle demo", e);
            cleanup();
            return false;
        }
    }

    /**
     * Render the triangle
     */
    public void render() {
        if (renderContext == null || !renderContext.isValid()) return;

        // Clear the screen
        renderContext.clear(0.2f, 0.3f, 0.3f, 1.0f, 1.0f);

        // Bind resources
        if (shaderProgram >= 0) {
            renderContext.bindShaderProgram(shaderProgram);
        }

        renderContext.bindVertexBuffer(vertexBuffer);
        renderContext.bindIndexBuffer(indexBuffer);

        // Draw the triangle
        renderContext.drawIndexed(TRIANGLE_INDICES.length, 0, 0);
    }

    /**
     * Cleanup demo resources
     */
    public void cleanup() {
        if (renderContext == null) return;

        LOGGER.info("Cleaning up HelloTriangle demo...");

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

        LOGGER.info("HelloTriangle demo cleanup complete");
    }

    public boolean isInitialized() {
        return vertexBuffer >= 0 && indexBuffer >= 0;
    }
}