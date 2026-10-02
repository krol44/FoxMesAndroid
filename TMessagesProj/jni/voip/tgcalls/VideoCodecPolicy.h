#ifndef TGCALLS_VIDEO_CODEC_POLICY_H
#define TGCALLS_VIDEO_CODEC_POLICY_H

#include "api/video_codecs/video_encoder_factory.h"
#include "api/video_codecs/video_decoder_factory.h"
#include "api/video_codecs/video_encoder.h"
#include "absl/strings/match.h"
#include <algorithm>

namespace tgcalls {

inline bool IsDisabledVideoCodec(const webrtc::SdpVideoFormat &format) {
    return absl::EqualsIgnoreCase(format.name, "VP8");
}
inline std::vector<webrtc::SdpVideoFormat> EnabledVideoFormats(std::vector<webrtc::SdpVideoFormat> formats) {
    formats.erase(std::remove_if(formats.begin(), formats.end(), IsDisabledVideoCodec), formats.end());
    return formats;
}
class EnabledVideoEncoderFactory final : public webrtc::VideoEncoderFactory {
public:
    explicit EnabledVideoEncoderFactory(std::unique_ptr<webrtc::VideoEncoderFactory> factory) : _factory(std::move(factory)) {}
    std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override { return EnabledVideoFormats(_factory->GetSupportedFormats()); }
    std::vector<webrtc::SdpVideoFormat> GetImplementations() const override { return EnabledVideoFormats(_factory->GetImplementations()); }
    CodecSupport QueryCodecSupport(const webrtc::SdpVideoFormat &format, absl::optional<std::string> mode) const override {
        return IsDisabledVideoCodec(format) ? CodecSupport{} : _factory->QueryCodecSupport(format, mode);
    }
    std::unique_ptr<webrtc::VideoEncoder> CreateVideoEncoder(const webrtc::SdpVideoFormat &format) override {
        return IsDisabledVideoCodec(format) ? nullptr : _factory->CreateVideoEncoder(format);
    }
private:
    std::unique_ptr<webrtc::VideoEncoderFactory> _factory;
};
class EnabledVideoDecoderFactory final : public webrtc::VideoDecoderFactory {
public:
    explicit EnabledVideoDecoderFactory(std::unique_ptr<webrtc::VideoDecoderFactory> factory) : _factory(std::move(factory)) {}
    std::vector<webrtc::SdpVideoFormat> GetSupportedFormats() const override { return EnabledVideoFormats(_factory->GetSupportedFormats()); }
    CodecSupport QueryCodecSupport(const webrtc::SdpVideoFormat &format, bool scaling) const override {
        return IsDisabledVideoCodec(format) ? CodecSupport{} : _factory->QueryCodecSupport(format, scaling);
    }
    std::unique_ptr<webrtc::VideoDecoder> Create(const webrtc::Environment &env, const webrtc::SdpVideoFormat &format) override {
        return IsDisabledVideoCodec(format) ? nullptr : _factory->Create(env, format);
    }
    std::unique_ptr<webrtc::VideoDecoder> CreateVideoDecoder(const webrtc::SdpVideoFormat &format) override {
        return IsDisabledVideoCodec(format) ? nullptr : _factory->CreateVideoDecoder(format);
    }
private:
    std::unique_ptr<webrtc::VideoDecoderFactory> _factory;
};
}
#endif
