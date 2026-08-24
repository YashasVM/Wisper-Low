package com.wisperlow.mobile.stt

data class SttModel(
    val id: String,
    val displayName: String,
    val downloadUrl: String,
    val archiveName: String,
    val dirName: String,
    val sizeHintMb: Int,
)

object ModelCatalog {
    val PARAKEET_V3_INT8 = SttModel(
        id = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6B v3 (int8)",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        archiveName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        dirName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        sizeHintMb = 490,
    )

    val all: List<SttModel> = listOf(PARAKEET_V3_INT8)

    fun byId(id: String): SttModel? = all.firstOrNull { it.id == id }
}
