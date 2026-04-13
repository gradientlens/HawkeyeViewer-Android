attribute vec4 aPosition;
attribute vec4 aTextureCoordinate;
uniform float uZoom;
uniform vec2 uPan;
uniform vec2 uCropZoom;
varying vec2 vTextureCoord;
void main()
{
    vec2 totalZoom = uCropZoom * uZoom;
    vTextureCoord = (aTextureCoordinate.xy - 0.5) / totalZoom + 0.5 - uPan;
    gl_Position = aPosition;
}