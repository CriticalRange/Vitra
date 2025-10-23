package com.vitra.render.constants;

/**
 * Centralized constants for the Vitra rendering system.
 * Eliminates duplicate constant definitions across multiple classes.
 */
public final class RenderConstants {
    // Private constructor to prevent instantiation
    private RenderConstants() {}

    // Buffer Type Constants - aligned across D3D11 and D3D12
    public static final class BufferType {
        public static final int VERTEX = 0;
        public static final int INDEX = 1;
        public static final int CONSTANT = 2;
        public static final int STRUCTURED = 3;
    }

    // D3D12-specific buffer types
    public static final class D3D12BufferType {
        public static final int VERTEX = BufferType.VERTEX;
        public static final int INDEX = BufferType.INDEX;
        public static final int CONSTANT = BufferType.CONSTANT;
        public static final int STRUCTURED = BufferType.STRUCTURED;
        public static final int RAYTRACING_TLAS = 4;
        public static final int RAYTRACING_BLAS = 5;
        public static final int VRS_MAP = 6;
    }

    // Shader Type Constants
    public static final class ShaderType {
        public static final int VERTEX = 0;
        public static final int PIXEL = 1;
        public static final int GEOMETRY = 2;
        public static final int COMPUTE = 3;
    }

    // D3D12-specific shader types
    public static final class D3D12ShaderType {
        public static final int VERTEX = ShaderType.VERTEX;
        public static final int PIXEL = ShaderType.PIXEL;
        public static final int GEOMETRY = ShaderType.GEOMETRY;
        public static final int HULL = 3;
        public static final int DOMAIN = 4;
        public static final int COMPUTE = ShaderType.COMPUTE;
        public static final int MESH = 6;
        public static final int AMPLIFICATION = 7;
        public static final int RAYTRACING = 8;
    }

    // Common buffer usage flags
    public static final class BufferUsage {
        public static final int DEFAULT = 0;
        public static final int IMMUTABLE = 1;
        public static final int DYNAMIC = 2;
        public static final int STAGING = 3;
    }

    // Common buffer bind flags
    public static final class BufferBind {
        public static final int VERTEX_BUFFER = 0x1;
        public static final int INDEX_BUFFER = 0x2;
        public static final int CONSTANT_BUFFER = 0x4;
        public static final int SHADER_RESOURCE = 0x8;
        public static final int STREAM_OUTPUT = 0x10;
        public static final int UNORDERED_ACCESS = 0x20;
        public static final int RENDER_TARGET = 0x40;
        public static final int DEPTH_STENCIL = 0x80;
    }
}