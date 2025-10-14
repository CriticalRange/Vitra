package com.vitra.debug;

import com.vitra.render.jni.VitraNativeRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug helper to manually set projection matrix for testing
 */
public class MatrixDebugHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("MatrixDebugHelper");
    private static boolean projectionSet = false;

    /**
     * Forcefully set an orthographic projection matrix for GUI/2D rendering
     * This is a TEST to verify that the black screen is caused by missing projection matrix
     *
     * Call this before any draw calls to set a working projection matrix.
     */
    public static void forceOrthographicProjection(int windowWidth, int windowHeight) {
        if (!VitraNativeRenderer.isInitialized()) {
            return;
        }

        // Set orthographic projection for 2D screen space (0,0) to (width,height)
        // left=0, right=width, bottom=height, top=0 (Y-down coordinate system)
        // near=-1000, far=3000 (standard Minecraft depth range for GUI)
        VitraNativeRenderer.setOrthographicProjection(
            0.0f,                    // left
            (float)windowWidth,      // right
            (float)windowHeight,     // bottom
            0.0f,                    // top
            -1000.0f,                // zNear
            3000.0f                  // zFar
        );

        if (!projectionSet) {
            LOGGER.info("======================================");
            LOGGER.info("FORCED ORTHOGRAPHIC PROJECTION SET!");
            LOGGER.info("Window: {}x{}", windowWidth, windowHeight);
            LOGGER.info("This is a TEST projection matrix for debugging");
            LOGGER.info("======================================");
            projectionSet = true;
        }
    }

    /**
     * Reset the projection set flag
     */
    public static void reset() {
        projectionSet = false;
    }
}
