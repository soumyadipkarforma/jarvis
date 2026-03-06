package com.jarvis.assistant.commands

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log

/**
 * Offline command processor for handling voice commands locally.
 *
 * Supports the following command types:
 *   - "open <app>" — Launch an installed application
 *   - "set alarm <time>" — Set an alarm
 *   - "set timer <duration>" — Set a countdown timer
 *   - "turn on/off flashlight" — Toggle flashlight
 *   - "turn on/off wifi" — Open WiFi settings
 *   - "turn on/off bluetooth" — Open Bluetooth settings
 *   - "what time is it" — Report current time
 *   - "what's the date" — Report current date
 *   - "battery level" — Report battery percentage
 *   - "volume up/down" — Adjust media volume
 *
 * Commands that cannot be handled locally are passed through to the LLM.
 */
class CommandProcessor(private val context: Context) {

    companion object {
        private const val TAG = "CommandProcessor"
    }

    /**
     * Result of command processing.
     */
    sealed class CommandResult {
        /** Command was recognized and an intent is ready to execute */
        data class Execute(val intent: Intent, val feedback: String) : CommandResult()

        /** Command was recognized and handled directly (no intent needed) */
        data class DirectResponse(val response: String) : CommandResult()

        /** Command was not recognized — should be forwarded to LLM */
        object NotACommand : CommandResult()
    }

    /**
     * Process a text command and determine the appropriate action.
     *
     * @param text The transcribed user speech
     * @return A CommandResult indicating the action to take
     */
    fun processCommand(text: String): CommandResult {
        val normalized = text.lowercase().trim()
        Log.d(TAG, "Processing command: $normalized")

        return when {
            normalized.startsWith("open ") -> handleOpenApp(normalized.removePrefix("open ").trim())
            normalized.startsWith("launch ") -> handleOpenApp(normalized.removePrefix("launch ").trim())
            normalized.startsWith("start ") -> handleOpenApp(normalized.removePrefix("start ").trim())

            normalized.startsWith("set alarm ") -> handleSetAlarm(normalized)
            normalized.startsWith("set timer ") || normalized.startsWith("set a timer ") -> handleSetTimer(normalized)

            normalized.contains("flashlight") || normalized.contains("torch") -> handleFlashlight(normalized)

            normalized.contains("wifi") || normalized.contains("wi-fi") -> handleWifiSettings(normalized)
            normalized.contains("bluetooth") -> handleBluetoothSettings(normalized)

            normalized == "what time is it" || normalized.contains("current time") ||
                normalized == "what's the time" -> handleTimeQuery()

            normalized.contains("what's the date") || normalized.contains("what date") ||
                normalized.contains("today's date") -> handleDateQuery()

            normalized.contains("battery") -> handleBatteryQuery()

            normalized.contains("volume up") -> handleVolumeChange(true)
            normalized.contains("volume down") -> handleVolumeChange(false)

            else -> CommandResult.NotACommand
        }
    }

    /**
     * Handle "open <app>" command.
     * Searches installed apps by label to find a matching package.
     */
    internal fun handleOpenApp(appName: String): CommandResult {
        if (appName.isBlank()) {
            return CommandResult.DirectResponse("Which app would you like me to open?")
        }

        val packageManager = context.packageManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            )
        }

        // Find the best matching app: prefer exact match, then shortest containing match
        val normalizedAppName = appName.lowercase()
        val matchingApp = intent
            .map { resolveInfo ->
                val label = resolveInfo.loadLabel(packageManager).toString().lowercase()
                resolveInfo to label
            }
            .filter { (_, label) -> label == normalizedAppName || label.contains(normalizedAppName) }
            .sortedWith(compareByDescending<Pair<android.content.pm.ResolveInfo, String>> { (_, label) ->
                label == normalizedAppName  // Exact matches first
            }.thenBy { (_, label) ->
                label.length  // Then shortest match
            })
            .firstOrNull()
            ?.first

        return if (matchingApp != null) {
            val label = matchingApp.loadLabel(packageManager).toString()
            val launchIntent = packageManager.getLaunchIntentForPackage(
                matchingApp.activityInfo.packageName
            )

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                CommandResult.Execute(launchIntent, "Opening $label")
            } else {
                CommandResult.DirectResponse("I found $label but couldn't create a launch intent.")
            }
        } else {
            CommandResult.DirectResponse("Sorry, I couldn't find an app called \"$appName\".")
        }
    }

    /**
     * Handle "set alarm" command.
     */
    private fun handleSetAlarm(text: String): CommandResult {
        // Extract time from text (simple pattern matching)
        val timeRegex = Regex("""(\d{1,2}):?(\d{2})?\s*(am|pm|a\.m\.|p\.m\.)?""", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(text)

        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: 8
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase()

            if (ampm.startsWith("p") && hour < 12) hour += 12
            if (ampm.startsWith("a") && hour == 12) hour = 0

            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val timeStr = String.format("%02d:%02d", hour, minute)
            return CommandResult.Execute(alarmIntent, "Setting alarm for $timeStr")
        }

        return CommandResult.DirectResponse("I couldn't understand the time. Please say something like 'set alarm 7:30 AM'.")
    }

    /**
     * Handle "set timer" command.
     */
    private fun handleSetTimer(text: String): CommandResult {
        val minuteRegex = Regex("""(\d+)\s*minute""", RegexOption.IGNORE_CASE)
        val secondRegex = Regex("""(\d+)\s*second""", RegexOption.IGNORE_CASE)

        val minutes = minuteRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = secondRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val totalSeconds = minutes * 60 + seconds

        if (totalSeconds > 0) {
            val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val display = when {
                minutes > 0 && seconds > 0 -> "$minutes minutes and $seconds seconds"
                minutes > 0 -> "$minutes minute${if (minutes != 1) "s" else ""}"
                else -> "$seconds second${if (seconds != 1) "s" else ""}"
            }

            return CommandResult.Execute(timerIntent, "Setting timer for $display")
        }

        return CommandResult.DirectResponse("I couldn't understand the duration. Please say something like 'set timer 5 minutes'.")
    }

    /**
     * Handle flashlight toggle command.
     */
    private fun handleFlashlight(text: String): CommandResult {
        // Flashlight requires Camera2 API, handled separately in the service
        val turnOn = text.contains("on") || text.contains("enable")
        val action = if (turnOn) "on" else "off"
        return CommandResult.DirectResponse("FLASHLIGHT_$action")
    }

    /**
     * Handle WiFi settings command.
     */
    private fun handleWifiSettings(text: String): CommandResult {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return CommandResult.Execute(intent, "Opening WiFi settings")
    }

    /**
     * Handle Bluetooth settings command.
     */
    private fun handleBluetoothSettings(text: String): CommandResult {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return CommandResult.Execute(intent, "Opening Bluetooth settings")
    }

    /**
     * Handle time query.
     */
    private fun handleTimeQuery(): CommandResult {
        val format = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val time = format.format(java.util.Date())
        return CommandResult.DirectResponse("The current time is $time.")
    }

    /**
     * Handle date query.
     */
    private fun handleDateQuery(): CommandResult {
        val format = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault())
        val date = format.format(java.util.Date())
        return CommandResult.DirectResponse("Today is $date.")
    }

    /**
     * Handle battery level query.
     */
    private fun handleBatteryQuery(): CommandResult {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val level = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        return if (level >= 0) {
            CommandResult.DirectResponse("Your battery is at $level percent.")
        } else {
            CommandResult.DirectResponse("I couldn't determine the battery level.")
        }
    }

    /**
     * Handle volume adjustment.
     */
    private fun handleVolumeChange(increase: Boolean): CommandResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audioManager != null) {
            val direction = if (increase) {
                android.media.AudioManager.ADJUST_RAISE
            } else {
                android.media.AudioManager.ADJUST_LOWER
            }
            audioManager.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                direction,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            val action = if (increase) "increased" else "decreased"
            return CommandResult.DirectResponse("Volume $action.")
        }
        return CommandResult.DirectResponse("I couldn't adjust the volume.")
    }

    /**
     * Get a list of all installed apps that can be launched.
     * Useful for suggesting completions or debugging.
     */
    fun getInstalledApps(): List<String> {
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            )
        }.map { it.loadLabel(packageManager).toString() }.sorted()
    }
}
