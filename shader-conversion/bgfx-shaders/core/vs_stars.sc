$input a_position

#include "dynamictransforms.sh"
#include "projection.sh"

void main() {
    gl_Position = mul(u_projMat, mul(u_modelViewMat, vec4(a_position, 1.0)));
}