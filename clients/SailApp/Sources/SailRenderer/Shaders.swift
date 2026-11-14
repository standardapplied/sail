/// The two pipelines' shaders, compiled when the renderer starts: SwiftPM builds no `.metal`.
/// Instances are pixel rectangles; glyphs read their atlas slot texel for texel, so a glyph drawn
/// on whole pixels is exactly what was rasterized.
enum Shaders {
    static let source = """
    #include <metal_stdlib>
    using namespace metal;

    struct Solid { float4 rect; float4 color; };
    struct Glyph { float4 rect; float2 slot; float colour; float unused; float4 color; };

    struct SolidOut { float4 position [[position]]; float4 color; };
    struct GlyphOut { float4 position [[position]]; float2 texel; float colour; float4 color; };

    static float2 corner(uint vertexID) {
        return float2(vertexID & 1, vertexID >> 1);
    }

    static float4 clip(float2 pixel, float2 viewport) {
        return float4(pixel / viewport * float2(2, -2) + float2(-1, 1), 0, 1);
    }

    vertex SolidOut solidVertex(uint vertexID [[vertex_id]], uint instanceID [[instance_id]],
                                const device Solid *solids [[buffer(0)]], constant float2 &viewport [[buffer(1)]]) {
        Solid solid = solids[instanceID];
        SolidOut out;
        out.position = clip(solid.rect.xy + corner(vertexID) * solid.rect.zw, viewport);
        out.color = solid.color;
        return out;
    }

    fragment float4 solidFragment(SolidOut in [[stage_in]]) {
        return float4(in.color.rgb * in.color.a, in.color.a);
    }

    vertex GlyphOut glyphVertex(uint vertexID [[vertex_id]], uint instanceID [[instance_id]],
                                const device Glyph *glyphs [[buffer(0)]], constant float2 &viewport [[buffer(1)]]) {
        Glyph glyph = glyphs[instanceID];
        float2 offset = corner(vertexID) * glyph.rect.zw;
        GlyphOut out;
        out.position = clip(glyph.rect.xy + offset, viewport);
        out.texel = glyph.slot + offset;
        out.colour = glyph.colour;
        out.color = glyph.color;
        return out;
    }

    fragment float4 glyphFragment(GlyphOut in [[stage_in]],
                                  texture2d<float, access::read> alpha [[texture(0)]],
                                  texture2d<float, access::read> colour [[texture(1)]]) {
        uint2 texel = uint2(in.texel);
        if (in.colour > 0.5) {
            return colour.read(texel);
        }
        float coverage = alpha.read(texel).r * in.color.a;
        return float4(in.color.rgb * coverage, coverage);
    }
    """
}
