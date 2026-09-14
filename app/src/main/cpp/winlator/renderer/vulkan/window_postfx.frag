#version 450

layout(binding = 0) uniform sampler2D texSampler;

layout(push_constant) uniform PC {
    float ndcX0, ndcY0, ndcX1, ndcY1;
    int   effectId;
    float sharpness;
    float resW;
    float resH;
} pc;

layout(location = 0) in  vec2 fragTexCoord;
layout(location = 0) out vec4 outColor;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 adjustSaturation(vec3 c, float amount) {
    float y = luma(c);
    return clamp(mix(vec3(y), c, amount), 0.0, 1.0);
}

vec3 adjustContrast(vec3 c, float amount) {
    return clamp((c - 0.5) * amount + 0.5, 0.0, 1.0);
}

vec3 acesTonemap(vec3 c) {
    c = max(c, vec3(0.0));
    const float a = 2.51;
    const float b = 0.03;
    const float d = 0.59;
    const float e = 0.14;
    const float f = 2.43;
    return clamp((c * (a * c + b)) / (c * (f * c + d) + e), 0.0, 1.0);
}

vec3 blurCross(vec2 uv, vec2 texel) {
    return (texture(texSampler, uv + vec2( 0.0,    -texel.y)).rgb
          + texture(texSampler, uv + vec2( 0.0,     texel.y)).rgb
          + texture(texSampler, uv + vec2(-texel.x,  0.0   )).rgb
          + texture(texSampler, uv + vec2( texel.x,  0.0   )).rgb) * 0.25;
}

vec3 adaptiveSharpen(vec2 uv, float amount) {
    vec2 texel = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    vec3 c = texture(texSampler, uv).rgb;
    vec3 n = texture(texSampler, uv + vec2(0.0, -texel.y)).rgb;
    vec3 s = texture(texSampler, uv + vec2(0.0,  texel.y)).rgb;
    vec3 w = texture(texSampler, uv + vec2(-texel.x, 0.0)).rgb;
    vec3 e = texture(texSampler, uv + vec2( texel.x, 0.0)).rgb;
    vec3 localMin = min(c, min(min(n, s), min(w, e)));
    vec3 localMax = max(c, max(max(n, s), max(w, e)));
    vec3 range = localMax - localMin;
    vec3 blur = (n + s + w + e) * 0.25;
    float edge = clamp(luma(range) * 2.4, 0.0, 1.0);
    float adaptive = mix(1.0, 0.38, edge);
    return clamp(c + (c - blur) * amount * adaptive, 0.0, 1.0);
}

vec3 clarity(vec2 uv, float amount) {
    vec2 texel = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    vec3 c = texture(texSampler, uv).rgb;
    vec3 blur = blurCross(uv, texel * 1.5);
    float y = luma(c);
    float midtoneMask = 1.0 - abs(y * 2.0 - 1.0);
    float gain = amount * mix(0.45, 1.0, midtoneMask);
    return clamp(c + (c - blur) * gain, 0.0, 1.0);
}

float cubicWeight(float x) {
    x = abs(x);
    if (x <= 1.0) return 1.5 * x * x * x - 2.5 * x * x + 1.0;
    if (x < 2.0) return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
    return 0.0;
}

vec3 sampleCatmullRom(vec2 uv) {
    vec2 size = max(vec2(pc.resW, pc.resH), vec2(1.0));
    vec2 p = uv * size - 0.5;
    vec2 base = floor(p);
    vec2 f = p - base;
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int y = -1; y <= 2; y++) {
        for (int x = -1; x <= 2; x++) {
            float w = cubicWeight(float(x) - f.x) * cubicWeight(float(y) - f.y);
            vec2 suv = (base + vec2(float(x), float(y)) + 0.5) / size;
            sum += texture(texSampler, clamp(suv, vec2(0.0), vec2(1.0))).rgb * w;
            weightSum += w;
        }
    }
    return clamp(sum / max(weightSum, 1e-5), 0.0, 1.0);
}

// Legacy post effects ---------------------------------------------------------

vec3 applyDLS(vec2 uv, float sharp) {
    vec2 texel  = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    float SAT   = 1.0 + sharp * 0.20;
    float CON   = 1.0 + sharp * 0.12;
    float SHARP = sharp * 1.2;
    vec3 orig = texture(texSampler, uv).rgb;
    vec3 c    = clamp((orig - 0.5) * CON + 0.5, 0.0, 1.0);
    float gray = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(gray), c, SAT);
    vec3 blur = blurCross(uv, texel);
    return clamp(c + (orig - blur) * SHARP, 0.0, 1.0);
}

vec3 applyCRT(vec2 uv) {
    const float CA = 1.0025;
    vec4 fc = texture(texSampler, uv);
    fc.r = texture(texSampler, (uv - 0.5) * CA + 0.5).r;
    fc.b = texture(texSampler, (uv - 0.5) / CA + 0.5).b;
    float sx = abs(sin(uv.x * 1024.0) * 0.5 * 0.125);
    float sy = abs(sin(uv.y * 1024.0) * 0.5 * 0.375);
    return mix(fc.rgb, vec3(0.0), sx + sy);
}

vec3 applyHDR(vec2 uv) {
    vec2 texel = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    vec3 c     = texture(texSampler, uv).rgb;
    const float r1 = 0.793, r2 = 0.870;
    vec3 b1 = vec3(0.0), b2 = vec3(0.0);
    vec2 offs[8] = vec2[](
        vec2( 1.5, -1.5), vec2(-1.5, -1.5), vec2( 1.5,  1.5), vec2(-1.5,  1.5),
        vec2( 0.0, -2.5), vec2( 0.0,  2.5), vec2(-2.5,  0.0), vec2( 2.5,  0.0)
    );
    for (int i = 0; i < 8; i++) {
        b1 += texture(texSampler, uv + offs[i] * r1 * texel).rgb;
        b2 += texture(texSampler, uv + offs[i] * r2 * texel).rgb;
    }
    b1 *= 0.005; b2 *= 0.010;
    vec3 hdr = (c + (b2 - b1)) * (r2 - r1);
    return clamp(pow(abs(hdr + c), vec3(1.30)) + hdr, 0.0, 1.0);
}

vec3 applyNatural(vec2 uv) {
    mat3 toYIQ = mat3( 0.299,  0.596,  0.212,
                       0.587, -0.275, -0.523,
                       0.114, -0.321,  0.311);
    mat3 toRGB = mat3( 1.0,         1.0,         1.0,
                       0.95568806, -0.27158179, -1.10817732,
                       0.61985809, -0.64687381,  1.70506455);
    vec3 c = texture(texSampler, uv).rgb;
    vec3 t = c * toYIQ;
    t = vec3(pow(max(t.r, 0.0), 1.12), t.g * 1.2, t.b * 1.2);
    return clamp(t * toRGB, 0.0, 1.0);
}

vec3 applyVibrance(vec2 uv, float strength) {
    vec3 c = texture(texSampler, uv).rgb;
    float hi = max(c.r, max(c.g, c.b));
    float lo = min(c.r, min(c.g, c.b));
    float saturation = hi - lo;
    float amount = mix(0.08, 0.42, clamp(strength, 0.0, 1.0));
    float gain = 1.0 + amount * (1.0 - saturation);
    return adjustSaturation(c, gain);
}

vec3 applyCurves(vec2 uv, float strength) {
    vec3 c = texture(texSampler, uv).rgb;
    vec3 sCurve = c * c * (3.0 - 2.0 * c);
    float amount = mix(0.18, 0.78, clamp(strength, 0.0, 1.0));
    return clamp(mix(c, sCurve, amount), 0.0, 1.0);
}

vec3 applyCASLite(vec2 uv, float strength) {
    return adaptiveSharpen(uv, mix(0.22, 0.90, clamp(strength, 0.0, 1.0)));
}

vec3 applyTechnicolor(vec2 uv, float strength) {
    vec3 c = texture(texSampler, uv).rgb;
    vec3 warm = vec3(
        c.r * 1.10 + c.g * 0.04 - c.b * 0.03,
        c.g * 1.04 + c.r * 0.02,
        c.b * 0.92 + c.g * 0.05
    );
    warm = clamp((warm - 0.5) * 1.06 + 0.5, 0.0, 1.0);
    float amount = mix(0.20, 0.72, clamp(strength, 0.0, 1.0));
    return mix(c, warm, amount);
}

vec3 applyLevels(vec2 uv, float strength) {
    vec3 c = texture(texSampler, uv).rgb;
    float s = clamp(strength, 0.0, 1.0);
    float blackPoint = mix(0.012, 0.055, s);
    float whitePoint = mix(0.992, 0.955, s);
    vec3 leveled = clamp((c - blackPoint) / max(whitePoint - blackPoint, 1e-3), 0.0, 1.0);
    float gamma = mix(1.0, 0.94, s);
    return pow(leveled, vec3(gamma));
}

vec3 applyGameClarity(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 base = texture(texSampler, uv).rgb;
    vec3 local = clarity(uv, mix(0.45, 1.15, s));
    vec3 sharp = adaptiveSharpen(uv, mix(0.30, 0.95, s));
    vec3 c = mix(base, local, mix(0.58, 0.86, s));
    c = mix(c, sharp, mix(0.38, 0.68, s));
    c = adjustContrast(c, mix(1.035, 1.105, s));
    c = adjustSaturation(c, mix(1.05, 1.18, s));
    return c;
}

vec3 applyCinematicProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 base = texture(texSampler, uv).rgb;
    vec3 film = acesTonemap(base * mix(1.03, 1.22, s));
    vec3 c = mix(base, film, mix(0.40, 0.78, s));
    float y = luma(c);
    float shadows = 1.0 - smoothstep(0.12, 0.55, y);
    float highlights = smoothstep(0.45, 0.90, y);
    c += vec3(-0.018, 0.007, 0.025) * shadows * s;
    c += vec3(0.028, 0.010, -0.014) * highlights * s;
    c = adjustSaturation(c, mix(0.98, 1.10, s));
    c = adjustContrast(c, mix(1.02, 1.09, s));
    return clamp(c, 0.0, 1.0);
}

vec3 applyVividProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 c = texture(texSampler, uv).rgb;
    float blackPoint = mix(0.008, 0.030, s);
    float whitePoint = mix(0.995, 0.970, s);
    c = clamp((c - blackPoint) / max(whitePoint - blackPoint, 1e-3), 0.0, 1.0);
    c = adjustContrast(c, mix(1.05, 1.16, s));
    c = adjustSaturation(c, mix(1.13, 1.34, s));
    c = pow(c, vec3(mix(0.98, 0.91, s)));
    return clamp(c, 0.0, 1.0);
}

vec3 applyCompetitiveProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 base = texture(texSampler, uv).rgb;
    vec3 lifted = pow(max(base, vec3(0.0)), vec3(mix(0.94, 0.82, s)));
    lifted = mix(lifted, acesTonemap(lifted * 1.08), 0.25 + 0.20 * s);
    vec3 sharp = adaptiveSharpen(uv, mix(0.38, 1.05, s));
    vec3 c = mix(lifted, sharp, mix(0.42, 0.70, s));
    c = adjustSaturation(c, mix(1.03, 1.13, s));
    c = adjustContrast(c, mix(1.01, 1.06, s));
    return clamp(c, 0.0, 1.0);
}

vec3 applyAdaptiveSharpenProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    return adaptiveSharpen(uv, mix(0.42, 1.40, s));
}

vec3 applyFilmicProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 base = texture(texSampler, uv).rgb;
    vec3 film = acesTonemap(base * mix(1.02, 1.28, s));
    film = adjustContrast(film, mix(1.01, 1.08, s));
    film = adjustSaturation(film, mix(1.00, 1.08, s));
    return clamp(mix(base, film, mix(0.42, 0.86, s)), 0.0, 1.0);
}

vec3 applyArcadeProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec3 c = texture(texSampler, uv).rgb;
    c = pow(max(c, vec3(0.0)), vec3(mix(0.97, 0.88, s)));
    c = adjustContrast(c, mix(1.06, 1.18, s));
    c = adjustSaturation(c, mix(1.18, 1.42, s));
    c.r *= mix(1.01, 1.055, s);
    c.b *= mix(1.00, 0.965, s);
    vec3 sharp = adaptiveSharpen(uv, mix(0.22, 0.65, s));
    c = mix(c, sharp, mix(0.18, 0.42, s));
    return clamp(c, 0.0, 1.0);
}

vec3 applyRetroCRTProfile(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec2 texel = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    float ca = mix(0.35, 1.55, s);
    vec3 c;
    c.r = texture(texSampler, uv + vec2(texel.x * ca, 0.0)).r;
    c.g = texture(texSampler, uv).g;
    c.b = texture(texSampler, uv - vec2(texel.x * ca, 0.0)).b;
    float scan = sin(uv.y * max(pc.resH, 1.0) * 3.14159265);
    float scanStrength = mix(0.035, 0.16, s);
    c *= 1.0 - scanStrength * (0.5 + 0.5 * scan);
    float mask = 0.965 + 0.035 * sin(uv.x * max(pc.resW, 1.0) * 3.14159265 * 0.6667);
    c *= mix(1.0, mask, 0.35 + 0.45 * s);
    vec2 p = uv * 2.0 - 1.0;
    float vignette = clamp(1.0 - dot(p, p) * mix(0.035, 0.12, s), 0.72, 1.0);
    c *= vignette;
    c = adjustSaturation(c, mix(1.02, 1.12, s));
    return clamp(c, 0.0, 1.0);
}

// Experimental upscale-inspired profiles. These are original implementations
// and do not include Pilzprinz Upscale code.
vec3 applyUpscaleSharp(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec2 texel = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    vec3 c = sampleCatmullRom(uv);
    vec3 blur = blurCross(uv, texel);
    return clamp(c + (c - blur) * mix(0.12, 0.55, s), 0.0, 1.0);
}

vec3 applyPixelClean(vec2 uv, float strength) {
    vec2 size = max(vec2(pc.resW, pc.resH), vec2(1.0));
    vec2 snapped = (floor(uv * size) + 0.5) / size;
    vec3 c = texture(texSampler, clamp(snapped, vec2(0.0), vec2(1.0))).rgb;
    float s = clamp(strength, 0.0, 1.0);
    c = adjustContrast(c, mix(1.0, 1.08, s));
    return adjustSaturation(c, mix(1.0, 1.08, s));
}

vec3 applyAnimeEdge(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec2 texel = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));
    vec3 c = texture(texSampler, uv).rgb;
    vec3 n = texture(texSampler, uv + vec2(0.0, -texel.y)).rgb;
    vec3 ss = texture(texSampler, uv + vec2(0.0, texel.y)).rgb;
    vec3 w = texture(texSampler, uv + vec2(-texel.x, 0.0)).rgb;
    vec3 e = texture(texSampler, uv + vec2(texel.x, 0.0)).rgb;
    float edge = clamp(abs(luma(n) - luma(ss)) + abs(luma(w) - luma(e)), 0.0, 1.0);
    vec3 sharp = adaptiveSharpen(uv, mix(0.20, 0.72, s));
    c = mix(c, sharp, 0.45 + 0.35 * s);
    c *= 1.0 - edge * mix(0.04, 0.18, s);
    c = adjustSaturation(c, mix(1.02, 1.16, s));
    return clamp(c, 0.0, 1.0);
}

// Color Boost ---------------------------------------------------------------
// A spatial color-and-detail enhancement pass: local contrast, contact-like
// shadow depth, material highlights, warm-color scattering, soft color bleed,
// adaptive vibrance and recovered micro-detail.
// v2: v1 gated almost every stage behind narrow smoothstep windows and then
// clamped the result back to within ~0.03-0.06 of the raw 3x3 neighborhood,
// which erased nearly all of it - net effect was "a bit brighter". This pass
// loosens the gating, roughly doubles-to-triples every stage's intensity,
// adds a wider-radius bleed ring, and widens the final clamp so the change
// actually survives to the output - aiming for the same order of visible
// difference as applyDLS / applyArcadeProfile, not a subtle tweak.
vec3 applyColorBoost(vec2 uv, float strength) {
    float s = clamp(strength, 0.0, 1.0);
    vec2 texel = 1.0 / max(vec2(pc.resW, pc.resH), vec2(1.0));

    vec3 c  = texture(texSampler, uv).rgb;
    vec3 n  = texture(texSampler, uv + vec2(0.0, -texel.y)).rgb;
    vec3 so = texture(texSampler, uv + vec2(0.0,  texel.y)).rgb;
    vec3 w  = texture(texSampler, uv + vec2(-texel.x, 0.0)).rgb;
    vec3 e  = texture(texSampler, uv + vec2( texel.x, 0.0)).rgb;
    vec3 nw = texture(texSampler, uv + vec2(-texel.x, -texel.y)).rgb;
    vec3 ne = texture(texSampler, uv + vec2( texel.x, -texel.y)).rgb;
    vec3 sw = texture(texSampler, uv + vec2(-texel.x,  texel.y)).rgb;
    vec3 se = texture(texSampler, uv + vec2( texel.x,  texel.y)).rgb;

    vec3 crossAvg = (n + so + w + e) * 0.25;
    vec3 diagAvg  = (nw + ne + sw + se) * 0.25;
    vec3 localBase = c * 0.36 + crossAvg * 0.44 + diagAvg * 0.20;

    float yc = luma(c);
    float yb = luma(localBase);
    float yn = luma(n), ys = luma(so), yw = luma(w), ye = luma(e);
    float ynw = luma(nw), yne = luma(ne), ysw = luma(sw), yse = luma(se);
    float minY = min(yc, min(min(yn, ys), min(min(yw, ye), min(min(ynw, yne), min(ysw, yse)))));
    float maxY = max(yc, max(max(yn, ys), max(max(yw, ye), max(max(ynw, yne), max(ysw, yse)))));
    float localRange = maxY - minY;
    // Loosened vs v1 (0.018-0.18): engages on almost any visible edge or
    // texture instead of only strong local contrast, so most of the frame
    // is actually affected rather than a handful of high-contrast spots.
    float structure = smoothstep(0.006, 0.09, localRange);
    float midtone = smoothstep(0.04, 0.20, yc) * (1.0 - smoothstep(0.80, 1.00, yc));

    vec3 result = c;

    // Local relight - roughly 3x stronger than v1.
    float localDetail = clamp(yc - yb, -0.28, 0.28);
    result += vec3(localDetail * structure * mix(0.55, 1.35, s));

    // Deeper, wider contact shadowing.
    float darkDelta = max(yb - yc, 0.0);
    float contact = smoothstep(0.010, 0.07, darkDelta)
                  * smoothstep(0.010, 0.12, localRange);
    result *= 1.0 - contact * mix(0.10, 0.26, s);

    // Material/specular response - wider band, much stronger push.
    float hi = max(c.r, max(c.g, c.b));
    float lo = min(c.r, min(c.g, c.b));
    float chroma = hi - lo;
    float relativeChroma = chroma / max(yc, 0.08);
    float neutralHighlight = 1.0 - smoothstep(0.32, 0.80, relativeChroma);
    float brightDelta = max(yc - yb, 0.0);
    float specular = smoothstep(0.010, 0.08, brightDelta)
                   * smoothstep(0.22, 0.80, yc)
                   * neutralHighlight * structure;
    vec3 specColor = max(c - localBase, vec3(0.0));
    result += specColor * specular * mix(0.9, 2.0, s);
    result += vec3(specular * mix(0.012, 0.045, s));

    // Warm-material / subsurface-style scattering - wider band, stronger mix.
    float warmRG = smoothstep(-0.03, 0.10, c.r - c.g);
    float warmRB = smoothstep(0.010, 0.16, c.r - c.b);
    float warmMask = warmRG * warmRB * midtone * (1.0 - specular);
    vec3 warmSoft = localBase * vec3(1.045, 1.010, 0.965);
    vec3 scattered = warmSoft + (result - localBase) * 0.80;
    result = mix(result, scattered, warmMask * mix(0.06, 0.18, s));

    // Wider-radius soft light/colour bleed - new in v2. Cheap 8-tap ring a
    // few texels out, so bright/saturated neighbourhoods visibly spill
    // colour and glow onto darker areas beside them - this reads as the
    // strongest single "relit" cue in real neural-rendering comparisons.
    vec2 ringOffs[8] = vec2[](
        vec2( 1.0, -1.0), vec2(-1.0, -1.0), vec2( 1.0,  1.0), vec2(-1.0,  1.0),
        vec2( 0.0, -1.4), vec2( 0.0,  1.4), vec2(-1.4,  0.0), vec2( 1.4,  0.0)
    );
    float ringRadius = mix(3.0, 7.0, s);
    vec3 bleed = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        bleed += texture(texSampler, uv + ringOffs[i] * ringRadius * texel).rgb;
    }
    bleed *= 0.125;
    float bleedLuma = luma(bleed);
    vec3 bleedChroma = bleed - vec3(bleedLuma);
    float sourceStrength = smoothstep(0.30, 0.80, bleedLuma);
    float receiveMask = 1.0 - smoothstep(0.60, 0.95, yc);
    float bleedAmount = mix(0.10, 0.30, s) * sourceStrength * receiveMask;
    result += bleedChroma * bleedAmount * 3.0;
    result += vec3(bleedLuma * bleedAmount * 0.5);

    // Recover high-frequency material detail - stronger and kept over a
    // wider contrast range than v1.
    vec3 detailRGB = c - localBase;
    float fineDetailMask = structure * (1.0 - smoothstep(0.45, 0.85, localRange));
    result += detailRGB * fineDetailMask * mix(0.20, 0.55, s);

    // Adaptive vibrance - wider band, stronger push.
    float resultY = luma(result);
    float resultHi = max(result.r, max(result.g, result.b));
    float resultLo = min(result.r, min(result.g, result.b));
    float resultChroma = resultHi - resultLo;
    float vibrance = (1.0 - smoothstep(0.22, 0.65, resultChroma))
                   * midtone * mix(0.06, 0.16, s);
    result = mix(vec3(resultY), result, 1.0 + vibrance);

    // Filmic highlight shoulder, blended in harder than v1.
    vec3 film = acesTonemap(max(result, vec3(0.0)) * mix(1.03, 1.14, s));
    result = mix(result, film, mix(0.10, 0.30, s));

    // Global punch pass - the piece v1 was missing entirely. applyDLS and
    // applyArcadeProfile read as "obviously different" largely because of a
    // global contrast/saturation push like this on top of local work; without
    // it, local-only relighting reads as barely-there.
    result = adjustContrast(result, mix(1.02, 1.10, s));
    result = adjustSaturation(result, mix(1.05, 1.20, s));

    // Structure guard, widened: v1 clamped almost back to the raw 3x3 range
    // (allowance 0.025-0.055), which is what was erasing most of the effect.
    // Widened here, and the bleed ring above is intentionally allowed to push
    // past this local 3x3 range rather than being clipped back to it.
    vec3 localMin = min(c, min(min(n, so), min(min(w, e), min(min(nw, ne), min(sw, se)))));
    vec3 localMax = max(c, max(max(n, so), max(max(w, e), max(max(nw, ne), max(sw, se)))));
    float allowance = mix(0.06, 0.16, s);
    result = clamp(result, localMin - vec3(allowance), localMax + vec3(allowance));

    return clamp(result, 0.0, 1.0);
}

void main() {
    vec2 uv = fragTexCoord;
    vec3 rgb;
    if      (pc.effectId == 1)  rgb = applyDLS                   (uv, pc.sharpness);
    else if (pc.effectId == 2)  rgb = applyCRT                   (uv);
    else if (pc.effectId == 3)  rgb = applyHDR                   (uv);
    else if (pc.effectId == 4)  rgb = applyNatural               (uv);
    else if (pc.effectId == 5)  rgb = applyVibrance              (uv, pc.sharpness);
    else if (pc.effectId == 6)  rgb = applyCurves                (uv, pc.sharpness);
    else if (pc.effectId == 7)  rgb = applyCASLite               (uv, pc.sharpness);
    else if (pc.effectId == 8)  rgb = applyTechnicolor           (uv, pc.sharpness);
    else if (pc.effectId == 9)  rgb = applyLevels                (uv, pc.sharpness);
    else if (pc.effectId == 10) rgb = applyGameClarity           (uv, pc.sharpness);
    else if (pc.effectId == 11) rgb = applyCinematicProfile      (uv, pc.sharpness);
    else if (pc.effectId == 12) rgb = applyVividProfile          (uv, pc.sharpness);
    else if (pc.effectId == 13) rgb = applyCompetitiveProfile    (uv, pc.sharpness);
    else if (pc.effectId == 14) rgb = applyAdaptiveSharpenProfile(uv, pc.sharpness);
    else if (pc.effectId == 15) rgb = applyFilmicProfile         (uv, pc.sharpness);
    else if (pc.effectId == 16) rgb = applyArcadeProfile         (uv, pc.sharpness);
    else if (pc.effectId == 17) rgb = applyRetroCRTProfile       (uv, pc.sharpness);
    else if (pc.effectId == 18) rgb = applyUpscaleSharp          (uv, pc.sharpness);
    else if (pc.effectId == 19) rgb = applyPixelClean            (uv, pc.sharpness);
    else if (pc.effectId == 20) rgb = applyAnimeEdge             (uv, pc.sharpness);
    else if (pc.effectId == 21) rgb = applyColorBoost            (uv, pc.sharpness);
    else                        rgb = texture(texSampler, uv).rgb;
    outColor = vec4(rgb, 1.0);
}
