package app.andy.model

data class TracePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val template: String,
)

object TraceConfigPresets {
    val Default = TracePreset(
        id = "default",
        title = "Default",
        subtitle = "The default config for general purpose tracing",
        template = """
            buffers {
              size_kb: 65536
              fill_policy: RING_BUFFER
            }

            data_sources {
              config {
                name: "linux.ftrace"
                target_buffer: 0
                ftrace_config {
                  ftrace_events: "sched/sched_switch"
                  ftrace_events: "sched/sched_wakeup"
                  ftrace_events: "sched/sched_waking"
                  ftrace_events: "sched/sched_process_exit"
                  ftrace_events: "task/task_newtask"
                  ftrace_events: "task/task_rename"
                  ftrace_events: "power/cpu_frequency"
                  ftrace_events: "power/cpu_idle"
                  ftrace_events: "power/suspend_resume"
                }
              }
            }

            data_sources {
              config {
                name: "linux.process_stats"
                target_buffer: 0
                process_stats_config {
                  scan_all_processes_on_start: true
                  proc_stats_poll_ms: 1000
                }
              }
            }

            data_sources {
              config {
                name: "linux.sys_stats"
                target_buffer: 0
                sys_stats_config {
                  meminfo_period_ms: 1000
                  meminfo_counters: MEMINFO_MEM_TOTAL
                  meminfo_counters: MEMINFO_MEM_FREE
                  meminfo_counters: MEMINFO_BUFFERS
                  meminfo_counters: MEMINFO_CACHED
                  meminfo_counters: MEMINFO_SWAP_CACHED
                  meminfo_counters: MEMINFO_ACTIVE
                  meminfo_counters: MEMINFO_INACTIVE
                  stat_period_ms: 1000
                  stat_counters: STAT_CPU_TIMES
                  stat_counters: STAT_FORK_COUNT
                }
              }
            }
        """.trimIndent(),
    )

    val Battery = TracePreset(
        id = "battery",
        title = "Battery",
        subtitle = "Battery usage and power consumption",
        template = """
            buffers {
              size_kb: 32768
              fill_policy: RING_BUFFER
            }

            data_sources {
              config {
                name: "android.power"
                target_buffer: 0
                android_power_config {
                  battery_poll_ms: 1000
                  battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT
                  battery_counters: BATTERY_COUNTER_CHARGE
                  battery_counters: BATTERY_COUNTER_CURRENT
                  collect_power_rails: true
                }
              }
            }

            data_sources {
              config {
                name: "linux.ftrace"
                target_buffer: 0
                ftrace_config {
                  ftrace_events: "power/suspend_resume"
                  ftrace_events: "power/cpu_frequency"
                  ftrace_events: "power/cpu_idle"
                  ftrace_events: "regulator/regulator_set_voltage"
                }
              }
            }
        """.trimIndent(),
    )

    val Thermal = TracePreset(
        id = "thermal",
        title = "Thermal",
        subtitle = "Thermal throttling and mitigation",
        template = """
            buffers {
              size_kb: 32768
              fill_policy: RING_BUFFER
            }

            data_sources {
              config {
                name: "linux.ftrace"
                target_buffer: 0
                ftrace_config {
                  ftrace_events: "thermal/thermal_temperature"
                  ftrace_events: "thermal/cdev_update"
                  ftrace_events: "power/cpu_frequency"
                  ftrace_events: "power/cpu_idle"
                  ftrace_events: "sched/sched_switch"
                }
              }
            }

            data_sources {
              config {
                name: "linux.sys_stats"
                target_buffer: 0
                sys_stats_config {
                  thermal_period_ms: 1000
                  cpufreq_period_ms: 1000
                }
              }
            }
        """.trimIndent(),
    )

    val Graphics = TracePreset(
        id = "graphics",
        title = "Graphics",
        subtitle = "Graphics pipeline and system compositor",
        template = """
            buffers {
              size_kb: 65536
              fill_policy: RING_BUFFER
            }

            data_sources {
              config {
                name: "linux.ftrace"
                target_buffer: 0
                ftrace_config {
                  atrace_categories: "gfx"
                  atrace_categories: "view"
                  atrace_categories: "wm"
                  atrace_categories: "am"
                  atrace_categories: "sf"
                  atrace_categories: "hal"
                  atrace_categories: "input"
                  atrace_apps: "*"
                  ftrace_events: "sched/sched_switch"
                  ftrace_events: "sched/sched_wakeup"
                  ftrace_events: "power/cpu_frequency"
                  ftrace_events: "power/cpu_idle"
                }
              }
            }

            data_sources {
              config {
                name: "android.surfaceflinger.frametimeline"
                target_buffer: 0
              }
            }

            data_sources {
              config {
                name: "android.gpu.memory"
                target_buffer: 0
              }
            }
        """.trimIndent(),
    )

    val Chrome = TracePreset(
        id = "chrome",
        title = "Chrome",
        subtitle = "Common Chrome trace events",
        template = """
            buffers {
              size_kb: 65536
              fill_policy: RING_BUFFER
            }

            data_sources {
              config {
                name: "org.chromium.trace_event"
                target_buffer: 0
                chrome_config {
                  trace_config: "{\"record_mode\":\"record-until-full\",\"included_categories\":[\"toplevel\",\"ipc\",\"cc\",\"blink\",\"ui\",\"gpu\",\"viz\",\"loading\",\"net\",\"navigation\",\"browser\",\"latency\",\"scheduler\"]}"
                }
              }
            }

            data_sources {
              config {
                name: "org.chromium.trace_metadata"
                target_buffer: 0
                chrome_config {
                  trace_config: "{\"record_mode\":\"record-until-full\",\"included_categories\":[\"*\"]}"
                }
              }
            }
        """.trimIndent(),
    )

    val V8 = TracePreset(
        id = "v8",
        title = "V8",
        subtitle = "JavaScript, wasm & GC",
        template = """
            buffers {
              size_kb: 65536
              fill_policy: RING_BUFFER
            }

            data_sources {
              config {
                name: "org.chromium.trace_event"
                target_buffer: 0
                chrome_config {
                  trace_config: "{\"record_mode\":\"record-until-full\",\"included_categories\":[\"v8\",\"v8.execute\",\"v8.wasm\",\"disabled-by-default-v8.compile\",\"disabled-by-default-v8.gc\",\"disabled-by-default-v8.cpu_profiler\"]}"
                }
              }
            }
        """.trimIndent(),
    )

    val Empty = TracePreset(
        id = "empty",
        title = "Empty",
        subtitle = "Start fresh",
        template = """
            buffers {
              size_kb: 65536
              fill_policy: RING_BUFFER
            }
        """.trimIndent(),
    )

    val all: List<TracePreset> = listOf(Default, Battery, Thermal, Graphics, Chrome, V8, Empty)

    fun byId(id: String): TracePreset? = all.firstOrNull { it.id == id }

    /**
     * Applies buffer size and duration (or long-trace write settings) to a preset template.
     * User-imported configs should be used verbatim and never passed through this.
     */
    fun materialize(template: String, durationSeconds: Int, bufferSizeMb: Int): String {
        val sizeKb = bufferSizeMb.coerceAtLeast(1) * 1024
        var result = SIZE_KB_IN_BUFFERS.replace(template) { match: MatchResult ->
            match.value.replace(Regex("""size_kb:\s*\d+"""), "size_kb: $sizeKb")
        }
        if (durationSeconds > 0) {
            result = appendOrReplace(result, "duration_ms", (durationSeconds * 1000).toString())
        } else {
            result = appendOrReplace(result, "write_into_file", "true")
            result = appendOrReplace(result, "file_write_period_ms", "2500")
        }
        result = appendOrReplace(result, "flush_period_ms", "5000")
        return result.trimEnd() + "\n"
    }

    private fun appendOrReplace(config: String, key: String, value: String): String {
        val pattern = Regex("""(?m)^$key:\s*\S+""")
        return if (pattern.containsMatchIn(config)) {
            pattern.replace(config, "$key: $value")
        } else {
            config.trimEnd() + "\n$key: $value\n"
        }
    }

    private val SIZE_KB_IN_BUFFERS = Regex(
        """(?s)buffers\s*\{[^{}]*?size_kb:\s*\d+[^{}]*?\}""",
    )
}
