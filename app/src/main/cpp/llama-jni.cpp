#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_jarvis_assistant_llm_LlamaLLMEngine_nativeLoadModel(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    
    llama_model_params model_params = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path, model_params);
    
    env->ReleaseStringUTFChars(model_path, path);
    
    if (!model) {
        LOGE("Failed to load model from %s", path);
        return 0;
    }
    
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jlong JNICALL
Java_com_jarvis_assistant_llm_LlamaLLMEngine_nativeCreateContext(JNIEnv *env, jobject thiz, jlong model_ptr, jint context_size) {
    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    if (!model) return 0;
    
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = context_size;
    ctx_params.n_batch = context_size;
    
    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        return 0;
    }
    
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_jarvis_assistant_llm_LlamaLLMEngine_nativeGenerate(JNIEnv *env, jobject thiz, jlong ctx_ptr, jstring prompt, jint max_tokens) {
    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    if (!ctx) return env->NewStringUTF("");
    
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    
    const llama_model *model = llama_get_model(ctx);
    const struct llama_vocab *vocab = llama_model_get_vocab(model);
    
    std::vector<llama_token> tokens;
    tokens.resize(llama_n_ctx(ctx));
    
    int n_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str), tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return env->NewStringUTF("Error: Tokenization failed");
    }
    
    // Very simplified generation loop placeholder
    std::string result = "Jarvis is online and JNI is functional. (Full generation loop implementation needed)";
    
    env->ReleaseStringUTFChars(prompt, prompt_str);
    
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_jarvis_assistant_llm_LlamaLLMEngine_nativeFreeContext(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    llama_context *ctx = reinterpret_cast<llama_context *>(ctx_ptr);
    if (ctx) llama_free(ctx);
}

JNIEXPORT void JNICALL
Java_com_jarvis_assistant_llm_LlamaLLMEngine_nativeFreeModel(JNIEnv *env, jobject thiz, jlong model_ptr) {
    llama_model *model = reinterpret_cast<llama_model *>(model_ptr);
    if (model) llama_model_free(model);
}

}
