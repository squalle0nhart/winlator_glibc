#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace lsfg {

struct CaptureExtent {
    uint32_t width;
    uint32_t height;
};

inline CaptureExtent captureExtent(uint32_t panelWidth, uint32_t panelHeight, int renderHeight) {
    if (panelWidth == 0 || panelHeight == 0 || renderHeight <= 0)
        return {panelWidth, panelHeight};
    const uint32_t height = std::clamp((uint32_t)renderHeight,
                                      std::max(16u, panelHeight / 4u), panelHeight);
    if (height >= panelHeight) return {panelWidth, panelHeight};
    return {(uint32_t)std::lround((double)height * panelWidth / panelHeight) & ~1u,
            height & ~1u};
}

} // namespace lsfg
