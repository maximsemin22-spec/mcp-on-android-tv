package io.middlepoint.mcponandroid.mcp

import android.content.Context
import io.middlepoint.mcponandroid.utils.ADB
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Base64

/**
 * Authorized, direct ADB management tools. No UI automation required.
 *
 * Access to the entire MCP endpoint is guarded by the per-device access key.
 * Shell operations execute as Android's ADB 'shell' user, NOT root.
 */
class TvAdminTools(
    private val server: Server,
    private val context: Context,
    private val adb: ADB,
) {
    private data class Field(
        val name: String,
        val description: String,
        val type: String = "string",
        val mandatory: Boolean = true,
    )

    private fun schema(vararg fields: Field): ToolSchema =
        ToolSchema(
            properties = buildJsonObject {
                fields.forEach { field ->
                    putJsonObject(field.name) {
                        put("type", field.type)
                        put("description", field.description)
                    }
                }
            },
            required = fields.filter { it.mandatory }.map { it.name },
        )

    private fun value(args: Map<String, JsonElement>?, key: String): String? =
        args?.get(key)?.jsonPrimitive?.content

    private fun text(message: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(message)))

    private fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private fun path(value: String?): String? =
        value?.takeIf { it.startsWith("/") && !it.contains("\u0000") && it.length < 2048 }

    private fun pkg(value: String?): String? =
        value?.takeIf { it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+")) }

    private fun flag(value: String?): Boolean = value == "true"

    private fun commandOutput(result: ADB.ExecResult): String = result.formatted()

    fun registerTools() {
        registerShell()
        registerFiles()
        registerPackages()
        registerProcesses()
        registerSystemTools()
    }

    private fun registerShell() {
        server.addTool(
            name = "run_shell_command",
            description = "ADVANCED: Execute a direct ADB shell command on THIS TV (Android shell-user permissions, not root). Authorized administrator only. Use for jobs that cannot be done via structured tools. Do not change system partitions or delete user data without explicit confirmation.",
            inputSchema = schema(
                Field("command", "Android shell command (sh syntax). WARNING: can alter apps, files, and device settings."),
                Field("timeout_ms", "Maximum execution time (1000-180000 ms, default 15000)", "integer", false),
            ),
        ) { request ->
            val cmd = value(request.arguments, "command")
                ?: return@addTool text("Missing 'command'")
            if (cmd.length > 8192 || cmd.isBlank())
                return@addTool text("Command must be 1-8192 characters.")
            val timeout = value(request.arguments, "timeout_ms")?.toLongOrNull()
                ?.coerceIn(1000, 180000) ?: 15000L
            text(commandOutput(adb.shellCommand(cmd, timeout)))
        }
    }

    private fun registerFiles() {
        server.addTool(
            name = "list_files",
            description = "List files and subfolders at an absolute path on the TV. For recursive inspection, set recursive=true; output is capped.",
            inputSchema = schema(
                Field("path", "Absolute folder path e.g. /sdcard/Download", mandatory = false),
                Field("recursive", "Whether to find files recursively, maxdepth 5", "boolean", false),
            ),
        ) { request ->
            val dir = path(value(request.arguments, "path") ?: "/sdcard")
                ?: return@addTool text("Invalid absolute path")
            val cmd = if (flag(value(request.arguments, "recursive")))
                "find ${quote(dir)} -maxdepth 5 -print | head -n 500"
            else
                "ls -la ${quote(dir)}"
            text(commandOutput(adb.shellCommand(cmd)))
        }

        server.addTool(
            name = "read_file_text",
            description = "Read up to 65536 bytes from a text file on the TV. For binary data, use read_file_base64.",
            inputSchema = schema(
                Field("path", "Absolute text file path"),
                Field("max_bytes", "Bytes to read, 1-65536 (default 32768)", "integer", false),
            ),
        ) { request ->
            val target = path(value(request.arguments, "path"))
                ?: return@addTool text("Invalid absolute path")
            val bytes = value(request.arguments, "max_bytes")?.toIntOrNull()
                ?.coerceIn(1, 65536) ?: 32768
            text(commandOutput(adb.shellCommand("head -c $bytes ${quote(target)}")))
        }

        server.addTool(
            name = "write_file_text",
            description = "Create or replace a TV file with UTF-8 text via ADB push. Parent directory must exist. Does not write outside shell-accessible paths.",
            inputSchema = schema(
                Field("path", "Absolute destination path, e.g. /sdcard/Download/config.txt"),
                Field("content", "The complete new UTF-8 file content (max 256 KiB)"),
            ),
        ) { request ->
            val target = path(value(request.arguments, "path"))
                ?: return@addTool text("Invalid absolute path")
            val content = value(request.arguments, "content")
                ?: return@addTool text("Missing 'content'")
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (bytes.size > 262144) return@addTool text("File too large (max 256 KiB)")
            text(pushBytes(target, bytes))
        }

        server.addTool(
            name = "read_file_base64",
            description = "Read a binary file as base64, up to 1 MiB. Uses ADB pull; only shell-accessible files.",
            inputSchema = schema(Field("path", "Absolute path to an existing file")),
        ) { request ->
            val target = path(value(request.arguments, "path"))
                ?: return@addTool text("Invalid absolute path")
            val size = adb.shellCommand("wc -c < ${quote(target)}")
            if (size.exitCode != 0) return@addTool text(commandOutput(size))
            val length = size.output.trim().toLongOrNull()
                ?: return@addTool text("Unable to determine file size")
            if (length > 1024 * 1024) return@addTool text("File exceeds 1 MiB limit")
            val local = File.createTempFile("mcp-read-", ".bin", context.cacheDir)
            try {
                val pulled = adb.execAdb(listOf("pull", target, local.absolutePath), 60000)
                if (pulled.exitCode != 0) return@addTool text(commandOutput(pulled))
                text("base64=${Base64.getEncoder().encodeToString(local.readBytes())}")
            } finally {
                local.delete()
            }
        }

        server.addTool(
            name = "write_file_base64",
            description = "Create or replace a binary file from base64 (up to 2 MiB). ADB shell permissions apply.",
            inputSchema = schema(
                Field("path", "Absolute destination file path"),
                Field("content_base64", "Standard base64 bytes (max 2 MiB decoded)"),
            ),
        ) { request ->
            val target = path(value(request.arguments, "path"))
                ?: return@addTool text("Invalid absolute path")
            val encoded = value(request.arguments, "content_base64")
                ?: return@addTool text("Missing content_base64")
            if (encoded.length > 2800000) return@addTool text("Payload exceeds 2 MiB")
            val bytes = try { Base64.getDecoder().decode(encoded) }
                catch (_: IllegalArgumentException) { return@addTool text("Invalid base64") }
            if (bytes.size > 2097152) return@addTool text("Decoded file exceeds 2 MiB")
            text(pushBytes(target, bytes))
        }

        server.addTool(
            name = "make_directory",
            description = "Create a directory and parents on the TV using mkdir -p.",
            inputSchema = schema(Field("path", "Absolute directory path")),
        ) { request ->
            val target = path(value(request.arguments, "path"))
                ?: return@addTool text("Invalid absolute path")
            text(commandOutput(adb.shellCommand("mkdir -p ${quote(target)}")))
        }

        server.addTool(
            name = "move_or_copy_file",
            description = "Move or copy a TV file/directory directly. For directories use copy operation with cp -R.",
            inputSchema = schema(
                Field("source", "Absolute source path"),
                Field("destination", "Absolute destination path"),
                Field("operation", "Either 'move' or 'copy'"),
            ),
        ) { request ->
            val from = path(value(request.arguments, "source"))
                ?: return@addTool text("Invalid source path")
            val to = path(value(request.arguments, "destination"))
                ?: return@addTool text("Invalid destination path")
            val op = value(request.arguments, "operation")
            val action = when(op) {
                "move" -> "mv"
                "copy" -> "cp -R"
                else -> return@addTool text("operation must be move or copy")
            }
            text(commandOutput(adb.shellCommand("$action ${quote(from)} ${quote(to)}")))
        }

        server.addTool(
            name = "delete_file_or_directory",
            description = "Permanently delete a file or directory. REQUIRES confirm=true. Do not use for system locations or without user's explicit approval.",
            inputSchema = schema(
                Field("path", "Absolute target file or directory"),
                Field("recursive", "Set true to remove a directory tree", "boolean", false),
                Field("confirm", "Must be true, after explicit user confirmation", "boolean"),
            ),
        ) { request ->
            val target = path(value(request.arguments, "path"))
                ?: return@addTool text("Invalid absolute path")
            if (!flag(value(request.arguments, "confirm")))
                return@addTool text("Deletion refused: confirm=true required")
            val forbidden = setOf("/", "/sdcard", "/storage", "/data", "/system", "/vendor", "/product", "/dev", "/proc")
            if (target.trimEnd('/') in forbidden || target.split('/').filter { it.isNotBlank() }.size < 2)
                return@addTool text("Refusing deletion of a top-level/system directory")
            val opt = if (flag(value(request.arguments, "recursive"))) "-rf" else "-f"
            text(commandOutput(adb.shellCommand("rm $opt ${quote(target)}")))
        }
    }

    private fun pushBytes(target: String, bytes: ByteArray): String {
        val local = File.createTempFile("mcp-write-", ".bin", context.cacheDir)
        return try {
            local.writeBytes(bytes)
            commandOutput(adb.execAdb(listOf("push", local.absolutePath, target), 60000))
        } finally {
            local.delete()
        }
    }

    private fun registerPackages() {
        server.addTool(
            name = "list_installed_packages",
            description = "Enumerate all/third-party Android packages directly with pm (including paths).",
            inputSchema = schema(
                Field("third_party_only", "Return only installed third-party packages", "boolean", false),
            ),
        ) { request ->
            val opt = if (flag(value(request.arguments, "third_party_only"))) "-3" else ""
            text(commandOutput(adb.shellCommand("pm list packages -f $opt")))
        }

        server.addTool(
            name = "get_package_details",
            description = "Inspect installed app state, permissions and activities using dumpsys package.",
            inputSchema = schema(Field("package_name", "Android package ID")),
        ) { request ->
            val name = pkg(value(request.arguments, "package_name"))
                ?: return@addTool text("Invalid package name")
            text(commandOutput(adb.shellCommand("dumpsys package ${quote(name)}", 30000)))
        }

        server.addTool(
            name = "install_apk_from_tv",
            description = "Install/reinstall an APK ALREADY present on this TV, e.g. /sdcard/Download/game.apk. Downloads locally via ADB pull then installs with adb install -r. User must authorize app installation.",
            inputSchema = schema(Field("path", "Absolute path of APK on the TV")),
        ) { request ->
            val remote = path(value(request.arguments, "path"))
                ?: return@addTool text("Invalid absolute path")
            if (!remote.endsWith(".apk", ignoreCase = true))
                return@addTool text("Only .apk files are supported")
            val local = File.createTempFile("mcp-install-", ".apk", context.cacheDir)
            try {
                val pull = adb.execAdb(listOf("pull", remote, local.absolutePath), 180000)
                if (pull.exitCode != 0) return@addTool text("Pull failed: ${commandOutput(pull)}")
                if (local.length() > 512L * 1024 * 1024)
                    return@addTool text("APK exceeds 512 MiB")
                text(commandOutput(adb.execAdb(listOf("install", "-r", local.absolutePath), 180000)))
            } finally {
                local.delete()
            }
        }

        server.addTool(
            name = "uninstall_package",
            description = "Uninstall a third-party app (removes app data). Requires confirm=true and explicit user approval. May fail for system apps.",
            inputSchema = schema(
                Field("package_name", "Package to uninstall"),
                Field("confirm", "Must be true after user authorization", "boolean"),
            ),
        ) { request ->
            val name = pkg(value(request.arguments, "package_name"))
                ?: return@addTool text("Invalid package name")
            if (!flag(value(request.arguments, "confirm")))
                return@addTool text("Uninstall refused: confirm=true required")
            if (name == context.packageName)
                return@addTool text("Refusing to uninstall the active MCP service")
            text(commandOutput(adb.execAdb(listOf("uninstall", name), 60000)))
        }

        server.addTool(
            name = "force_stop_package",
            description = "Stop a running app by package name without deleting its data.",
            inputSchema = schema(Field("package_name", "App package to stop")),
        ) { request ->
            val name = pkg(value(request.arguments, "package_name"))
                ?: return@addTool text("Invalid package name")
            if (name == context.packageName)
                return@addTool text("Refusing to stop MCP itself")
            text(commandOutput(adb.shellCommand("am force-stop ${quote(name)}")))
        }

        server.addTool(
            name = "launch_android_activity",
            description = "Start a specified Android component directly without using the remote-control cursor. Example com.example/.MainActivity",
            inputSchema = schema(Field("component", "package/activity component string")),
        ) { request ->
            val component = value(request.arguments, "component")
            if (component == null ||
                !component.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z_.$][A-Za-z0-9_.$]*")))
                return@addTool text("Invalid activity component")
            text(commandOutput(adb.shellCommand("am start -n ${quote(component)}")))
        }

        server.addTool(
            name = "set_default_home",
            description = "Set the TV default home launcher via Android package manager (only on supported Android versions). Requires confirm=true. This does not disable or remove stock launcher.",
            inputSchema = schema(
                Field("component", "Installed launcher package/activity component"),
                Field("confirm", "Must be true after explicit user approval", "boolean"),
            ),
        ) { request ->
            val component = value(request.arguments, "component")
            if (component == null ||
                !component.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z_.$][A-Za-z0-9_.$]*")))
                return@addTool text("Invalid activity component")
            if (!flag(value(request.arguments, "confirm")))
                return@addTool text("Change not applied: confirm=true required")
            text(commandOutput(adb.shellCommand("cmd package set-home-activity ${quote(component)}")))
        }
    }

    private fun registerProcesses() {
        server.addTool(
            name = "list_processes",
            description = "Inspect active TV processes directly with ps -A; optional case-insensitive text filter.",
            inputSchema = schema(
                Field("filter", "Optional process-name substring", mandatory = false),
            ),
        ) { request ->
            val f = value(request.arguments, "filter").orEmpty()
            if (f.length > 100) return@addTool text("Filter too long")
            val cmd = if (f.isBlank()) "ps -A" else "ps -A | grep -i -F ${quote(f)}"
            text(commandOutput(adb.shellCommand(cmd)))
        }

        server.addTool(
            name = "get_process_details",
            description = "Inspect process IDs and memory usage for one Android package.",
            inputSchema = schema(Field("package_name", "Android package name")),
        ) { request ->
            val name = pkg(value(request.arguments, "package_name"))
                ?: return@addTool text("Invalid package name")
            val ids = adb.shellCommand("pidof ${quote(name)}")
            val mem = adb.shellCommand("dumpsys meminfo ${quote(name)}", 30000)
            text("PIDs: ${ids.output.trim()}\\nMemory:\\n${commandOutput(mem)}")
        }
    }

    private fun registerSystemTools() {
        server.addTool(
            name = "get_system_diagnostics",
            description = "Direct device diagnostics: storage, memory, CPU, properties, connectivity, services, activity, or battery.",
            inputSchema = schema(Field("section", "One of: storage, memory, cpu, properties, network, activity, services, battery")),
        ) { request ->
            val cmd = when (value(request.arguments, "section")) {
                "storage" -> "df -h"
                "memory" -> "cat /proc/meminfo"
                "cpu" -> "cat /proc/cpuinfo"
                "properties" -> "getprop"
                "network" -> "ip addr; ip route"
                "activity" -> "dumpsys activity activities"
                "services" -> "dumpsys activity services"
                "battery" -> "dumpsys battery"
                else -> return@addTool text("Unknown section")
            }
            text(commandOutput(adb.shellCommand(cmd, 30000)))
        }

        server.addTool(
            name = "read_android_logs",
            description = "Get last N Android logcat lines for troubleshooting (may contain private data).",
            inputSchema = schema(Field("lines", "Line count, max 500, default 120", "integer", false)),
        ) { request ->
            val n = value(request.arguments, "lines")?.toIntOrNull()?.coerceIn(1, 500) ?: 120
            text(commandOutput(adb.shellCommand("logcat -d -t $n", 30000)))
        }
    }
}
