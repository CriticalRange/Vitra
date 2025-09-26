package com.vitra.render.backend;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import com.vitra.VitraMod;
import com.vitra.render.MatrixUtil;
import org.lwjgl.bgfx.BGFX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BGFX D3D11 window wrapper that handles BGFX frame submission
 */
public class BgfxWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger("BgfxWindow");
    private static BgfxWindow instance;

    private Window wrappedWindow;
    private boolean bgfxInitialized = false;

    // Frame timing optimization to prevent overwhelming GPU and infinite loops
    private long lastFrameTime = 0;
    private final long frameTimeLimit = 16; // ~60 FPS max (16ms between frames)
    private volatile boolean frameInProgress = false;

    private BgfxWindow() {
        // Private constructor for singleton
    }

    public static BgfxWindow getInstance() {
        if (instance == null) {
            instance = new BgfxWindow();
        }
        return instance;
    }

    public void wrapWindow(Window window) {
        this.wrappedWindow = window;
        LOGGER.info("Wrapped GLFW window with BGFX D3D11 handler");
    }

    public void handleUpdateDisplay() {
        // Prevent infinite rendering loops that cause crashes
        if (frameInProgress) {
            LOGGER.debug("Frame already in progress, skipping duplicate call");
            return;
        }

        // Rate limit frames to prevent overwhelming the GPU and causing crashes
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFrameTime < frameTimeLimit) {
            LOGGER.debug("Frame rate limited - skipping frame ({}ms since last)", currentTime - lastFrameTime);
            return;
        }

        frameInProgress = true;
        lastFrameTime = currentTime;

        try {
            if (!bgfxInitialized) {
                LOGGER.info("First frame - ensuring BGFX D3D11 is ready");
                if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isFullyInitialized()) {
                    bgfxInitialized = true;
                    LOGGER.info("BGFX D3D11 confirmed ready for rendering");
                }
            }

            // Handle BGFX frame submission - THE ONLY POINT WHERE FRAMES ARE SUBMITTED
            renderSingleFrame();

        } catch (Exception e) {
            LOGGER.error("Error during BGFX frame submission", e);
        } finally {
            frameInProgress = false;
        }
    }

    private void renderSingleFrame() {
        try {
            if (bgfxInitialized) {
                // Set up the color palette first - index 0 should be black
                float[] blackColor = {0.0f, 0.0f, 0.0f, 1.0f}; // Black with full alpha (RGBA)
                BGFX.bgfx_set_palette_color(0, blackColor);
                LOGGER.debug("*** BGFX DEBUG: Set palette color 0 to black (0,0,0,1)");

                // Remove duplicate clear color setting - will be set properly in frame submission

                // Process any pending BGFX rendering commands that were submitted by mixins
                renderMinecraftContent();

                // Submit the frame to DirectX 11 - this should be fast in multithreaded mode
                try {
                    // CRITICAL FIX: Ensure view 0 is properly configured before frame submission
                    // The view MUST target the main backbuffer for content to be visible
                    int currentWidth = wrappedWindow != null ? wrappedWindow.getWidth() : 854;
                    int currentHeight = wrappedWindow != null ? wrappedWindow.getHeight() : 480;
                    LOGGER.info("*** FRAMEBUFFER FIX: Configuring view 0 for backbuffer ({}x{})", currentWidth, currentHeight);

                    // CRITICAL: Set view 0 to target the default framebuffer (backbuffer)
                    BGFX.bgfx_set_view_name(0, "MainView");
                    BGFX.bgfx_set_view_frame_buffer(0, BGFX.BGFX_INVALID_HANDLE); // Use default backbuffer
                    BGFX.bgfx_set_view_rect(0, 0, 0, currentWidth, currentHeight);

                    // Set transparent clear color to allow Minecraft content to show through
                    int transparentColor = 0x00000000; // Fully transparent
                    BGFX.bgfx_set_view_clear(0, BGFX.BGFX_CLEAR_COLOR | BGFX.BGFX_CLEAR_DEPTH, transparentColor, 1.0f, 0);

                    // BGFX frame submission - should be non-blocking in multithreaded mode
                    BGFX.bgfx_frame(false); // Advance frame without capture
                } catch (Exception frameException) {
                    LOGGER.error("*** BGFX ERROR: Exception during frame submission", frameException);
                    // Don't rethrow - try to continue gracefully
                } catch (Error frameError) {
                    LOGGER.error("*** BGFX CRITICAL: Native error during frame submission", frameError);
                    throw frameError; // Re-throw errors as they indicate serious native issues
                }
            } else {
                LOGGER.debug("BgfxWindow: BGFX not initialized yet, skipping frame submission");
                // Check if renderer is available to trigger initialization
                if (VitraMod.getRenderer() != null && VitraMod.getRenderer().isFullyInitialized()) {
                    bgfxInitialized = true;
                    LOGGER.info("BGFX D3D11 confirmed ready for rendering");
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error during single frame rendering", e);
        }
    }

    private void renderMinecraftContent() {
        // Ensure view is properly configured
        int viewWidth = wrappedWindow != null ? wrappedWindow.getWidth() : 854;
        int viewHeight = wrappedWindow != null ? wrappedWindow.getHeight() : 480;
        BGFX.bgfx_set_view_rect(0, 0, 0, viewWidth, viewHeight);

        // CRITICAL: Set projection matrix for BGFX (without this, nothing renders!)
        setupProjectionMatrix(viewWidth, viewHeight);

        // CRITICAL TEST: Draw BGFX debug text to verify BGFX is rendering ANYTHING
        drawDebugText();

        // CRITICAL TEST: Draw hardcoded triangle to test if BGFX rendering works at all
        drawTestTriangle();

        // Draw all pending BGFX buffers that were created by BgfxMeshDataHandler
        int drawCallCount = com.vitra.render.bgfx.BgfxMeshDataHandler.drawAllBuffers(0);

        if (drawCallCount > 0) {
            LOGGER.info("*** FRAMEBUFFER TEST: Submitted {} BGFX draw calls for Minecraft geometry", drawCallCount);
        } else {
            // Touch the view to ensure it gets processed even if no geometry is submitted
            LOGGER.info("*** FRAMEBUFFER TEST: No geometry submitted, touching view 0 to ensure clear color is visible");
            BGFX.bgfx_touch(0);
        }
    }

    /**
     * CRITICAL TEST: Draw BGFX debug text to verify BGFX rendering pipeline
     */
    private void drawDebugText() {
        try {
            // Clear debug text buffer and draw test text
            BGFX.bgfx_dbg_text_clear(0, false);
            BGFX.bgfx_dbg_text_printf(1, 1, 0x0f, "BGFX RENDERING TEST");
            BGFX.bgfx_dbg_text_printf(1, 2, 0x0a, "If you see this text, BGFX is working!");
            BGFX.bgfx_dbg_text_printf(1, 3, 0x0c, "DirectX 11 Backend Active");
            LOGGER.info("*** DEBUG TEXT: BGFX debug text drawn - should be visible if BGFX renders");
        } catch (Exception e) {
            LOGGER.error("*** DEBUG TEXT: Failed to draw BGFX debug text", e);
        }
    }

    /**
     * CRITICAL: Setup projection matrix for BGFX rendering
     * Without this, all geometry will be clipped and invisible
     */
    private void setupProjectionMatrix(int width, int height) {
        try {
            // Try to use captured Minecraft matrices first
            float[] capturedProjection = MatrixUtil.getLastProjectionMatrix();
            float[] capturedModelView = MatrixUtil.getLastModelViewMatrix();

            if (capturedProjection != null && capturedModelView != null) {
                // Use actual Minecraft matrices
                BGFX.bgfx_set_view_transform(0, capturedModelView, capturedProjection);
                LOGGER.info("*** MATRIX FIX: Using captured Minecraft matrices (projection + modelview)");
                return;
            }

            // Use perspective projection for 3D Minecraft world content instead of orthographic
            LOGGER.info("*** MATRIX FIX: Using perspective projection for 3D Minecraft world ({}x{})", width, height);
            // Skip orthographic - go straight to perspective fallback below

            // Final fallback to perspective if all else fails
            LOGGER.info("*** MATRIX FIX: Using fallback perspective projection ({}x{})", width, height);

            // Create perspective projection matrix for 3D rendering (like Minecraft)
            float[] perspectiveProjMatrix = new float[16];

            // Perspective projection parameters (similar to Minecraft's)
            float fov = 70.0f; // Field of view in degrees (Minecraft default)
            float aspect = (float) width / (float) height;
            float nearPlane = 0.05f; // Minecraft's near plane
            float farPlane = 1000.0f; // Minecraft's far plane

            // Convert FOV to radians
            float fovRadians = (float) Math.toRadians(fov);
            float f = 1.0f / (float) Math.tan(fovRadians / 2.0f);

            // Build perspective projection matrix
            perspectiveProjMatrix[0] = f / aspect;  // X scale
            perspectiveProjMatrix[1] = 0.0f;
            perspectiveProjMatrix[2] = 0.0f;
            perspectiveProjMatrix[3] = 0.0f;

            perspectiveProjMatrix[4] = 0.0f;
            perspectiveProjMatrix[5] = f;           // Y scale
            perspectiveProjMatrix[6] = 0.0f;
            perspectiveProjMatrix[7] = 0.0f;

            perspectiveProjMatrix[8] = 0.0f;
            perspectiveProjMatrix[9] = 0.0f;
            perspectiveProjMatrix[10] = (farPlane + nearPlane) / (nearPlane - farPlane);  // Z scale
            perspectiveProjMatrix[11] = -1.0f;

            perspectiveProjMatrix[12] = 0.0f;
            perspectiveProjMatrix[13] = 0.0f;
            perspectiveProjMatrix[14] = (2.0f * farPlane * nearPlane) / (nearPlane - farPlane); // Z translation
            perspectiveProjMatrix[15] = 0.0f;

            // CRITICAL FIX: Setup view matrix (camera positioning) for Minecraft 3D world
            // Minecraft geometry needs both projection AND view matrices
            float[] viewMatrix = createMinecraftViewMatrix();

            // Set both view and projection matrices for view 0
            BGFX.bgfx_set_view_transform(0, viewMatrix, perspectiveProjMatrix);

            LOGGER.info("*** MATRIX FIX: Perspective projection + view matrices set successfully (FOV={}°, aspect={}, near={}, far={})",
                       fov, aspect, nearPlane, farPlane);

        } catch (Exception e) {
            LOGGER.error("*** MATRIX FIX: Failed to set projection matrix", e);
        }
    }

    /**
     * CRITICAL: Create view matrix for Minecraft camera positioning
     * This positions the camera to view Minecraft's world coordinate system
     */
    private float[] createMinecraftViewMatrix() {
        float[] viewMatrix = new float[16];

        // Position camera closer to origin to see geometry clustered there
        // All geometry is being placed at origin, so get closer view
        float eyeX = 0.0f, eyeY = 2.0f, eyeZ = 5.0f;     // Much closer to see origin geometry
        float lookX = 0.0f, lookY = 0.0f, lookZ = 0.0f;  // Looking directly at origin
        float upX = 0.0f, upY = 1.0f, upZ = 0.0f;        // Y-up coordinate system

        // Calculate view matrix vectors
        // Forward vector (from eye to target, normalized)
        float forwardX = lookX - eyeX;
        float forwardY = lookY - eyeY;
        float forwardZ = lookZ - eyeZ;
        float forwardLen = (float) Math.sqrt(forwardX*forwardX + forwardY*forwardY + forwardZ*forwardZ);
        forwardX /= forwardLen;
        forwardY /= forwardLen;
        forwardZ /= forwardLen;

        // Right vector (cross product of forward and up)
        float rightX = forwardY * upZ - forwardZ * upY;
        float rightY = forwardZ * upX - forwardX * upZ;
        float rightZ = forwardX * upY - forwardY * upX;
        float rightLen = (float) Math.sqrt(rightX*rightX + rightY*rightY + rightZ*rightZ);
        rightX /= rightLen;
        rightY /= rightLen;
        rightZ /= rightLen;

        // Up vector (cross product of right and forward)
        float cameraUpX = rightY * forwardZ - rightZ * forwardY;
        float cameraUpY = rightZ * forwardX - rightX * forwardZ;
        float cameraUpZ = rightX * forwardY - rightY * forwardX;

        // Build view matrix (camera-to-world transform inverse)
        viewMatrix[0] = rightX;
        viewMatrix[1] = cameraUpX;
        viewMatrix[2] = -forwardX;
        viewMatrix[3] = 0.0f;

        viewMatrix[4] = rightY;
        viewMatrix[5] = cameraUpY;
        viewMatrix[6] = -forwardY;
        viewMatrix[7] = 0.0f;

        viewMatrix[8] = rightZ;
        viewMatrix[9] = cameraUpZ;
        viewMatrix[10] = -forwardZ;
        viewMatrix[11] = 0.0f;

        viewMatrix[12] = -(rightX * eyeX + rightY * eyeY + rightZ * eyeZ);
        viewMatrix[13] = -(cameraUpX * eyeX + cameraUpY * eyeY + cameraUpZ * eyeZ);
        viewMatrix[14] = -(-forwardX * eyeX + -forwardY * eyeY + -forwardZ * eyeZ);
        viewMatrix[15] = 1.0f;

        LOGGER.info("*** VIEW MATRIX: Camera at ({},{},{}) looking at ({},{},{}) with up ({},{},{})",
                   eyeX, eyeY, eyeZ, lookX, lookY, lookZ, upX, upY, upZ);

        return viewMatrix;
    }

    /**
     * CRITICAL TEST: Draw a hardcoded triangle to verify BGFX rendering pipeline
     */
    private void drawTestTriangle() {
        try {
            LOGGER.info("*** TRIANGLE TEST: Drawing hardcoded COLORFUL triangle (cyan/magenta/yellow) to test BGFX pipeline");

            // Create simple triangle vertices: position (x,y,z), color (r,g,b,a)
            java.nio.ByteBuffer triangleData = java.nio.ByteBuffer.allocateDirect(3 * 7 * 4); // 3 vertices * 7 floats * 4 bytes
            triangleData.order(java.nio.ByteOrder.nativeOrder());

            // Vertex 1: Top center, bright cyan
            triangleData.putFloat(0.0f).putFloat(0.8f).putFloat(0.0f);    // position
            triangleData.putFloat(0.0f).putFloat(1.0f).putFloat(1.0f).putFloat(1.0f); // bright cyan color

            // Vertex 2: Bottom left, bright magenta
            triangleData.putFloat(-0.8f).putFloat(-0.8f).putFloat(0.0f);   // position
            triangleData.putFloat(1.0f).putFloat(0.0f).putFloat(1.0f).putFloat(1.0f); // bright magenta color

            // Vertex 3: Bottom right, bright yellow
            triangleData.putFloat(0.8f).putFloat(-0.8f).putFloat(0.0f);    // position
            triangleData.putFloat(1.0f).putFloat(1.0f).putFloat(0.0f).putFloat(1.0f); // bright yellow color

            triangleData.flip();

            // Copy to BGFX memory
            org.lwjgl.bgfx.BGFXMemory bgfxMemory = BGFX.bgfx_copy(triangleData);

            // Create vertex layout for position + color
            org.lwjgl.bgfx.BGFXVertexLayout layout = org.lwjgl.bgfx.BGFXVertexLayout.create();
            BGFX.bgfx_vertex_layout_begin(layout, BGFX.BGFX_RENDERER_TYPE_DIRECT3D11);
            BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            BGFX.bgfx_vertex_layout_add(layout, BGFX.BGFX_ATTRIB_COLOR0, 4, BGFX.BGFX_ATTRIB_TYPE_FLOAT, false, false);
            BGFX.bgfx_vertex_layout_end(layout);

            // Create transient vertex buffer
            org.lwjgl.bgfx.BGFXTransientVertexBuffer tvb = org.lwjgl.bgfx.BGFXTransientVertexBuffer.create();
            BGFX.bgfx_alloc_transient_vertex_buffer(tvb, 3, layout);

            // Copy triangle data to transient buffer
            java.nio.ByteBuffer transientData = tvb.data();
            triangleData.rewind(); // Reset position to 0
            transientData.put(triangleData); // Direct buffer to direct buffer copy

            // Get position+color shader program
            short triangleProgram = com.vitra.render.bgfx.BgfxShaderCompiler.createPositionColorProgram();
            LOGGER.info("*** TRIANGLE TEST: Using shader program {} for triangle", triangleProgram);

            // CRITICAL FIX: Use same render state as working debug text
            // Debug text works, so triangle should use the same configuration
            long renderState = BGFX.BGFX_STATE_WRITE_RGB | BGFX.BGFX_STATE_WRITE_A | BGFX.BGFX_STATE_DEPTH_TEST_ALWAYS;
            LOGGER.info("*** RENDER STATE FIX: Triangle using same state as debug text (always pass depth)");
            BGFX.bgfx_set_state(renderState, 0);

            // Set identity model matrix for triangle
            float[] identityMatrix = {
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
            };
            BGFX.bgfx_set_transform(identityMatrix);

            // Set transient vertex buffer
            BGFX.bgfx_set_transient_vertex_buffer(0, tvb, 0, 3);

            // CRITICAL DEBUG: Validate everything before submit
            LOGGER.info("*** TRIANGLE VALIDATION: transient buffer data limit={}, program={}, renderState=0x{}",
                       tvb.data().limit(), triangleProgram, Long.toHexString(renderState));

            if (triangleProgram == BGFX.BGFX_INVALID_HANDLE) {
                LOGGER.error("*** TRIANGLE VALIDATION FAILED: Invalid shader program!");
                return;
            }

            // Submit triangle draw call
            BGFX.bgfx_submit(0, triangleProgram, 0, (byte)0);

            LOGGER.info("*** TRIANGLE TEST: Submitted white triangle - if this shows, BGFX pipeline works!");

        } catch (Exception e) {
            LOGGER.error("*** TRIANGLE TEST: Failed to draw test triangle", e);
        }
    }

    public void shutdown() {
        LOGGER.info("Shutting down BGFX window wrapper");
        bgfxInitialized = false;

        if (VitraMod.getRenderer() != null) {
            VitraMod.getRenderer().shutdown();
        }
    }
}