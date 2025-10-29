package com.vitra.render.shader;

/**
 * Uniform variable information for shader constant buffers.
 * Contains type, name, size in bytes, and slot number.
 */
public class UniformInfo {
    public final String type;
    public final String name;
    public final int size;
    public final int slot;

    public UniformInfo(String type, String name, int slot) {
        this.type = type;
        this.name = name;
        this.size = getUniformSize(type);
        this.slot = slot;
    }

    private static int getUniformSize(String type) {
        return switch (type) {
            case "mat4" -> 64;  // 4x4 matrix = 16 floats = 64 bytes
            case "mat3" -> 48;  // 3x3 matrix = 9 floats (+padding) = 48 bytes
            case "vec4" -> 16;  // 4 floats = 16 bytes
            case "vec3" -> 16;  // 3 floats (+padding) = 16 bytes
            case "vec2" -> 8;   // 2 floats = 8 bytes
            case "float" -> 4;   // 1 float = 4 bytes
            case "int" -> 4;     // 1 int = 4 bytes
            default -> 16;       // default to vec4 size
        };
    }
}
