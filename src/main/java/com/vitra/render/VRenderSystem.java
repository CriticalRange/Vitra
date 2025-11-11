package com.vitra.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.vitra.VitraMod;
import com.vitra.util.MappedBuffer;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/**
 * DirectX Render System - CPU-side uniform storage
 *
 * Based on VulkanMod's VRenderSystem pattern.
 * Stores all Minecraft shader uniforms in native memory (MappedBuffers) for zero-copy upload to constant buffers.
 *
 * Architecture:
 * - All uniform data stored in off-heap direct ByteBuffers
 * - Suppliers retrieve these buffers for constant buffer updates
 * - Zero-copy: native pointers passed directly to JNI for GPU upload
 *
 * Uniform categories:
 * - Matrices (64 bytes each): ModelView, Projection, Texture, MVP
 * - Vectors (12-16 bytes): Colors, fog parameters, light directions
 * - Scalars (4 bytes): Time, fog parameters, flags
 */
public class VRenderSystem {

    // ==================== MATRIX UNIFORMS (64 bytes each) ====================

    /**
     * Model-View matrix (transforms from model space to view space)
     * Updated by Minecraft via RenderSystem.setModelViewMatrix()
     */
    private static final MappedBuffer modelViewMatrix = new MappedBuffer(16 * 4); // 64 bytes

    /**
     * Projection matrix (transforms from view space to clip space)
     * Updated by Minecraft via RenderSystem.setProjectionMatrix()
     */
    private static final MappedBuffer projectionMatrix = new MappedBuffer(16 * 4); // 64 bytes

    /**
     * Texture matrix (for texture coordinate transformation)
     * Updated by Minecraft via RenderSystem.setTextureMatrix()
     */
    private static final MappedBuffer textureMatrix = new MappedBuffer(16 * 4); // 64 bytes

    /**
     * Model-View-Projection combined matrix (computed)
     * MVP = Projection * ModelView
     */
    private static final MappedBuffer MVP = new MappedBuffer(16 * 4); // 64 bytes

    /**
     * Inverse View-Rotation matrix (for skybox rendering)
     */
    private static final MappedBuffer inverseViewRotationMatrix = new MappedBuffer(16 * 4); // 64 bytes

    // ==================== VECTOR UNIFORMS ====================

    /**
     * Shader color modulator (RGBA multiplier for all fragments)
     * Default: (1, 1, 1, 1) - no modulation
     */
    private static final MappedBuffer shaderColor = new MappedBuffer(4 * 4); // 16 bytes (vec4)

    /**
     * Fog color (RGB + alpha)
     */
    private static final MappedBuffer shaderFogColor = new MappedBuffer(4 * 4); // 16 bytes (vec4)

    /**
     * Light direction 0 (primary light)
     */
    public static final MappedBuffer lightDirection0 = new MappedBuffer(3 * 4); // 12 bytes (vec3, padded to 16)

    /**
     * Light direction 1 (secondary light)
     */
    public static final MappedBuffer lightDirection1 = new MappedBuffer(3 * 4); // 12 bytes (vec3, padded to 16)

    /**
     * Model offset (chunk offset for terrain rendering)
     */
    public static final MappedBuffer modelOffset = new MappedBuffer(3 * 4); // 12 bytes (vec3, padded to 16)

    /**
     * Screen size (width, height) for GUI shaders
     */
    private static final MappedBuffer screenSize = new MappedBuffer(2 * 4); // 8 bytes (vec2, padded to 16)

    // ==================== SCALAR UNIFORMS ====================

    /**
     * Fog start distance
     */
    private static float fogStart = 0.0f;

    /**
     * Fog end distance
     */
    private static float fogEnd = 1.0f;

    /**
     * Fog shape (0 = sphere, 1 = cylinder)
     */
    private static int fogShape = 0;

    /**
     * Game time (for animated shaders)
     */
    private static float gameTime = 0.0f;

    /**
     * Line width (for line rendering)
     */
    private static float lineWidth = 1.0f;

    /**
     * Alpha cutout threshold (for cutout shaders)
     */
    public static float alphaCutout = 0.0f;

    // ==================== INITIALIZATION ====================

    static {
        // Initialize matrices to identity
        float[] identity = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };

        modelViewMatrix.putFloatArray(0, identity);
        projectionMatrix.putFloatArray(0, identity);
        textureMatrix.putFloatArray(0, identity);
        MVP.putFloatArray(0, identity);
        inverseViewRotationMatrix.putFloatArray(0, identity);

        // Initialize shader color to white (no modulation)
        shaderColor.putVec4(0, 1.0f, 1.0f, 1.0f, 1.0f);

        // Initialize fog color to black
        shaderFogColor.putVec4(0, 0.0f, 0.0f, 0.0f, 1.0f);

        // Initialize screen size to default
        screenSize.putVec2(0, 1920, 1080);
    }

    // ==================== MATRIX GETTERS (for Uniforms suppliers) ====================

    public static MappedBuffer getModelViewMatrix() {
        return modelViewMatrix;
    }

    public static MappedBuffer getProjectionMatrix() {
        return projectionMatrix;
    }

    public static MappedBuffer getTextureMatrix() {
        return textureMatrix;
    }

    private static int getMVPCallCount = 0;

    public static MappedBuffer getMVP() {
        // DEBUG: Log first 10 calls to see what MVP contains when supplier is called
        if (getMVPCallCount < 10) {
            java.nio.FloatBuffer fb = MVP.byteBuffer().asFloatBuffer();
            System.out.println("[GET_MVP_SUPPLIER " + getMVPCallCount + "] Returning MVP, first 4 floats: " +
                fb.get(0) + ", " + fb.get(1) + ", " + fb.get(2) + ", " + fb.get(3));
            getMVPCallCount++;
        }
        return MVP;
    }

    public static MappedBuffer getInverseViewRotationMatrix() {
        return inverseViewRotationMatrix;
    }

    // ==================== VECTOR GETTERS ====================

    public static MappedBuffer getShaderColor() {
        return shaderColor;
    }

    public static MappedBuffer getShaderFogColor() {
        return shaderFogColor;
    }

    public static MappedBuffer getScreenSize() {
        return screenSize;
    }

    // ==================== SCALAR GETTERS ====================

    public static float getFogStart() {
        return fogStart;
    }

    public static float getFogEnd() {
        return fogEnd;
    }

    public static int getFogShape() {
        return fogShape;
    }

    public static float getGameTime() {
        return gameTime;
    }

    public static float getLineWidth() {
        return lineWidth;
    }

    // ==================== MATRIX SETTERS ====================

    // Debug counters for matrix logging
    private static int matrixSetCount = 0;
    private static int mvpComputeCount = 0;

    /**
     * Set model-view matrix from Minecraft's Matrix4f
     * CRITICAL: This is called from RenderSystemMixin after every modelview matrix change
     */
    public static void setModelViewMatrix(Matrix4f matrix) {
        // Store column-major (JOML default)
        matrix.get(0, modelViewMatrix.byteBuffer());

        // DEBUG: Log first 20 matrix sets AND any non-identity matrices
        boolean isIdentity = matrix.m00() == 1.0f && matrix.m11() == 1.0f && matrix.m22() == 1.0f && matrix.m33() == 1.0f &&
                             matrix.m01() == 0.0f && matrix.m02() == 0.0f && matrix.m03() == 0.0f &&
                             matrix.m10() == 0.0f && matrix.m12() == 0.0f && matrix.m13() == 0.0f &&
                             matrix.m20() == 0.0f && matrix.m21() == 0.0f && matrix.m23() == 0.0f &&
                             matrix.m30() == 0.0f && matrix.m31() == 0.0f && matrix.m32() == 0.0f;

        if (matrixSetCount < 20 || !isIdentity) {
            System.out.println("[VRENDERSSYSTEM_SET_MV " + matrixSetCount + (isIdentity ? " IDENTITY" : " NON-IDENTITY") + "] ModelView matrix set:");
            System.out.println("  Row 0: " + matrix.m00() + ", " + matrix.m01() + ", " + matrix.m02() + ", " + matrix.m03());
            System.out.println("  Row 1: " + matrix.m10() + ", " + matrix.m11() + ", " + matrix.m12() + ", " + matrix.m13());
            System.out.println("  Row 2: " + matrix.m20() + ", " + matrix.m21() + ", " + matrix.m22() + ", " + matrix.m23());
            System.out.println("  Row 3: " + matrix.m30() + ", " + matrix.m31() + ", " + matrix.m32() + ", " + matrix.m33());
        }
        matrixSetCount++;

        updateMVP();
    }

    /**
     * Apply model-view matrix (VulkanMod compatibility method)
     * Same as setModelViewMatrix but matches VulkanMod's naming
     */
    public static void applyModelViewMatrix(Matrix4f matrix) {
        setModelViewMatrix(matrix);
    }

    /**
     * Set projection matrix from Minecraft's Matrix4f
     * CRITICAL: This is called from RenderSystemMixin after every projection matrix change
     */
    public static void setProjectionMatrix(Matrix4f matrix) {
        // Store column-major (JOML default)
        matrix.get(0, projectionMatrix.byteBuffer());

        // DEBUG: Log first projection matrix set
        if (matrixSetCount < 1) {
            System.out.println("[VRENDERSYSTEM_SET_PROJ] Projection matrix set:");
            System.out.println("  Row 0: " + matrix.m00() + ", " + matrix.m01() + ", " + matrix.m02() + ", " + matrix.m03());
            System.out.println("  Row 1: " + matrix.m10() + ", " + matrix.m11() + ", " + matrix.m12() + ", " + matrix.m13());
            System.out.println("  Row 2: " + matrix.m20() + ", " + matrix.m21() + ", " + matrix.m22() + ", " + matrix.m23());
            System.out.println("  Row 3: " + matrix.m30() + ", " + matrix.m31() + ", " + matrix.m32() + ", " + matrix.m33());
        }

        updateMVP();
    }

    /**
     * Apply projection matrix (VulkanMod compatibility method)
     * Same as setProjectionMatrix but matches VulkanMod's naming
     */
    public static void applyProjectionMatrix(Matrix4f matrix) {
        setProjectionMatrix(matrix);
    }

    /**
     * Set texture matrix from Minecraft's Matrix4f
     */
    public static void setTextureMatrix(Matrix4f matrix) {
        matrix.get(0, textureMatrix.byteBuffer());
    }

    /**
     * Set inverse view-rotation matrix from Minecraft's Matrix4f
     */
    public static void setInverseViewRotationMatrix(Matrix4f matrix) {
        matrix.get(0, inverseViewRotationMatrix.byteBuffer());
    }

    /**
     * Update MVP matrix (MVP = Projection * ModelView)
     * Called automatically when ModelView or Projection changes
     *
     * CRITICAL: VulkanMod pattern - this is called after EVERY matrix update
     * to ensure shaders always have the correct combined transformation matrix.
     * Without this, shaders render with incorrect/garbage MVP data.
     */
    private static void updateMVP() {
        // Create JOML matrices from buffers
        Matrix4f mv = new Matrix4f();
        Matrix4f proj = new Matrix4f();

        mv.set(modelViewMatrix.byteBuffer());
        proj.set(projectionMatrix.byteBuffer());

        // Compute MVP = Projection * ModelView
        Matrix4f mvp = new Matrix4f(proj).mul(mv);

        // DEBUG: Log first 20 MVP computations AND any non-identity MVP
        boolean mvpIsIdentity = mvp.m00() == 1.0f && mvp.m11() == 1.0f && mvp.m22() == 1.0f && mvp.m33() == 1.0f &&
                                mvp.m01() == 0.0f && mvp.m02() == 0.0f && mvp.m03() == 0.0f;
        if (mvpComputeCount < 20 || !mvpIsIdentity) {
            System.out.println("[MVP_COMPUTE " + mvpComputeCount + (mvpIsIdentity ? " IDENTITY" : " NON-IDENTITY") + "] BEFORE transpose:");
            System.out.println("  MV row3: " + mv.m30() + ", " + mv.m31() + ", " + mv.m32() + ", " + mv.m33());
            System.out.println("  Proj row0: " + proj.m00() + ", " + proj.m01() + ", " + proj.m02() + ", " + proj.m03());
            System.out.println("  MVP row0: " + mvp.m00() + ", " + mvp.m01() + ", " + mvp.m02() + ", " + mvp.m03());
            System.out.println("  MVP row3: " + mvp.m30() + ", " + mvp.m31() + ", " + mvp.m32() + ", " + mvp.m33());
        }
        mvpComputeCount++;

        // Store column-major (JOML default) - matches HLSL column_major (no pragma)
        mvp.get(0, MVP.byteBuffer());

        // DEBUG: Verify MVP was stored correctly
        if (mvpComputeCount <= 10 && !mvpIsIdentity) {
            System.out.println("[MVP_STORED] After storing to MappedBuffer:");
            System.out.println("  Reading back first 4 floats from MVP buffer:");
            java.nio.FloatBuffer fb = MVP.byteBuffer().asFloatBuffer();
            System.out.println("  " + fb.get(0) + ", " + fb.get(1) + ", " + fb.get(2) + ", " + fb.get(3));
        }
    }

    /**
     * Calculate MVP matrix (VulkanMod compatibility method)
     * This is the exact same as updateMVP() but matches VulkanMod's naming convention.
     *
     * VulkanMod calls this from RenderSystemMixin after every matrix change:
     * - setProjectionMatrix() → calculateMVP()
     * - applyModelViewMatrix() → calculateMVP()
     */
    public static void calculateMVP() {
        updateMVP();
    }

    // ==================== VECTOR SETTERS ====================

    /**
     * Set shader color modulator (RGBA)
     */
    public static void setShaderColor(float r, float g, float b, float a) {
        shaderColor.putVec4(0, r, g, b, a);
    }

    /**
     * Set shader color from float array
     */
    public static void setShaderColor(float[] color) {
        if (color.length >= 4) {
            shaderColor.putVec4(0, color);
        }
    }

    /**
     * Set fog color (RGBA)
     */
    public static void setShaderFogColor(float r, float g, float b, float a) {
        shaderFogColor.putVec4(0, r, g, b, a);
    }

    /**
     * Set fog color from Minecraft's RenderSystem
     */
    public static void updateShaderFogColor() {
        float[] fogColor = RenderSystem.getShaderFogColor();
        setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], 1.0f);
    }

    /**
     * Set screen size (width, height)
     */
    public static void setScreenSize(float width, float height) {
        screenSize.putVec2(0, width, height);
    }

    // ==================== SCALAR SETTERS ====================

    /**
     * Set fog start distance
     */
    public static void setFogStart(float start) {
        fogStart = start;
    }

    /**
     * Set fog end distance
     */
    public static void setFogEnd(float end) {
        fogEnd = end;
    }

    /**
     * Set fog shape (0 = sphere, 1 = cylinder)
     */
    public static void setFogShape(int shape) {
        fogShape = shape;
    }

    /**
     * Set game time (for animated shaders)
     */
    public static void setGameTime(float time) {
        gameTime = time;
    }

    /**
     * Set line width
     */
    public static void setLineWidth(float width) {
        lineWidth = width;
    }

    // ==================== INITIALIZATION ====================

    /**
     * Initialize VRenderSystem
     * Called during renderer initialization
     */
    public static void initialize() {
        // Initialize default values
        // MappedBuffers are already allocated in static initializers

        // Set identity matrices
        Matrix4f identity = new Matrix4f();
        setModelViewMatrix(identity);
        setProjectionMatrix(identity);
        setTextureMatrix(identity);
        setInverseViewRotationMatrix(identity);

        // Set default colors
        setShaderColor(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        setShaderFogColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Set default scalars
        setFogStart(0.0f);
        setFogEnd(1.0f);
        setFogShape(0);
        setGameTime(0);
        setLineWidth(1.0f);
    }

    // ==================== SYNC WITH RENDERSYSTEM ====================

    /**
     * Sync all uniforms from Minecraft's RenderSystem
     * Called once per frame before rendering
     */
    public static void syncFromRenderSystem() {
        // Sync matrices
        Matrix4f mv = RenderSystem.getModelViewMatrix();
        Matrix4f proj = RenderSystem.getProjectionMatrix();
        Matrix4f tex = RenderSystem.getTextureMatrix();

        if (mv != null) setModelViewMatrix(mv);
        if (proj != null) setProjectionMatrix(proj);
        if (tex != null) setTextureMatrix(tex);

        // Sync scalar uniforms
        setFogStart(RenderSystem.getShaderFogStart());
        setFogEnd(RenderSystem.getShaderFogEnd());
        setFogShape(RenderSystem.getShaderFogShape().getIndex());
        setGameTime(RenderSystem.getShaderGameTime());
        setLineWidth(RenderSystem.getShaderLineWidth());

        // Sync fog color
        updateShaderFogColor();

        // Sync shader color
        float[] color = RenderSystem.getShaderColor();
        setShaderColor(color);
    }

    // ==================== DIRTY TRACKING ====================

    // ==================== CLEANUP ====================

    /**
     * Free all native memory
     * Called on mod shutdown
     */
    public static void cleanup() {
        modelViewMatrix.free();
        projectionMatrix.free();
        textureMatrix.free();
        MVP.free();
        inverseViewRotationMatrix.free();
        shaderColor.free();
        shaderFogColor.free();
        lightDirection0.free();
        lightDirection1.free();
        modelOffset.free();
        screenSize.free();
    }
}
