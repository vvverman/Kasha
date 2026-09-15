@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package brain.ios

import brain.ai.ModelArtifacts
import brain.model.Project
import brain.studio.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import platform.posix.getenv
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/** Требует подготовленные pinned-модели и iOS frameworks. Это реальный inference, не mock. */
class IosLocalModelsExecutionTest {
    @Test fun installedModelsExecuteThroughIosRouterAndNativeBindings() = runTest(timeout = 20.minutes) {
        fun required(name: String) = getenv(name)?.toKString() ?: error("Required integration fixture: $name")
        val frameworks = required("KASHA_IOS_NATIVE_FRAMEWORKS")
        val sourceModels = required("KASHA_IOS_MODEL_FIXTURES")
        val audio = required("KASHA_IOS_AUDIO_FIXTURE")
        val evidence = required("KASHA_IOS_AI_EVIDENCE")
        val root = IosPaths.directory(IosPaths.child(NSTemporaryDirectory(), "native-models-${NSUUID().UUIDString}"))
        val selection = AiSelection()
        val preferences = Preferences(ai = selection)
        val tested = ModelArtifacts.packages.filterKeys { it in setOf(selection.speechToText, selection.text) }
        val native = IosNativeModels(frameworks)
        val packages = IosModelPackages(root, tested, { selection }) { url, part ->
            val spec = tested.values.single { it.url == url }
            // Тестовые исходники уже скачаны и проверены workflow. Реальный publish/hash остаётся в менеджере.
            check(NSFileManager.defaultManager.linkItemAtPath(IosPaths.child(sourceModels, spec.fileName), part, null))
        }
        try {
            assertTrue(native.available(AiRole.SPEECH_TO_TEXT))
            assertTrue(native.available(AiRole.TEXT))
            packages.install(selection.speechToText)
            packages.install(selection.text)
            val local = IosLocalModels(packages, native)
            val router = IosRoutedIntelligence(IosOnDeviceIntelligence(), null, local) { preferences }
            assertTrue(router.capabilities(selection, "ru").all { it.executable })
            val before = iosModelSha256(audio)
            val transcript = router.transcribe(audio, "ru", "")
            assertTrue(transcript.any { it in 'А'..'я' }, transcript)
            val source = "Ирина не меняла 1200 пунктов."
            assertTrue(router.title(source, "ru").isNotBlank())
            val tidy = router.tidy(source, "ru")
            assertTrue("1200" in tidy && "не" in tidy && "Ирина" in tidy, tidy)
            val projects = listOf(Project("work", "Работа", instruction = "Рабочие встречи"))
            val ranking = router.rank(source, projects, "ru")
            assertEquals(setOf("work"), ranking.keys)
            assertTrue(ranking.getValue("work") in 0..4)
            assertEquals(before, iosModelSha256(audio))
            assertEquals(selection, preferences.ai)
            IosPaths.directory(evidence)
            IosPaths.write(IosPaths.child(evidence, "ios-local-models.json"),
                """{"passed":true,"nativeWhisper":true,"nativeQwen":true,"routerRoles":3,"sourceUnchanged":true,"networkTransport":false}""")
        } finally { IosPaths.remove(root) }
    }
}
