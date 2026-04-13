uniform mat4 uMVPMatrix;
uniform float uZoom;
uniform vec2 uPan;
uniform vec2 uCropZoom;
attribute vec4 aPosition;
attribute vec4 aTextureCoordinate;
varying vec2 vTextureCoord;
void main()
{
    gl_Position = uMVPMatrix * aPosition;
    vec2 totalZoom = uCropZoom * uZoom;
    vTextureCoord = (aTextureCoordinate.xy - 0.5) / totalZoom + 0.5 - uPan;
}