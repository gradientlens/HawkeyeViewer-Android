precision mediump float;
uniform sampler2D uTextureSampler;
varying vec2 vTextureCoord;

// Image adjustment uniforms
// GL defaults uniforms to 0.0, so shader must handle that as "no adjustment"
uniform float uBrightness; // 0.0 = use default (1.0), else multiplier
uniform float uContrast;   // 0.0 = use default (1.0), else multiplier
uniform float uSaturation; // 0.0 = use default (1.0), else multiplier
uniform float uHue;        // rotation in radians, 0.0 = no change
uniform float uGamma;      // 0.0 = use default (1.0), else gamma value
uniform float uSharpness;  // 0.0 = no sharpen, >0.0 = sharpen amount (0-2.0)
uniform vec2  uTexelSize;  // 1.0/width, 1.0/height for neighbor sampling

// Luminance weights (Rec. 709)
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main()
{
    vec4 color = texture2D(uTextureSampler, vTextureCoord);

    // Treat 0.0 (GL default) as "no adjustment" = 1.0
    float b = uBrightness > 0.001 ? uBrightness : 1.0;
    float c = uContrast > 0.001 ? uContrast : 1.0;
    float s = uSaturation > 0.001 ? uSaturation : 1.0;
    float g = uGamma > 0.001 ? uGamma : 1.0;

    // Early exit: if all adjustments are at defaults, just output the texture
    bool noAdj = abs(b - 1.0) < 0.01 && abs(c - 1.0) < 0.01
              && abs(s - 1.0) < 0.01 && abs(uHue) < 0.001
              && abs(g - 1.0) < 0.01 && uSharpness < 0.01;
    if (noAdj) {
        gl_FragColor = color;
        return;
    }

    // 1. Brightness (simple multiply)
    if (abs(b - 1.0) > 0.01)
        color.rgb *= b;

    // 2. Contrast (scale around 0.5 gray)
    if (abs(c - 1.0) > 0.01)
        color.rgb = (color.rgb - 0.5) * c + 0.5;

    // 3. Saturation (lerp between grayscale and color)
    if (abs(s - 1.0) > 0.01) {
        float luma = dot(color.rgb, LUMA);
        color.rgb = mix(vec3(luma), color.rgb, s);
    }

    // 4. Hue rotation (rotate in YIQ color space)
    if (abs(uHue) > 0.001) {
        float cosH = cos(uHue);
        float sinH = sin(uHue);
        mat3 hueRotation = mat3(
            0.299 + 0.701 * cosH + 0.168 * sinH,
            0.587 - 0.587 * cosH + 0.330 * sinH,
            0.114 - 0.114 * cosH - 0.497 * sinH,
            0.299 - 0.299 * cosH - 0.328 * sinH,
            0.587 + 0.413 * cosH + 0.035 * sinH,
            0.114 - 0.114 * cosH + 0.292 * sinH,
            0.299 - 0.300 * cosH + 1.250 * sinH,
            0.587 - 0.588 * cosH - 1.050 * sinH,
            0.114 + 0.886 * cosH - 0.203 * sinH
        );
        color.rgb = hueRotation * color.rgb;
    }

    // 5. Gamma correction
    if (abs(g - 1.0) > 0.01) {
        color.rgb = pow(clamp(color.rgb, 0.0, 1.0), vec3(1.0 / g));
    }

    // 6. Sharpness (unsharp mask using 4-tap laplacian)
    if (uSharpness > 0.01 && uTexelSize.x > 0.0) {
        vec3 n = texture2D(uTextureSampler, vTextureCoord + vec2(0.0, -uTexelSize.y)).rgb;
        vec3 so = texture2D(uTextureSampler, vTextureCoord + vec2(0.0, uTexelSize.y)).rgb;
        vec3 e = texture2D(uTextureSampler, vTextureCoord + vec2(uTexelSize.x, 0.0)).rgb;
        vec3 w = texture2D(uTextureSampler, vTextureCoord + vec2(-uTexelSize.x, 0.0)).rgb;
        vec3 blur = (n + so + e + w) * 0.25;
        color.rgb = color.rgb + uSharpness * (color.rgb - blur);
    }

    gl_FragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}
