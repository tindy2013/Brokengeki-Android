package com.github.brokengeki

import android.app.Application

class BrokengekiApplication : Application() {
    var lastServer: String
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getString("server", "") ?: ""
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putString("server", value).apply()
        }

    var showDelay: Boolean
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getBoolean("show_delay", false)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putBoolean("show_delay", value).apply()
        }

    var enableVibrate: Boolean
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getBoolean("enable_vibrate", true)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putBoolean("enable_vibrate", value).apply()
        }

    var tcpMode: Boolean
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getBoolean("tcp_mode", false)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putBoolean("tcp_mode", value).apply()
        }

    var enableGyroLever: Boolean
        get() {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            return config.getBoolean("gyro_lever", true)
        }
        set(value) {
            val config = getSharedPreferences(settings_preference, MODE_PRIVATE)
            config.edit().putBoolean("gyro_lever", value).apply()
        }

    companion object {
        private const val settings_preference = "settings"
    }
}