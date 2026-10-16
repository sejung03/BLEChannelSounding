package com.example.blecsranger.storage

import android.content.Context
import com.example.blecsranger.model.BoardConfig

class BoardPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("board_config", Context.MODE_PRIVATE)

    fun load(): List<BoardConfig> {
        val defaults = listOf(
            BoardConfig(0, "A", "AA:BB:CC:DD:EE:01"),
            BoardConfig(1, "B", "AA:BB:CC:DD:EE:02"),
            BoardConfig(2, "C", "AA:BB:CC:DD:EE:03"),
            BoardConfig(3, "D", "AA:BB:CC:DD:EE:04"),
        )
        return defaults.map { default ->
            BoardConfig(
                index = default.index,
                label = default.label,
                macAddress = preferences.getString("mac_${default.index}", default.macAddress) ?: default.macAddress,
            )
        }
    }

    fun save(boards: List<BoardConfig>) {
        preferences.edit().apply {
            boards.forEach { board ->
                putString("mac_${board.index}", board.macAddress)
            }
        }.apply()
    }
}
