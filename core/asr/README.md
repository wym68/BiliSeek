# :core:asr — 离线语音识别

工单：`docs/archive/10-AI总结-实现工单.md` AI-7。只认「本地音频文件 → 带时间戳的句子」，
不依赖 `:core:ai` / `:core:network` / `:core:player`，不知道 B 站。

```
音频文件 → PcmDecoder（MediaExtractor + MediaCodec，按块拉取，收尾等输出端 EOS）
        → ResamplePolicy（下混单声道 + 线性插值到 16 kHz，流式）
        → Silero VAD（512 样本窗口；合并 <0.3s 间隔、丢 <0.25s、20s 起尽快切开）
        → VadSegmentPolicy（兜底：>30s 等长硬切）
        → SenseVoice OfflineRecognizer（逐片段）
        → AsrTextPolicy（去 <|…|> 标签、规整空白与重复标点）
        → AsrCheckpoint（每句追加一行 JSON；续做起点 = 最后一句 endMs）
```

## 公开 API

`AsrSegment`、`AsrProgress`、`AsrError`、`AsrModelSpec`、`ModelFile`、`ModelSource`、`ModelState`、
`OfflineTranscriber`、`AsrModelManager`、`AsrModelCatalog`。其余一律 `internal`。

## 入库的第三方产物

| 文件 | 来源 | 版本 | SHA-256 / 许可证 |
|------|------|------|------|
| `libs/sherpa-onnx-classes-1.13.8.jar` | 官方 `sherpa-onnx-1.13.8.aar` 内的 `classes.jar` | 1.13.8 | AAR sha256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`；jar sha256 `8af5aa647494ec9cb6596959d6d20864377cf4a9dedf1c1b178d936bfb7cea95`；Apache-2.0 |
| `src/main/jniLibs/{arm64-v8a,x86_64}/libsherpa-onnx-jni.so` | 同一 AAR 的 `jni/` | 1.13.8 | Apache-2.0 |
| `src/main/jniLibs/{arm64-v8a,x86_64}/libonnxruntime.so` | 同一 AAR 的 `jni/` | 随 1.13.8 | MIT |
| `src/main/assets/silero_vad.onnx` | sherpa-onnx `asr-models` 发布页 | — | sha256 `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`；MIT |

**与工单的差异**：工单写的是直接入库整个 AAR（`implementation(files("….aar"))`）。实际 AGP 不允许
library 模块直接依赖本地 AAR（打包 AAR 时无法把它合并进去），所以改为拆开入库：`classes.jar` +
我们要的两个 ABI 的 `.so`（AAR 里其余 ABI 与 QNN 等可选库不入库）。包名 `com.k2fsa.sherpa.onnx`
不能改（JNI 符号绑定包名）。release 包再由 app 的 `ndk.abiFilters` 只留 arm64-v8a。

R8 规则在 `consumer-rules.pro`，随模块下发给 app。

## 模型（不进 APK）

`AsrModelCatalog.SENSE_VOICE_2024_07_17`：sherpa-onnx 转换的 SenseVoice-Small int8（2024-07-17），
存放 `filesDir/asr_models/sensevoice_small_int8_2024_07_17/`。

| 文件 | 字节 | SHA-256 |
|------|------|------|
| `model.int8.onnx` | 239233841 | `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` |
| `tokens.txt` | 315894 | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` |

字节数与哈希取自官方 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` 解包后的文件。
许可证：FunASR Model License（模型不随 APK 分发，由用户下载 / 导入）。

选 2024-07-17 而不是工单初稿的 2025-09-09：后者是用 WSYue 粤语数据微调的衍生版本，许可证链更复杂。

下载来源（逐文件，任何来源都过同一组 SHA-256）：
- HuggingFace：`https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/`
- HuggingFace 国内镜像：`https://hf-mirror.com/…/resolve/main/`

> **未验证**：开发环境访问不了 HuggingFace，上面两个 URL 下的 `model.int8.onnx` / `tokens.txt`
> 是否与 tar 包内文件逐字节一致尚未核对。真机首次下载时若报「校验失败」，说明该仓库的文件与
> tar 包不同——此时改用「导入模型文件」（从 GitHub Releases 下载 tar.bz2 解压后导入两个文件），
> 并据实修正 `AsrModelCatalog`。

## 测试

- JVM 单测（`./gradlew :core:asr:test`，离线）：`ResamplePolicyTest`、`VadSegmentPolicyTest`、
  `AsrTextPolicyTest`、`AsrCheckpointTest`、`ModelStoreTest`（本地 MockWebServer：多文件下载、
  Range 续传、服务器忽略 Range、哈希不符 → Broken、换来源复用、导入按名 / 按大小匹配、进度节流）。
- 需真机：`PcmDecoder` 收尾不丢尾、`OfflineTranscriber` 端到端与 RTF——**未做**，见工单验收记录。

## 升级流程

1. 下载新版官方 AAR，核对 sha256；解出 `classes.jar` 改名入 `libs/`，`jni/arm64-v8a`、`jni/x86_64` 下的
   两个 `.so` 覆盖 `src/main/jniLibs/`；改 `build.gradle.kts` 里的文件名与本 README 的版本表。
2. `javap` 对比 `OfflineRecognizer` / `Vad` / 各 Config 的构造与属性是否变化（我们用无参构造 + 属性赋值，
   对新增字段不敏感）。
3. 真机跑一次本地识别（debug + release 各一次，release 验证 R8 规则）。
