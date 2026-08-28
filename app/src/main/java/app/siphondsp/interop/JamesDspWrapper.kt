package app.siphondsp.interop

import app.siphondsp.model.ProcessorMessage

typealias JamesDspHandle = Long

object JamesDspWrapper {
    external fun alloc(callbacks: JamesDspCallbacks): JamesDspHandle
    external fun free(self: JamesDspHandle)
    external fun isHandleValid(self: JamesDspHandle): Boolean

    external fun getBenchmarkSize(): Int
    external fun runBenchmark(c0: DoubleArray, c1: DoubleArray)
    external fun loadBenchmark(c0: DoubleArray, c1: DoubleArray)

    external fun processInt16(self: JamesDspHandle, input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    external fun processInt8U24(self: JamesDspHandle, input: IntArray): IntArray
    external fun processInt24Packed(self: JamesDspHandle, input: BooleanArray): BooleanArray
    external fun processInt32(self: JamesDspHandle, input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    external fun processFloat(self: JamesDspHandle, input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)

    external fun setSamplingRate(self: JamesDspHandle, sampleRate: Float, forceRefresh: Boolean)
    external fun configureNativeBmwDsp(self: JamesDspHandle, values: FloatArray): Boolean
    external fun configureNativeBmwPeq(
        self: JamesDspHandle,
        enabled: Boolean,
        preampDb: Float,
        fullRangeBands: DoubleArray,
        lowBandBands: DoubleArray,
        midBandBands: DoubleArray,
    ): Boolean
    external fun setNativeBmwDspSampleRate(self: JamesDspHandle, sampleRate: Float)
    external fun getNativeBmwCompressorMeter(self: JamesDspHandle): FloatArray?
    external fun startNativeBmwCapture(self: JamesDspHandle)
    external fun stopNativeBmwCapture(self: JamesDspHandle)
    external fun getNativeBmwCaptureFrameCount(self: JamesDspHandle): Long
    external fun exportNativeBmwCaptureWav(self: JamesDspHandle, rawInPath: String, outPath: String): FloatArray?

    external fun setLimiter(self: JamesDspHandle, threshold: Float, release: Float): Boolean
    external fun setPostGain(self: JamesDspHandle, postGain: Float): Boolean
    external fun setConvolver(self: JamesDspHandle, enable: Boolean, impulseResponse: FloatArray, irChannels: Int, irFrames: Int): Boolean

    external fun eelErrorCodeToString(errorCode: Int): String

    interface JamesDspCallbacks {
        fun onLiveprogOutput(message: String)
        fun onLiveprogExec(id: String)
        fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?)
        fun onVdcParseError()
        fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode)
    }

    init {
        System.loadLibrary("jamesdsp-wrapper")
    }
}
