package com.vitra.mixin.compatibility.gl;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(GL15.class)
public class GL15Mixin {
    // VULKANMOD APPROACH: Only handle PIXEL_PACK/UNPACK buffers for texture copying
    // Vertex/Index buffers are handled by GlStateManagerM
    private static final AtomicInteger bufferIdCounter = new AtomicInteger(1);
    private static final Map<Integer, VitraGlBuffer> bufferMap = new HashMap<>();
    private static VitraGlBuffer pixelPackBuffer = null;
    private static VitraGlBuffer pixelUnpackBuffer = null;

    @Overwrite(remap = false)
    @NativeType("void")
    public static int glGenBuffers() {
        int id = bufferIdCounter.getAndIncrement();
        bufferMap.put(id, new VitraGlBuffer(id));
        return id;
    }

    @Overwrite(remap = false)
    public static void glBindBuffer(@NativeType("GLenum") int target, @NativeType("GLuint") int buffer) {
        VitraGlBuffer glBuffer = bufferMap.get(buffer);

        // Only handle pixel buffers - throw exception for vertex/index buffers to catch bugs early
        switch (target) {
            case GL32.GL_PIXEL_PACK_BUFFER:
                pixelPackBuffer = glBuffer;
                break;
            case GL32.GL_PIXEL_UNPACK_BUFFER:
                pixelUnpackBuffer = glBuffer;
                break;
            case GL15.GL_ARRAY_BUFFER:
            case GL15.GL_ELEMENT_ARRAY_BUFFER:
                // Vertex/Index buffers are handled by GlStateManagerM - don't interfere
                break;
            default:
                throw new IllegalStateException("GL15M: Unsupported buffer target: " + target);
        }
    }

    @Overwrite(remap = false)
    public static void glBufferData(@NativeType("GLenum") int target, @NativeType("void const *") ByteBuffer data, @NativeType("GLenum") int usage) {
        // Only handle pixel buffers
        if (target != GL32.GL_PIXEL_PACK_BUFFER && target != GL32.GL_PIXEL_UNPACK_BUFFER) {
            return; // Handled by GlStateManagerM
        }

        VitraGlBuffer buffer = getBufferForTarget(target);
        if (buffer != null && data != null) {
            buffer.allocate(data.remaining());
            ByteBuffer bufferData = buffer.getData();
            bufferData.put(data);
            data.rewind();
            bufferData.rewind();
        }
    }

    @Overwrite(remap = false)
    public static void glBufferData(int target, long size, int usage) {
        // Only handle pixel buffers
        if (target != GL32.GL_PIXEL_PACK_BUFFER && target != GL32.GL_PIXEL_UNPACK_BUFFER) {
            return; // Handled by GlStateManagerM
        }

        VitraGlBuffer buffer = getBufferForTarget(target);
        if (buffer != null) {
            buffer.allocate((int) size);
        }
    }

    @Overwrite(remap = false)
    @NativeType("void *")
    public static ByteBuffer glMapBuffer(@NativeType("GLenum") int target, @NativeType("GLenum") int access) {
        // Only handle pixel buffers
        if (target != GL32.GL_PIXEL_PACK_BUFFER && target != GL32.GL_PIXEL_UNPACK_BUFFER) {
            return null; // Handled by GlStateManagerM
        }

        VitraGlBuffer buffer = getBufferForTarget(target);
        if (buffer != null) {
            ByteBuffer mappedBuffer = buffer.getData();
            mappedBuffer.position(0);
            return mappedBuffer;
        }
        return null;
    }

    @Overwrite(remap = false)
    @Nullable
    @NativeType("void *")
    public static ByteBuffer glMapBuffer(@NativeType("GLenum") int target, @NativeType("GLenum") int access, long length, @Nullable ByteBuffer old_buffer) {
        return glMapBuffer(target, access);
    }

    @Overwrite(remap = false)
    @NativeType("GLboolean")
    public static boolean glUnmapBuffer(@NativeType("GLenum") int target) {
        return true;
    }

    @Overwrite(remap = false)
    public static void glDeleteBuffers(int buffer) {
        VitraGlBuffer glBuffer = bufferMap.remove(buffer);
        if (glBuffer != null) {
            glBuffer.free();
        }
    }

    @Overwrite(remap = false)
    public static void glDeleteBuffers(@NativeType("GLuint const *") IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) {
            glDeleteBuffers(buffers.get(i));
        }
    }

    private static VitraGlBuffer getBufferForTarget(int target) {
        return switch (target) {
            case GL32.GL_PIXEL_PACK_BUFFER -> pixelPackBuffer;
            case GL32.GL_PIXEL_UNPACK_BUFFER -> pixelUnpackBuffer;
            default -> null;
        };
    }

    // VULKANMOD APPROACH: CPU buffer emulation for texture copying
    // Note: VulkanMod's texture loading code accesses VkGlBuffer.getPixelUnpackBufferBound() directly
    // We can't add public methods to mixins targeting external classes, so texture loading will need
    // to be handled differently in Vitra (via MTextureUtil mixin or similar)
    private static class VitraGlBuffer {
        private final int id;
        private ByteBuffer data;

        VitraGlBuffer(int id) {
            this.id = id;
        }

        void allocate(int size) {
            if (this.data != null) {
                free();
            }
            this.data = MemoryUtil.memAlloc(size);
        }

        public ByteBuffer getData() {
            return this.data;
        }

        void free() {
            if (this.data != null) {
                MemoryUtil.memFree(this.data);
                this.data = null;
            }
        }
    }
}
