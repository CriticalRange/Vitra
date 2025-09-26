package com.vitra.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for matrix operations for BGFX rendering
 */
public class MatrixUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("MatrixUtil");

    // Store matrices for BGFX
    private static float[] lastProjectionMatrix = null;
    private static float[] lastModelViewMatrix = null;

    /**
     * Set orthographic projection for main menu UI
     */
    public static void setMainMenuOrthographicProjection(int width, int height) {
        try {
            // Create orthographic projection matrix for 2D UI (main menu)
            float[] orthoMatrix = new float[16];

            // Orthographic projection: left=0, right=width, top=0, bottom=height, near=-1000, far=1000
            float left = 0.0f;
            float right = (float) width;
            float top = 0.0f;
            float bottom = (float) height;
            float zNear = -1000.0f;
            float zFar = 1000.0f;

            // Build orthographic projection matrix
            orthoMatrix[0] = 2.0f / (right - left);    // X scale
            orthoMatrix[1] = 0.0f;
            orthoMatrix[2] = 0.0f;
            orthoMatrix[3] = 0.0f;

            orthoMatrix[4] = 0.0f;
            orthoMatrix[5] = 2.0f / (top - bottom);    // Y scale (inverted for screen coordinates)
            orthoMatrix[6] = 0.0f;
            orthoMatrix[7] = 0.0f;

            orthoMatrix[8] = 0.0f;
            orthoMatrix[9] = 0.0f;
            orthoMatrix[10] = -2.0f / (zFar - zNear);  // Z scale
            orthoMatrix[11] = 0.0f;

            orthoMatrix[12] = -(right + left) / (right - left);    // X translation
            orthoMatrix[13] = -(top + bottom) / (top - bottom);    // Y translation
            orthoMatrix[14] = -(zFar + zNear) / (zFar - zNear);   // Z translation
            orthoMatrix[15] = 1.0f;

            // Identity view matrix for 2D UI
            float[] identityView = new float[16];
            identityView[0] = 1.0f; identityView[5] = 1.0f; identityView[10] = 1.0f; identityView[15] = 1.0f;

            lastProjectionMatrix = orthoMatrix;
            lastModelViewMatrix = identityView;

            LOGGER.info("*** MATRIX CAPTURE: Set orthographic projection for main menu UI ({}x{})", width, height);

        } catch (Exception e) {
            LOGGER.error("Error setting orthographic projection", e);
        }
    }

    /**
     * Get the last projection matrix
     */
    public static float[] getLastProjectionMatrix() {
        return lastProjectionMatrix;
    }

    /**
     * Get the last model-view matrix
     */
    public static float[] getLastModelViewMatrix() {
        return lastModelViewMatrix;
    }

    /**
     * Set projection matrix from captured Minecraft data
     */
    public static void setProjectionMatrix(float[] projectionMatrix) {
        if (projectionMatrix != null && projectionMatrix.length == 16) {
            lastProjectionMatrix = projectionMatrix.clone();
            LOGGER.debug("*** MATRIX UTIL: Updated projection matrix from Minecraft capture");
        }
    }

    /**
     * Set model-view matrix from captured Minecraft data
     */
    public static void setModelViewMatrix(float[] modelViewMatrix) {
        if (modelViewMatrix != null && modelViewMatrix.length == 16) {
            lastModelViewMatrix = modelViewMatrix.clone();
            LOGGER.debug("*** MATRIX UTIL: Updated model-view matrix from Minecraft capture");
        }
    }

    /**
     * Check if both matrices are available
     */
    public static boolean hasValidMatrices() {
        return lastProjectionMatrix != null && lastModelViewMatrix != null;
    }
}