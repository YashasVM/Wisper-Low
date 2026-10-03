package com.wisperlow.mobile.stt

/** What a model is good at; the UI maps this to user-facing copy. */
enum class ModelKind {
    /** Highest accuracy, 25 European languages. */
    MULTILINGUAL_ACCURATE,

    /** Highest accuracy, English only. */
    ENGLISH_ACCURATE,

    /** Small and fast, English only. Best for older or low-memory phones. */
    ENGLISH_FAST,
}

data class SttModel(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    val downloadUrl: String,
    val archiveName: String,
    val dirName: String,
    val sizeHintMb: Int,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
)

object ModelCatalog {
    val PARAKEET_V3_INT8 = SttModel(
        id = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet 0.6B v3",
        kind = ModelKind.MULTILINGUAL_ACCURATE,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        archiveName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        dirName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        sizeHintMb = 490,
        archiveSizeBytes = 487_170_055L,
        archiveSha256 = "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf",
    )

    val PARAKEET_V2_INT8 = SttModel(
        id = "parakeet-tdt-0.6b-v2-int8",
        displayName = "Parakeet 0.6B v2",
        kind = ModelKind.ENGLISH_ACCURATE,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        archiveName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
        dirName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        sizeHintMb = 485,
        archiveSizeBytes = 482_468_385L,
        archiveSha256 = "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad",
    )

    val PARAKEET_110M_EN_INT8 = SttModel(
        id = "parakeet-tdt-ctc-110m-en-int8",
        displayName = "Parakeet 110M",
        kind = ModelKind.ENGLISH_FAST,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2",
        archiveName = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2",
        dirName = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        sizeHintMb = 105,
        archiveSizeBytes = 104_337_827L,
        archiveSha256 = "17f945007b52ccd8b7200ffc7c5652e9e8e961dfdf479cefcabd06cf5703630b",
    )

    val DEFAULT: SttModel = PARAKEET_V3_INT8

    val all: List<SttModel> = listOf(PARAKEET_V3_INT8, PARAKEET_110M_EN_INT8, PARAKEET_V2_INT8)

    fun byId(id: String): SttModel? = all.firstOrNull { it.id == id }
}
