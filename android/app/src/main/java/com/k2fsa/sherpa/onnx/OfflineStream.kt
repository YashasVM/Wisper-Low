package com.k2fsa.sherpa.onnx

class OfflineStream(ptr: Long) {
    internal var ptr: Long = ptr
        private set

    fun acceptWaveform(samples: FloatArray, sampleRate: Int = 16000) =
        acceptWaveform(ptr, samples, sampleRate)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    fun release() = finalize()

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)

    private external fun delete(ptr: Long)
}
