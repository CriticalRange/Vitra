/*
 * BGFX conversion of Minecraft's matrix.glsl
 * Matrix constructor converted to BGFX format
 */

mat2 mat2_rotate_z(float radians) {
    return mtxFromCols(
        cos(radians), -sin(radians),
        sin(radians), cos(radians)
    );
}