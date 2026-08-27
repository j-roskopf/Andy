package app.andy.desktop.service.voice

/**
 * Pinned whisper.cpp / model downloads for [DesktopVoiceSetupService].
 *
 * Binary version string is stored in `~/.andy/voice/state.json` so a future Andy
 * release can bump [BINARY_VERSION] and re-fetch.
 */
object VoiceArtifacts {
    /** Bump when the managed macOS runtime layout changes (libomp / libexec backends). */
    const val BINARY_VERSION = "whisper.cpp-v1.9.2-runtime2"
    const val MODEL_NAME = "ggml-base.en.bin"
    /** Hugging Face publishes a short SHA-1; we verify full SHA-256 of the file. */
    const val MODEL_SHA256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002"
    /**
     * Pinned to the commit that added this LFS blob (not `main`) so enable() stays reproducible.
     * Digest [MODEL_SHA256] is the oid of that blob.
     */
    const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/80da2d8bfee42b0e836fc3a9890373e5defc00a6/ggml-base.en.bin"
    /** ~148 MiB model; require this + 20% free before download. */
    const val MODEL_BYTES = 147_964_211L

    enum class Platform {
        MacOsArm64,
        MacOsX64,
        LinuxX64,
        WindowsX64,
    }

    data class Download(
        val url: String,
        val sha256: String,
        val headers: Map<String, String> = emptyMap(),
        val expectedBytes: Long? = null,
        val label: String,
    )

    data class BinaryPackage(
        val platform: Platform,
        /** Primary archive (Linux/Windows official, or macOS whisper-cpp bottle). */
        val primary: Download,
        /** macOS: libggml bottle (dylibs + libexec backends). */
        val secondary: Download? = null,
        /** macOS: libomp bottle (required by libggml-base / CPU backends). */
        val tertiary: Download? = null,
    )

    private val brewHeaders = mapOf(
        "Authorization" to "Bearer QQ==",
        "Accept" to "application/vnd.oci.image.layer.v1.tar+gzip",
    )

    fun detectPlatform(
        osName: String = System.getProperty("os.name").orEmpty(),
        osArch: String = System.getProperty("os.arch").orEmpty(),
    ): Platform? {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        val arm = arch == "aarch64" || arch == "arm64"
        val x64 = arch == "amd64" || arch == "x86_64" || arch == "x64"
        return when {
            os.contains("mac") || os.contains("darwin") -> when {
                arm -> Platform.MacOsArm64
                x64 -> Platform.MacOsX64
                else -> null
            }
            os.contains("win") -> if (x64 || arch.contains("amd64")) Platform.WindowsX64 else null
            os.contains("linux") -> if (x64) Platform.LinuxX64 else null
            else -> null
        }
    }

    fun binaryPackage(platform: Platform): BinaryPackage = when (platform) {
        Platform.LinuxX64 -> BinaryPackage(
            platform,
            Download(
                url = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.2/whisper-bin-ubuntu-x64.tar.gz",
                sha256 = "46811a3ecf584307480a220b9ef5ff81b7b22dc41577cbc274ce3afc61f753b1",
                expectedBytes = 9_497_583L,
                label = "whisper-cli (linux)",
            ),
        )
        Platform.WindowsX64 -> BinaryPackage(
            platform,
            Download(
                url = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.9.2/whisper-bin-x64.zip",
                sha256 = "49dcc16de826f20bd53d44f947a1ae49dfa81f86cad67a64d80820cb192d674a",
                expectedBytes = 8_194_445L,
                label = "whisper-cli (windows)",
            ),
        )
        Platform.MacOsArm64 -> BinaryPackage(
            platform,
            primary = Download(
                url = "https://ghcr.io/v2/homebrew/core/whisper-cpp/blobs/sha256:e5954e14cd822aeb32d2e6752310bf2f349f9c0ea26d6f8e2f8ab72094b083ab",
                sha256 = "e5954e14cd822aeb32d2e6752310bf2f349f9c0ea26d6f8e2f8ab72094b083ab",
                headers = brewHeaders,
                label = "whisper-cli (macOS arm64)",
            ),
            secondary = Download(
                url = "https://ghcr.io/v2/homebrew/core/ggml/blobs/sha256:dbf6fef844185ca6ac8082ce0c96a5caf7ce6f33cdd39578fffb5789a9268aac",
                sha256 = "dbf6fef844185ca6ac8082ce0c96a5caf7ce6f33cdd39578fffb5789a9268aac",
                headers = brewHeaders,
                label = "libggml (macOS arm64)",
            ),
            tertiary = Download(
                url = "https://ghcr.io/v2/homebrew/core/libomp/blobs/sha256:7460e688895afb5df8c5f22a9e0ba2bffb0e46df265afe68eac56d538cd2496f",
                sha256 = "7460e688895afb5df8c5f22a9e0ba2bffb0e46df265afe68eac56d538cd2496f",
                headers = brewHeaders,
                label = "libomp (macOS arm64)",
            ),
        )
        Platform.MacOsX64 -> BinaryPackage(
            platform,
            primary = Download(
                url = "https://ghcr.io/v2/homebrew/core/whisper-cpp/blobs/sha256:0898a0a1a1c8fefdde20b675538b28a73ca3aa762859af63a4ee172e9386821b",
                sha256 = "0898a0a1a1c8fefdde20b675538b28a73ca3aa762859af63a4ee172e9386821b",
                headers = brewHeaders,
                label = "whisper-cli (macOS x64)",
            ),
            secondary = Download(
                url = "https://ghcr.io/v2/homebrew/core/ggml/blobs/sha256:d03f84786b328d32abc9720c86bdbea29864031dc533fa2b4e083a3c7f5495e7",
                sha256 = "d03f84786b328d32abc9720c86bdbea29864031dc533fa2b4e083a3c7f5495e7",
                headers = brewHeaders,
                label = "libggml (macOS x64)",
            ),
            tertiary = Download(
                url = "https://ghcr.io/v2/homebrew/core/libomp/blobs/sha256:569a93ca1ac3c3674c56055baddd0f9697a95a32cc2f3c485da3d7c8a53711f4",
                sha256 = "569a93ca1ac3c3674c56055baddd0f9697a95a32cc2f3c485da3d7c8a53711f4",
                headers = brewHeaders,
                label = "libomp (macOS x64)",
            ),
        )
    }
}
