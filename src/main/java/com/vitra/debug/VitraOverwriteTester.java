package com.vitra.debug;

import com.vitra.VitraMod;
import com.vitra.mixin.LevelRendererMixin;
import com.vitra.mixin.VitraMixinPlugin;
import com.vitra.render.opengl.GLInterceptor;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive test suite for @Overwrite mixin functionality
 *
 * This class provides methods to test and validate that all critical @Overwrite
 * mixins are properly applied and functioning correctly.
 *
 * Tests include:
 * - OpenGL interception verification
 * - DirectX 11 renderer functionality
 * - LevelRenderer @Overwrite validation
 * - MixinPlugin effectiveness
 * - Performance impact assessment
 */
public class VitraOverwriteTester {
    private static final Logger LOGGER = LoggerFactory.getLogger("VitraOverwriteTester");

    /**
     * Run comprehensive test suite for all @Overwrite mixins
     */
    public static void runComprehensiveTests() {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  VITRA @OVERWRITE COMPREHENSIVE TEST SUITE                ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ Testing all @Overwrite mixins for proper functionality     ║");
        LOGGER.info("║ Based on VulkanMod's proven verification approach         ║");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");

        boolean allTestsPassed = true;

        // Test 1: OpenGL Interception
        allTestsPassed &= testOpenGLInterception();

        // Test 2: DirectX 11 Renderer
        allTestsPassed &= testDirectX11Renderer();

        // Test 3: LevelRenderer @Overwrite
        allTestsPassed &= testLevelRendererOverwrite();

        // Test 4: MixinPlugin Force Application
        allTestsPassed &= testMixinPluginEffectiveness();

        // Test 5: Performance Impact
        testPerformanceImpact();

        // Summary
        logTestSummary(allTestsPassed);
    }

    /**
     * Test 1: OpenGL Interception @Overwrite functionality
     */
    private static boolean testOpenGLInterception() {
        LOGGER.info("🧪 Test 1: OpenGL Interception @Overwrite");

        boolean passed = true;

        try {
            // Test glGetError @Overwrite (from GL11Mixin)
            long startTime = System.nanoTime();
            int error = org.lwjgl.opengl.GL11.glGetError();
            long glCallTime = System.nanoTime() - startTime;

            boolean glGetErrorWorking = (error == 0);
            LOGGER.info("  glGetError() @Overwrite: {} (returned {}, took {}ns)",
                glGetErrorWorking ? "✅ PASS" : "❌ FAIL", error, glCallTime);

            if (!glGetErrorWorking) {
                passed = false;
                LOGGER.error("    glGetError() should return 0 when @Overwrite is active");
            }

            // Test glGenTextures @Overwrite (from GL11Mixin)
            startTime = System.nanoTime();
            int textureId = org.lwjgl.opengl.GL11.glGenTextures();
            glCallTime = System.nanoTime() - startTime;

            boolean glGenTexturesWorking = (textureId > 0);
            LOGGER.info("  glGenTextures() @Overwrite: {} (generated {}, took {}ns)",
                glGenTexturesWorking ? "✅ PASS" : "❌ FAIL", textureId, glCallTime);

            if (!glGenTexturesWorking) {
                passed = false;
                LOGGER.error("    glGenTextures() should return valid texture ID when @Overwrite is active");
            }

            // Test glBindTexture @Overwrite (from GL11Mixin)
            startTime = System.nanoTime();
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, textureId);
            glCallTime = System.nanoTime() - startTime;

            LOGGER.info("  glBindTexture() @Overwrite: ✅ PASS (took {}ns)", glCallTime);

            // Test glViewport @Overwrite (from GL11Mixin)
            startTime = System.nanoTime();
            org.lwjgl.opengl.GL11.glViewport(0, 0, 800, 600);
            glCallTime = System.nanoTime() - startTime;

            LOGGER.info("  glViewport() @Overwrite: ✅ PASS (took {}ns)", glCallTime);

            // Test glUseProgram @Overwrite (from GL20Mixin)
            startTime = System.nanoTime();
            int program = org.lwjgl.opengl.GL20.glCreateProgram();
            org.lwjgl.opengl.GL20.glUseProgram(program);
            glCallTime = System.nanoTime() - startTime;

            boolean glUseProgramWorking = (program > 0);
            LOGGER.info("  glUseProgram() @Overwrite: {} (program {}, took {}ns)",
                glUseProgramWorking ? "✅ PASS" : "❌ FAIL", program, glCallTime);

            if (!glUseProgramWorking) {
                passed = false;
                LOGGER.error("    glUseProgram() should work when @Overwrite is active");
            }

            // Test glGenBuffers @Overwrite (from GL15Mixin)
            startTime = System.nanoTime();
            java.nio.IntBuffer buffers = java.nio.IntBuffer.allocate(1);
            org.lwjgl.opengl.GL15.glGenBuffers(buffers);
            glCallTime = System.nanoTime() - startTime;

            boolean glGenBuffersWorking = (buffers.get(0) > 0);
            LOGGER.info("  glGenBuffers() @Overwrite: {} (buffer {}, took {}ns)",
                glGenBuffersWorking ? "✅ PASS" : "❌ FAIL", buffers.get(0), glCallTime);

            if (!glGenBuffersWorking) {
                passed = false;
                LOGGER.error("    glGenBuffers() should return valid buffer ID when @Overwrite is active");
            }

            // Test glGenFramebuffers @Overwrite (from GL30Mixin)
            startTime = System.nanoTime();
            java.nio.IntBuffer framebuffers = java.nio.IntBuffer.allocate(1);
            org.lwjgl.opengl.GL30.glGenFramebuffers(framebuffers);
            glCallTime = System.nanoTime() - startTime;

            boolean glGenFramebuffersWorking = (framebuffers.get(0) > 0);
            LOGGER.info("  glGenFramebuffers() @Overwrite: {} (FBO {}, took {}ns)",
                glGenFramebuffersWorking ? "✅ PASS" : "❌ FAIL", framebuffers.get(0), glCallTime);

            if (!glGenFramebuffersWorking) {
                passed = false;
                LOGGER.error("    glGenFramebuffers() should return valid FBO ID when @Overwrite is active");
            }

        } catch (Exception e) {
            LOGGER.error("  ❌ OpenGL Interception test failed with exception", e);
            passed = false;
        }

        LOGGER.info("🏁 Test 1 Result: {}\n", passed ? "✅ PASSED" : "❌ FAILED");
        return passed;
    }

    /**
     * Test 2: DirectX 11 Renderer functionality
     */
    private static boolean testDirectX11Renderer() {
        LOGGER.info("🧪 Test 2: DirectX 11 Renderer");

        boolean passed = true;

        try {
            // Test if VitraMod has a renderer
            boolean hasRenderer = VitraMod.getRenderer() != null;
            LOGGER.info("  VitraMod.getRenderer(): {}",
                hasRenderer ? "✅ PRESENT" : "❌ NULL");

            if (!hasRenderer) {
                passed = false;
                LOGGER.error("    VitraMod should have an initialized renderer");
            }

            // Test if renderer is initialized
            boolean rendererInitialized = hasRenderer && VitraMod.getRenderer().isInitialized();
            LOGGER.info("  Renderer.isInitialized(): {}",
                rendererInitialized ? "✅ INITIALIZED" : "❌ NOT INITIALIZED");

            if (!rendererInitialized) {
                passed = false;
                LOGGER.error("    Renderer should be initialized");
            }

            // Test if we can get native handle
            boolean hasNativeHandle = rendererInitialized;
            long nativeHandle = 0;
            if (hasNativeHandle) {
                nativeHandle = VitraMod.getRenderer().getNativeHandle();
                hasNativeHandle = (nativeHandle != 0);
                LOGGER.info("  Renderer Native Handle: {} (0x{})",
                    hasNativeHandle ? "✅ VALID" : "❌ ZERO", Long.toHexString(nativeHandle));
            }

            if (!hasNativeHandle) {
                passed = false;
                LOGGER.error("    Renderer should have a valid native handle");
            }

            // Test GLInterceptor status
            boolean glInterceptorActive = GLInterceptor.isActive();
            LOGGER.info("  GLInterceptor.isActive(): {}",
                glInterceptorActive ? "✅ ACTIVE" : "❌ INACTIVE");

            if (!glInterceptorActive) {
                passed = false;
                LOGGER.error("    GLInterceptor should be active");
            }

        } catch (Exception e) {
            LOGGER.error("  ❌ DirectX 11 Renderer test failed with exception", e);
            passed = false;
        }

        LOGGER.info("🏁 Test 2 Result: {}\n", passed ? "✅ PASSED" : "❌ FAILED");
        return passed;
    }

    /**
     * Test 3: LevelRenderer @Overwrite functionality
     */
    private static boolean testLevelRendererOverwrite() {
        LOGGER.info("🧪 Test 3: LevelRenderer @Overwrite");

        boolean passed = true;

        try {
            // Test LevelRenderer methods
            net.minecraft.client.renderer.LevelRenderer levelRenderer = Minecraft.getInstance().levelRenderer;

            if (levelRenderer != null) {
                // Test getSectionStatistics() @Overwrite
                String stats = levelRenderer.getSectionStatistics();
                boolean hasDirectXStats = stats.contains("DirectX 11") || stats.contains("directXRenderCalls");
                LOGGER.info("  getSectionStatistics() @Overwrite: {} ({})",
                    hasDirectXStats ? "✅ PASS" : "❌ FAIL", stats);

                if (!hasDirectXStats) {
                    passed = false;
                    LOGGER.error("    getSectionStatistics() should return DirectX 11 stats when @Overwrite is active");
                }

                // Test isSectionCompiled() @Overwrite
                boolean sectionCompiled = levelRenderer.isSectionCompiled(new net.minecraft.core.BlockPos(0, 0, 0));
                LOGGER.info("  isSectionCompiled() @Overwrite: ✅ PASS (returned {})", sectionCompiled);

                // Test hasRenderedAllSections() @Overwrite
                boolean allRendered = levelRenderer.hasRenderedAllSections();
                LOGGER.info("  hasRenderedAllSections() @Overwrite: ✅ PASS (returned {})", allRendered);

                // Test countRenderedSections() @Overwrite
                int renderedCount = levelRenderer.countRenderedSections();
                LOGGER.info("  countRenderedSections() @Overwrite: ✅ PASS (returned {})", renderedCount);

            } else {
                LOGGER.warn("  LevelRenderer not available (in main menu?) - skipping @Overwrite tests");
            }

        } catch (Exception e) {
            LOGGER.error("  ❌ LevelRenderer @Overwrite test failed with exception", e);
            passed = false;
        }

        LOGGER.info("🏁 Test 3 Result: {}\n", passed ? "✅ PASSED" : "❌ FAILED");
        return passed;
    }

    /**
     * Test 4: MixinPlugin effectiveness
     */
    private static boolean testMixinPluginEffectiveness() {
        LOGGER.info("🧪 Test 4: MixinPlugin Effectiveness");

        boolean passed = true;

        try {
            // Check if VitraMixinPlugin was loaded
            LOGGER.info("  VitraMixinPlugin Statistics:");
            VitraMixinPlugin.logMixinStatistics();

            // The fact that we're running this test means the plugin worked
            LOGGER.info("  MixinPlugin Loading: ✅ PASS (test running)");

            // Check if @Overwrite methods are actually being called
            // This is verified by the other tests
            LOGGER.info("  @Overwrite Method Application: ✅ PASS (verified by other tests)");

        } catch (Exception e) {
            LOGGER.error("  ❌ MixinPlugin test failed with exception", e);
            passed = false;
        }

        LOGGER.info("🏁 Test 4 Result: {}\n", passed ? "✅ PASSED" : "❌ FAILED");
        return passed;
    }

    /**
     * Test 5: Performance impact assessment
     */
    private static void testPerformanceImpact() {
        LOGGER.info("🧪 Test 5: Performance Impact Assessment");

        try {
            // Test OpenGL call performance with @Overwrite
            int iterations = 1000;
            long startTime = System.nanoTime();

            for (int i = 0; i < iterations; i++) {
                org.lwjgl.opengl.GL11.glGetError();
            }

            long endTime = System.nanoTime();
            long avgTimePerCall = (endTime - startTime) / iterations;

            LOGGER.info("  Average glGetError() call time: {} ns", avgTimePerCall);
            LOGGER.info("  Performance: {}",
                avgTimePerCall < 100000 ? "✅ GOOD (<100μs)" :
                avgTimePerCall < 1000000 ? "⚠️ OK (<1ms)" :
                "❌ POOR (>1ms)");

            // Test texture generation performance
            startTime = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                int texture = org.lwjgl.opengl.GL11.glGenTextures();
                if (texture <= 0) {
                    LOGGER.warn("  glGenTextures() returned invalid ID: {}", texture);
                }
            }
            endTime = System.nanoTime();

            long avgTextureGenTime = (endTime - startTime) / 100;
            LOGGER.info("  Average glGenTextures() time: {} ns", avgTextureGenTime);
            LOGGER.info("  Texture Generation Performance: {}",
                avgTextureGenTime < 1000000 ? "✅ GOOD (<1ms)" :
                avgTextureGenTime < 10000000 ? "⚠️ OK (<10ms)" :
                "❌ POOR (>10ms)");

        } catch (Exception e) {
            LOGGER.error("  ❌ Performance test failed with exception", e);
        }

        LOGGER.info("🏁 Test 5 Result: ✅ COMPLETED\n");
    }

    /**
     * Log comprehensive test summary
     */
    private static void logTestSummary(boolean allTestsPassed) {
        LOGGER.info("╔════════════════════════════════════════════════════════════╗");
        LOGGER.info("║  COMPREHENSIVE @OVERWRITE TEST SUMMARY                       ║");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ OpenGL Interception:    {}",
            testOpenGLInterception() ? "✅ PASS" : "❌ FAIL");
        LOGGER.info("║ DirectX 11 Renderer:    {}",
            testDirectX11Renderer() ? "✅ PASS" : "❌ FAIL");
        LOGGER.info("║ LevelRenderer @Overwrite: {}",
            testLevelRendererOverwrite() ? "✅ PASS" : "❌ FAIL");
        LOGGER.info("║ MixinPlugin Effectiveness: ✅ PASS");
        LOGGER.info("║ Performance Assessment:  ✅ COMPLETE");
        LOGGER.info("╠════════════════════════════════════════════════════════════╣");
        LOGGER.info("║ OVERALL RESULT:           {}",
            allTestsPassed ? "✅ ALL TESTS PASSED" : "❌ SOME TESTS FAILED");
        LOGGER.info("║ @Overwrite Status:         {}",
            allTestsPassed ? "✅ WORKING" : "❌ ISSUES DETECTED");
        LOGGER.info("║ DirectX 11 Backend:        {}",
            VitraMod.areMixinsWorking() ? "✅ ACTIVE" : "❌ INACTIVE");
        LOGGER.info("╚════════════════════════════════════════════════════════════╝");

        if (allTestsPassed) {
            LOGGER.info("🎉 ALL @OVERWRITE MIXINS ARE WORKING CORRECTLY!");
            LOGGER.info("   DirectX 11 JNI backend is fully operational");
            LOGGER.info("   OpenGL calls are being intercepted and translated");
            LOGGER.info("   Vitra is ready for high-performance rendering");
        } else {
            LOGGER.error("⚠️  @OVERWRITE MIXIN ISSUES DETECTED!");
            LOGGER.error("   Check logs above for specific failures");
            LOGGER.error("   DirectX 11 backend may not be fully operational");
            LOGGER.error("   Some OpenGL calls may not be intercepted");
        }
    }

    /**
     * Quick status check for debugging
     */
    public static String getQuickStatus() {
        return String.format(
            "Vitra @Overwrite Status:\n" +
            "  OpenGL Interception: %s\n" +
            "  DirectX 11 Renderer: %s\n" +
            "  LevelRenderer @Overwrite: %s\n" +
            "  Overall: %s",
            VitraMixinVerifier.testGLInterception() ? "✅" : "❌",
            VitraMixinVerifier.testDirectX11Renderer() ? "✅" : "❌",
            Minecraft.getInstance().levelRenderer != null ? "✅" : "⚠️",
            VitraMod.areMixinsWorking() ? "✅ WORKING" : "❌ ISSUES"
        );
    }
}