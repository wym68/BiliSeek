package com.biliseek.core.asr.model

import com.biliseek.core.asr.AsrModelSpec
import com.biliseek.core.asr.ModelFile
import com.biliseek.core.asr.ModelSource

/**
 * 模型清单。字节数与 SHA-256 取自 sherpa-onnx 官方发布包
 * `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2`（GitHub Releases asr-models）内的文件。
 *
 * 选 2024-07-17 而不是 2025-09-09：后者由 WSYue 粤语数据微调，许可证链更复杂；前者直接来自
 * FunAudioLLM/SenseVoice 官方权重（FunASR 模型许可证）。见 README.md。
 */
object AsrModelCatalog {
    private const val HF_REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"

    val SENSE_VOICE_2024_07_17 = AsrModelSpec(
        id = "sensevoice_small_int8_2024_07_17",
        displayName = "SenseVoice Small（中/英/日/韩/粤）",
        files = listOf(
            ModelFile(
                name = "model.int8.onnx",
                bytes = 239_233_841L,
                sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            ),
            ModelFile(
                name = "tokens.txt",
                bytes = 315_894L,
                sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            ),
        ),
        sources = listOf(
            ModelSource("hf", "HuggingFace", "https://huggingface.co/$HF_REPO/resolve/main/"),
            ModelSource("hf_mirror", "HuggingFace 国内镜像", "https://hf-mirror.com/$HF_REPO/resolve/main/"),
        ),
        license = "FunASR Model License",
        licenseUrl = "https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE",
    )

    val default: AsrModelSpec = SENSE_VOICE_2024_07_17
    val all: List<AsrModelSpec> = listOf(SENSE_VOICE_2024_07_17)

    /** 随 APK 附带的 VAD 模型（assets 内路径）。 */
    internal const val VAD_ASSET = "silero_vad.onnx"
}
