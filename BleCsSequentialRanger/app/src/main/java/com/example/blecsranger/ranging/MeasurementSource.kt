package com.example.blecsranger.ranging

import com.example.blecsranger.model.BoardConfig
import com.example.blecsranger.model.MeasurementResult
import com.example.blecsranger.model.RawMeasurementObservation

interface MeasurementSource : AutoCloseable {
    interface Request {
        fun cancel()
    }

    fun prepare(
        boards: List<BoardConfig>,
        onReady: (String) -> Unit,
        onError: (String) -> Unit,
    ): Request

    fun measureOnce(
        board: BoardConfig,
        onObservation: (RawMeasurementObservation) -> Unit,
        onResult: (MeasurementResult) -> Unit,
        onError: (String) -> Unit,
    ): Request

    override fun close()
}
