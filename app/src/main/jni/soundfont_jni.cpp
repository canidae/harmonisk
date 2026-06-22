#include <jni.h>
#include <mutex>
#include <android/log.h>

#define TSF_IMPLEMENTATION
#include "tsf.h"

#define LOG_TAG "SoundFontJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

struct SynthHandle {
    tsf* synth;
    std::mutex mutex;
};

extern "C" JNIEXPORT jlong JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeLoad(
    JNIEnv* env, jclass /*clazz*/, jstring path)
{
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading SoundFont from: %s", pathStr);
    tsf* synth = tsf_load_filename(pathStr);
    env->ReleaseStringUTFChars(path, pathStr);

    if (!synth) {
        LOGE("Failed to load SoundFont");
        return 0;
    }

    tsf_set_output(synth, TSF_MONO, 44100, 0.0f);
    if (!tsf_set_max_voices(synth, 64)) {
        LOGE("Failed to set max voices");
    }

    LOGI("Loaded %d presets", tsf_get_presetcount(synth));
    for (int i = 0; i < tsf_get_presetcount(synth) && i < 5; i++) {
        LOGI("  preset[%d]: bank=%d prog=%d name=%s",
            i,
            synth->presets[i].bank,
            synth->presets[i].preset,
            synth->presets[i].presetName);
    }

    auto* handle = new (std::nothrow) SynthHandle{synth};
    if (!handle) {
        tsf_close(synth);
        return 0;
    }

    LOGI("SoundFont loaded successfully");
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeUnload(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        tsf_close(h->synth);
    }
    delete h;
    LOGD("SoundFont unloaded");
}

extern "C" JNIEXPORT jint JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeGetPresetIndex(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint bank, jint preset)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return -1;
    int idx = tsf_get_presetindex(h->synth, bank, preset);
    LOGD("nativeGetPresetIndex(bank=%d preset=%d) -> %d", bank, preset, idx);
    return idx;
}

extern "C" JNIEXPORT jint JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeActiveVoiceCount(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return -1;
    return tsf_active_voice_count(h->synth);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeNoteOnPreset(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint presetIndex, jint key, jfloat velocity)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return JNI_FALSE;
    if (presetIndex < 0) {
        LOGE("nativeNoteOnPreset: invalid presetIndex=%d", presetIndex);
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    int result = tsf_note_on(h->synth, presetIndex, key, velocity);
    if (!result) {
        LOGE("nativeNoteOnPreset failed: preset=%d key=%d vel=%.2f", presetIndex, key, velocity);
    }
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeNoteOffPreset(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint presetIndex, jint key)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return;
    if (presetIndex < 0) {
        LOGE("nativeNoteOffPreset: invalid presetIndex=%d", presetIndex);
        return;
    }
    std::lock_guard<std::mutex> lock(h->mutex);
    tsf_note_off(h->synth, presetIndex, key);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeChannelNoteOn(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint channel, jint key, jfloat velocity)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(h->mutex);
    int result = tsf_channel_note_on(h->synth, channel, key, velocity);
    LOGD("nativeChannelNoteOn(ch=%d key=%d vel=%.2f) -> %d  voices=%d",
        channel, key, velocity, result, tsf_active_voice_count(h->synth));
    if (!result) {
        LOGE("nativeChannelNoteOn failed: channel=%d key=%d vel=%.2f", channel, key, velocity);
    }
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeChannelNoteOff(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint channel, jint key)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return;
    std::lock_guard<std::mutex> lock(h->mutex);
    tsf_channel_note_off(h->synth, channel, key);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeSetChannelPreset(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint channel, jint bank, jint preset)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(h->mutex);
    int result = tsf_channel_set_bank_preset(h->synth, channel, bank, preset);
    if (!result) {
        LOGE("nativeSetChannelPreset failed: ch=%d bank=%d preset=%d", channel, bank, preset);
    }
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeSetChannelDrum(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint channel)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(h->mutex);
    int result = tsf_channel_set_presetnumber(h->synth, channel, 0, 1);
    if (!result) {
        LOGE("nativeSetChannelDrum failed: channel=%d", channel);
    }
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeAllNotesOff(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return;
    std::lock_guard<std::mutex> lock(h->mutex);
    tsf_note_off_all(h->synth);
    if (h->synth->channels) {
        for (int ch = 0; ch < h->synth->channels->channelNum; ++ch) {
            tsf_channel_sounds_off_all(h->synth, ch);
            tsf_channel_note_off_all(h->synth, ch);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_exent_perfectpitch_audio_SoundFontSynthesizer_nativeRender(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jshortArray buffer, jint samples)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle);
    if (!h) return;
    jshort* buf = env->GetShortArrayElements(buffer, nullptr);
    if (!buf) return;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        tsf_render_short(h->synth, buf, samples, 0);
    }
    env->ReleaseShortArrayElements(buffer, buf, 0);
}
