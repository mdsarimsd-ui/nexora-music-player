/*
 * Copyright (C) 2026 LastWave Authors (https://github.com/Clash-Projects/LastWave-Native)
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This file is part of LastWave.
 *
 * LastWave is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * LastWave is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LastWave. If not, see <https://www.gnu.org/licenses/>.
 */

#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace lastwave::audio {

/**
 * Parametric equalizer (AutoEQ / manual PEQ).
 *
 * One process-wide configuration is published from the control thread
 * ([publish]) and picked up lock-free by every instance on its next block:
 * the native DSP chain (mixer / converter paths) and the in-place processor
 * the Kotlin sink runs on the direct routes (USB exclusive, internal direct,
 * bit-perfect AudioTrack). Each instance owns its own filter state and
 * rebuilds coefficients when the configuration revision or its sample rate
 * changes. Filter state is kept across coefficient changes so edits do not
 * reset the signal.
 */
class ParametricEq final {
public:
    static constexpr std::size_t kMaxFilters = 20;
    static constexpr std::size_t kMaxChannels = 2;

    enum FilterType : std::int32_t {
        kPeaking = 0,
        kLowShelf = 1,
        kHighShelf = 2,
        kLowPass = 3,
        kHighPass = 4,
    };

    // PCM encodings accepted by processPcm (same values as Kotlin's
    // NativePcmEncoding: 16-bit, packed 24-bit, 32-bit int, float).
    static constexpr std::int32_t kPcm16 = 0;
    static constexpr std::int32_t kPcm24Packed = 1;
    static constexpr std::int32_t kPcm32 = 2;
    static constexpr std::int32_t kPcmFloat = 3;

    /** Control thread. Arrays hold [count] entries; count is clamped to kMaxFilters. */
    static void publish(
        bool enabled,
        float preampDb,
        const std::int32_t* types,
        const float* frequenciesHz,
        const float* gainsDb,
        const float* qs,
        const std::uint8_t* filterEnabled,
        std::size_t count) noexcept;

    /** True when a published, enabled configuration has at least one active filter or a preamp. */
    static bool isEngaged() noexcept;

    void configure(double sampleRate, std::int32_t channels) noexcept;
    void reset() noexcept;

    /**
     * Float interleaved, in place. Returns true when the EQ was applied
     * (false = disabled, nothing touched).
     */
    bool process(float* interleaved, std::int32_t frameCount, std::int32_t channelCount) noexcept;

    /**
     * Integer / float PCM in its own packing, in place (direct routes).
     * 16-bit output gets TPDF dither. Returns true when applied.
     */
    bool processPcm(std::uint8_t* data, std::size_t byteCount, std::int32_t encoding) noexcept;

    /** Magnitude in dB of the published configuration at [frequency] (preamp included). */
    static double responseDb(double sampleRate, double frequency) noexcept;

private:
    struct Coefficients final {
        double b0{1.0};
        double b1{0.0};
        double b2{0.0};
        double a1{0.0};
        double a2{0.0};
    };

    struct Spec final {
        std::int32_t type{kPeaking};
        double frequency{1000.0};
        double gainDb{0.0};
        double q{0.707};
    };

    static Coefficients design(const Spec& spec, double sampleRate) noexcept;
    static double magnitude(const Coefficients& c, double sampleRate, double frequency) noexcept;
    void syncConfiguration() noexcept;

    inline float tick(float input, std::size_t channel) noexcept {
        double value = static_cast<double>(input);
        for (std::size_t i = 0; i < activeCount_; ++i) {
            const Coefficients* c = &coefficients_[i];
            Coefficients blended;
            if (coefficientFadeRemaining_[i] > 0) {
                // A filter just changed: glide from the previous
                // coefficients to the new ones over about 20 ms (linear in
                // coefficient space, both ends stable biquads). Band edits,
                // preset switches and Q changes fade instead of clicking,
                // and the shared filter state keeps running through it.
                const double t =
                    static_cast<double>(coefficientFadeTotal_ - coefficientFadeRemaining_[i]) /
                    static_cast<double>(coefficientFadeTotal_);
                blended.b0 = prevCoefficients_[i].b0 + (coefficients_[i].b0 - prevCoefficients_[i].b0) * t;
                blended.b1 = prevCoefficients_[i].b1 + (coefficients_[i].b1 - prevCoefficients_[i].b1) * t;
                blended.b2 = prevCoefficients_[i].b2 + (coefficients_[i].b2 - prevCoefficients_[i].b2) * t;
                blended.a1 = prevCoefficients_[i].a1 + (coefficients_[i].a1 - prevCoefficients_[i].a1) * t;
                blended.a2 = prevCoefficients_[i].a2 + (coefficients_[i].a2 - prevCoefficients_[i].a2) * t;
                c = &blended;
                // Counted once per frame (channel 0) so the fade length is
                // in frames, not samples, whatever the channel count.
                if (channel == 0) --coefficientFadeRemaining_[i];
            }
            const Coefficients& cf = *c;
            double& z1 = z1_[i][channel];
            double& z2 = z2_[i][channel];
            const double out = cf.b0 * value + z1;
            z1 = cf.b1 * value - cf.a1 * out + z2;
            z2 = cf.b2 * value - cf.a2 * out;
            value = out;
        }
        // Preamp glides toward its target (one-pole, about 25 ms) so
        // preamp edits never step either.
        smoothPreampGain_ += 0.0008 * (preampGain_ - smoothPreampGain_);
        if (std::abs(preampGain_ - smoothPreampGain_) < 1.0e-9) smoothPreampGain_ = preampGain_;
        return static_cast<float>(value * smoothPreampGain_);
    }

    /**
     * Poweramp-style output limiter. Engages only when a published filter
     * boosts the signal: a per-channel peak envelope (fast attack, slow
     * release) drives a soft-knee gain reduction, so boosted bands stay
     * clean instead of hard-clipping. Runs after the EQ and preamp.
     */
    inline float limit(std::size_t channel, float value) noexcept {
        if (!limiterEnabled_) return value;
        const float peak = std::abs(value);
        const float envCoef =
            peak > limiterEnv_[channel] ? limiterAttackCoef_ : limiterReleaseCoef_;
        limiterEnv_[channel] = envCoef * limiterEnv_[channel] + (1.0F - envCoef) * peak;
        float target = 1.0F;
        if (limiterEnv_[channel] > kLimiterThreshold) {
            const float e = limiterEnv_[channel];
            target = (kLimiterThreshold + (e - kLimiterThreshold) / kLimiterRatio) / e;
        }
        const float gainCoef =
            target < limiterGain_[channel] ? limiterAttackCoef_ : limiterReleaseCoef_;
        limiterGain_[channel] = gainCoef * limiterGain_[channel] + (1.0F - gainCoef) * target;
        return std::clamp(value * limiterGain_[channel], -kLimiterCeiling, kLimiterCeiling);
    }

    double sampleRate_{48000.0};
    std::int32_t channels_{2};
    std::uint32_t appliedRevision_{0xFFFFFFFFU};
    double appliedSampleRate_{0.0};
    bool enabled_{false};
    double preampGain_{1.0};
    std::size_t activeCount_{0};
    std::array<Coefficients, kMaxFilters> coefficients_{};
    // Click-free editing: previous coefficients and fade countdown for each
    // slot while tick() glides between them (see tick).
    std::array<Coefficients, kMaxFilters> prevCoefficients_{};
    std::array<std::int32_t, kMaxFilters> coefficientFadeRemaining_{};
    std::int32_t coefficientFadeTotal_{960};
    double smoothPreampGain_{1.0};
    std::array<std::array<double, kMaxChannels>, kMaxFilters> z1_{};
    std::array<std::array<double, kMaxChannels>, kMaxFilters> z2_{};
    std::array<std::int32_t, kMaxFilters> slotSource_{
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
    std::uint32_t ditherState_{0x12345678U};

    // Output limiter state (per channel).
    static constexpr float kLimiterThreshold = 0.891F;  // about -1 dBFS, soft-knee point
    static constexpr float kLimiterRatio = 12.0F;
    static constexpr float kLimiterCeiling = 0.980F;    // about -0.18 dBFS hard safety
    bool limiterEnabled_{false};
    float limiterAttackCoef_{0.98F};
    float limiterReleaseCoef_{0.999F};
    std::array<float, kMaxChannels> limiterEnv_{};
    std::array<float, kMaxChannels> limiterGain_{};
};

}  // namespace lastwave::audio
