package com.example.aichat.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 基于系统 [SpeechRecognizer] 的语音转文字封装（方案 A）。
 *
 * 设计要点：
 *  - 只暴露「开始 / 停止 / 释放」三个动作，结果通过 [Callback] 回传，
 *    界面层不直接接触 SpeechRecognizer，日后换成云端 ASR 或端侧模型时
 *    只需替换本类实现。
 *  - 回调全部在主线程触发（SpeechRecognizer 本身即在主线程回调）。
 *  - 识别结束后必须调用 [release]，否则系统识别服务会一直被占用。
 *
 * 已知局限（与方案评估一致）：
 *  - 部分机型/模拟器没有识别服务，[isAvailable] 返回 false；
 *  - 个别厂商会在识别时弹出自己的界面，无法完全避免。
 */
class VoiceInputEngine(private val context: Context) {

    /** 识别过程回调。 */
    interface Callback {
        /** 识别服务已开始收音。 */
        fun onReady()

        /** 识别过程中返回的开始音量（dB），用于「正在听」的动画强度。 */
        fun onVolumeChanged(rmsDb: Float)

        /** 临时结果，用于实时显示识别中的文字。 */
        fun onPartial(text: String)

        /** 最终结果。无结果时 [text] 为空串，[reason] 给出提示文案。 */
        fun onResult(text: String, error: String?)

        /** 识别结束（成功或失败），界面应退出「正在听」状态。 */
        fun onFinished()
    }

    private var recognizer: SpeechRecognizer? = null
    private var callback: Callback? = null
    private var listening = false

    /** 系统是否提供可用的语音识别服务。调用前无需权限。 */
    fun isAvailable(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (e: Exception) {
        false
    }

    /** 是否处于收音状态。 */
    fun isListening(): Boolean = listening

    /**
     * 开始一次识别。
     *
     * 每次开始都重新创建 SpeechRecognizer：部分机型在一次识别被取消后再复用
     * 实例会直接回调 ERROR_CLIENT，重建是最稳的做法。
     */
    fun start(cb: Callback) {
        if (listening) return
        if (!isAvailable()) {
            cb.onResult("", "本机不支持语音识别，请改用键盘输入。")
            cb.onFinished()
            return
        }

        callback = cb
        listening = true
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            it.startListening(buildIntent())
        }
    }

    /** 用户主动停止收音（如再次点麦克风），仍在等待系统返回最终结果。 */
    fun stop() {
        if (!listening) return
        recognizer?.stopListening()
    }

    /** 取消本次识别并丢弃结果。 */
    fun cancel() {
        if (!listening) return
        listening = false
        recognizer?.cancel()
        destroyRecognizer()
        callback?.onFinished()
        callback = null
    }

    /** 彻底释放系统识别服务，调用方应在页面销毁时调用。 */
    fun release() {
        listening = false
        destroyRecognizer()
        callback = null
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            it.setRecognitionListener(null)
            it.destroy()
        }
        recognizer = null
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        // 自由说，适合聊天输入；不限制语法。
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG_ZH_CN)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANG_ZH_CN)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 静音自动结束的宽容度：稍长一点，避免说话中途停顿就被截断。
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
    }

    private val listener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            callback?.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            callback?.onVolumeChanged(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            val reason = describeError(error)
            // 「没有识别到内容」不算故障，按空结果处理，不弹错误。
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                finish("", null)
            } else {
                finish("", reason)
            }
        }

        override fun onResults(results: Bundle?) {
            finish(pickBest(results), null)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = pickBest(partialResults)
            if (text.isNotEmpty()) callback?.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** 收尾：先回调结果，再通知结束并清理识别服务。 */
    private fun finish(text: String, error: String?) {
        val cb = callback
        listening = false
        destroyRecognizer()
        if (cb != null) {
            cb.onResult(text, error)
            cb.onFinished()
        }
        callback = null
    }

    /** 从结果 Bundle 中取第一条识别文字。 */
    private fun pickBest(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，请检查麦克风是否被其他应用占用。"
        SpeechRecognizer.ERROR_CLIENT -> "语音识别被中断，请重试。"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限，请在系统设置中开启。"
        SpeechRecognizer.ERROR_NETWORK -> "语音识别需要网络，请检查网络后重试。"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时，语音识别失败。"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，请稍后再试。"
        SpeechRecognizer.ERROR_SERVER -> "语音识别服务出错，请稍后重试。"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话声。"
        else -> "语音识别失败（错误码 $error）。"
    }

    private companion object {
        const val LANG_ZH_CN = "zh-CN"
    }
}
