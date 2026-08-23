package com.k2fsa.sherpa.onnx

class OfflineStream(val ptr: Long) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int = 16000) =
        acceptWaveform(ptr, samples, sampleRate)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)

    private external fun delete(ptr: Long)
}
