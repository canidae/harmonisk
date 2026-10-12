#include <jni.h>
#include <mutex>
#include <vector>
#include <android/log.h>

#define TSF_IMPLEMENTATION
#include "tsf.h"

#define LOG_TAG "SoundFontJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

enum SynthCommandType {
    CMD_NOTE_ON,
    CMD_NOTE_OFF,
    CMD_ALL_NOTES_OFF,
    CMD_SET_CHANNEL_PRESET,
    CMD_SET_CHANNEL_DRUM,
};

struct SynthCommand {
    int type;
    int channel;
    int key;
    float velocity;
    int bank;
    int preset;
};

struct SynthHandle {
    tsf* synth;
    std::mutex tsf_mutex;
    std::mutex queue_mutex;
    std::vector<SynthCommand> command_queue;
};

static void execute_note_ons(SynthHandle* h, const std::vector<SynthCommand>& cmds) {
    for (auto& cmd : cmds) {
        if (cmd.type == CMD_NOTE_ON) {
            if (!tsf_channel_note_on(h->synth, cmd.channel, cmd.key, cmd.velocity)) {
                LOGE("CMD_NOTE_ON failed: channel=%d key=%d vel=%.2f", cmd.channel, cmd.key, cmd.velocity);
            }
        } else if (cmd.type == CMD_SET_CHANNEL_PRESET) {
            tsf_channel_set_bank_preset(h->synth, cmd.channel, cmd.bank, cmd.preset);
        } else if (cmd.type == CMD_SET_CHANNEL_DRUM) {
            tsf_channel_set_presetnumber(h->synth, cmd.channel, 0, 1);
        }
    }
}

static void execute_note_offs(SynthHandle* h, const std::vector<SynthCommand>& cmds) {
    for (auto& cmd : cmds) {
        if (cmd.type == CMD_NOTE_OFF) {
            tsf_channel_note_off(h->synth, cmd.channel, cmd.key);
        } else if (cmd.type == CMD_ALL_NOTES_OFF) {
            tsf_note_off_all(h->synth);
            if (h->synth->channels) {
                for (int ch = 0; ch < h->synth->channels->channelNum; ++ch) {
                    tsf_channel_sounds_off_all(h->synth, ch);
                    tsf_channel_note_off_all(h->synth, ch);
                }
            }
        }
    }
}

// Drain the command queue into a local vector (queue_mutex locked briefly)
static std::vector<SynthCommand> drain_queue(SynthHandle* h) {
    std::vector<SynthCommand> cmds;
    std::lock_guard<std::mutex> lock(h->queue_mutex);
    cmds.swap(h->command_queue);
    return cmds;
}

extern "C" JNIEXPORT jlong JNICALL
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeLoad(
    JNIEnv* env, jclass /*clazz*/, jstring path, jint sampleRate)
{
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading SoundFont from: %s", pathStr);
    tsf* synth = tsf_load_filename(pathStr);
    env->ReleaseStringUTFChars(path, pathStr);

    if (!synth) {
        LOGE("Failed to load SoundFont");
        return 0;
    }

    const int outSampleRate = sampleRate >= 8000 ? sampleRate : 44100;
    LOGI("Rendering at %d Hz", outSampleRate);
    tsf_set_output(synth, TSF_MONO, (float)outSampleRate, 0.0f);
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
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeUnload(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle_)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle_);
    if (!h) return;

    // Process any remaining queued commands before closing
    {
        std::lock_guard<std::mutex> qlock(h->queue_mutex);
        std::lock_guard<std::mutex> tlock(h->tsf_mutex);
        execute_note_offs(h, h->command_queue);
        execute_note_ons(h, h->command_queue);
        h->command_queue.clear();
        tsf_close(h->synth);
    }
    delete h;
    LOGD("SoundFont unloaded");
}

extern "C" JNIEXPORT jint JNICALL
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeGetPresetIndex(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle_, jint bank, jint preset)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle_);
    if (!h) return -1;
    int idx = tsf_get_presetindex(h->synth, bank, preset);
    LOGD("nativeGetPresetIndex(bank=%d preset=%d) -> %d", bank, preset, idx);
    return idx;
}

extern "C" JNIEXPORT jint JNICALL
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeActiveVoiceCount(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle_)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle_);
    if (!h) return -1;
    // NOTE: not thread-safe — for debugging only; synth state may change
    // concurrently on the render thread
    return tsf_active_voice_count(h->synth);
}

extern "C" JNIEXPORT void JNICALL
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeChannelNoteOn(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle_, jint channel, jint key, jfloat velocity)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle_);
    if (!h) return;
    std::lock_guard<std::mutex> lock(h->queue_mutex);
    h->command_queue.push_back({CMD_NOTE_ON, channel, key, velocity, 0, 0});
}

extern "C" JNIEXPORT void JNICALL
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeChannelNoteOff(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle_, jint channel, jint key)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle_);
    if (!h) return;
    std::lock_guard<std::mutex> lock(h->queue_mutex);
    h->command_queue.push_back({CMD_NOTE_OFF, channel, key, 0.0f, 0, 0});
}

extern "C" JNIEXPORT void JNICALL
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeAllNotesOff(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle_)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle_);
    if (!h) return;
    std::lock_guard<std::mutex> lock(h->queue_mutex);
    h->command_queue.push_back({CMD_ALL_NOTES_OFF, 0, 0, 0.0f, 0, 0});
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeSetChannelPreset(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle_, jint channel, jint bank, jint preset)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle_);
    if (!h) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(h->queue_mutex);
    h->command_queue.push_back({CMD_SET_CHANNEL_PRESET, channel, 0, 0.0f, bank, preset});
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeSetChannelDrum(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle_, jint channel)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle_);
    if (!h) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(h->queue_mutex);
    h->command_queue.push_back({CMD_SET_CHANNEL_DRUM, channel, 0, 0.0f, 0, 0});
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_net_exent_harmonisk_audio_SoundFontSynthesizer_nativeRender(
    JNIEnv* env, jclass /*clazz*/, jlong handle_, jshortArray buffer, jint samples)
{
    auto* h = reinterpret_cast<SynthHandle*>(handle_);
    if (!h) return;

    jshort* buf = env->GetShortArrayElements(buffer, nullptr);
    if (!buf) return;

    // Drain queue under queue_mutex first (lock ordering: queue before tsf,
    // consistent with nativeUnload). A command queued between drain and
    // the tsf lock will be picked up on the next render cycle (~11.6ms).
    auto cmds = drain_queue(h);

    {
        std::lock_guard<std::mutex> lock(h->tsf_mutex);
        // Stop old notes before rendering — they fade during this cycle.
        execute_note_offs(h, cmds);
        tsf_render_short(h->synth, buf, samples, 0);
        // Start new notes after rendering — they first produce audio in
        // the next cycle, creating a ~11.6ms gap between release and
        // attack so repeated same-pitch notes sound as separate events.
        execute_note_ons(h, cmds);
    }

    env->ReleaseShortArrayElements(buffer, buf, 0);
}
