package brain.domain

import brain.model.Project
import brain.studio.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class StudioContractTest {
    @Test fun defaultsAreExplicit() { val p=Preferences();assertEquals(1.5,p.savedSpeed);assertEquals(32,p.bitrate);assertFalse(p.autoRoute);assertTrue(p.autoRecord) }
    @Test fun speedAndQualityCannotBeInvalid() { assertFails{Preferences(quality=-1).validated()};assertFails{Preferences(savedSpeed=Double.NaN).validated()};assertFails{Preferences(savedSpeed=3.0).validated()} }
    @Test fun allEightLanguagesResolveFromSystem() { Languages.codes.forEach{assertEquals(it,Languages.resolve("system","$it-AA"))};assertEquals("en",Languages.resolve("system","ja-JP"));assertEquals("ru",Languages.resolve("ru","en-US")) }
    @Test fun silenceProducesZeroLevel() { assertEquals(0f,SignalLevel.pcm16(ByteArray(1024)));assertEquals(0f,SignalLevel.pcm16(byteArrayOf(1))) }
    @Test fun actualSamplesProduceNonzeroLevel() { val b=ByteArray(100){if(it%2==0)0 else 32};assertTrue(SignalLevel.pcm16(b)>0.5f) }
    @Test fun mockNeverClaimsToRecognizeVoice() = runTest { val ai=DemoIntelligence(0);assertTrue(ai.simulated);assertEquals(ai.transcribe("first.wav","ru","idea"),ai.transcribe("different.wav","ru","idea")) }
    @Test fun rawExampleKeepsSlangUntilButton() = runTest { val ai=DemoIntelligence(0);val raw=ai.transcribe("unused","ru","idea");assertTrue(raw.contains("фигня"));val tidy=ai.tidy(raw,"ru");assertFalse(tidy.contains("фигня"));assertTrue(tidy.contains("нельзя"));assertTrue(tidy.contains("\n\n")) }
    @Test fun titleWithoutInstructionIsEnoughInSimulation() = runTest { val ai=DemoIntelligence(0);val scores=ai.rank("Пароль учебного аккаунта",listOf(Project("p","Пароли"),Project("f","Кулинария")),"ru");assertTrue(scores.getValue("p")>scores.getValue("f")) }
    @Test fun allDemoExamplesExistInAllLanguages() = runTest { val ai=DemoIntelligence(0);for(lang in Languages.codes)for(example in listOf("idea","password","cooking"))assertTrue(ai.transcribe("unused",lang,example).length>20) }
}
