package brain.runtime

import brain.model.*
import brain.studio.*
import kotlinx.coroutines.*
import java.nio.file.*
import kotlin.test.*

class StudioProcessingTest {
    @Test fun settingsPersistAndValidate()=runBlocking<Unit>{
        val root=Files.createTempDirectory("studio-prefs")
        try{val p=Preferences(autoRecord=false,autoRoute=true,quality=3,savedSpeed=2.0,language="kk",theme="dark");PreferenceStore(root).save(p);assertEquals(p,PreferenceStore(root).read());assertFails{PreferenceStore(root).save(p.copy(savedSpeed=4.0))}}
        finally{root.toFile().deleteRecursively()}
    }
    @Test fun realAudioWithMockIntelligenceAndNoModels()=runBlocking<Unit>{
        val root=Files.createTempDirectory("studio-audio");val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try{
            val store=FileBrainStore(root,runtimeStatus={RuntimeStatus(simulated=true)},singleCurrent=true)
            val prefs=PreferenceStore(root);prefs.save(Preferences(autoRecord=false,language="ru"))
            val processor=StudioProcessor(store,prefs,DemoIntelligence(0),"ffmpeg");val repo=StudioDiskRepository(store,processor,prefs,scope)
            val c=repo.createDemo();withTimeout(15000){while(store.capture(c.id)!!.status.isWorking)delay(50)}
            val ready=store.capture(c.id)!!;assertEquals(CaptureStatus.READY,ready.status,ready.message)
            assertTrue(ready.simulated);assertFalse(ready.llmApplied);assertTrue(ready.transcript.contains("фигня"))
            assertEquals(1.5,ready.savedSpeed);assertTrue(ready.durationSeconds<2.2);assertTrue(ready.waveform.any{it>0})
            assertEquals(listOf("saved.m4a"),Files.list(store.resolveAudio(ready).parent).use{it.map{p->p.fileName.toString()}.toList()})
            val cleaned=repo.tidy(c.id);assertFalse(cleaned.textToSave.contains("фигня"));assertTrue(cleaned.textToSave.contains("нельзя"))
            val project=repo.createProject(ProjectDraft("Приложение"));repo.distribute(c.id,DistributionRequest(project.id));assertEquals(1,repo.snapshot().notes.size)
        }finally{scope.cancel();scope.coroutineContext[Job]?.join();root.toFile().deleteRecursively()}
    }
    @Test fun encoderFailurePreservesSource()=runBlocking<Unit>{
        val root=Files.createTempDirectory("studio-fail")
        try{
            val store=FileBrainStore(root){RuntimeStatus()};val input=byteArrayOf(1,2,3);val c=store.createCapture("a.wav",input)
            val p=StudioProcessor(store,PreferenceStore(root),DemoIntelligence(0),"unused",CommandRunner{_,_->error("encoder failure")})
            val failed=p.process(c.id);assertEquals(CaptureStatus.FAILED,failed.status);assertFalse(failed.audioFinalized)
            assertContentEquals(input,Files.readAllBytes(store.resolveAudio(failed)))
        }finally{root.toFile().deleteRecursively()}
    }
    @Test fun singleCurrentEnforcedByStoreNotOnlyUi()=runBlocking<Unit>{
        val root=Files.createTempDirectory("studio-single")
        try{val s=FileBrainStore(root,runtimeStatus={RuntimeStatus()},singleCurrent=true);s.createCapture("a.wav",byteArrayOf(1));assertFails{s.createCapture("b.wav",byteArrayOf(2))};assertEquals(1,s.snapshot().captures.size)}
        finally{root.toFile().deleteRecursively()}
    }
    @Test fun discardFailureRollsBackAudioMove()=runBlocking<Unit>{
        val root=Files.createTempDirectory("studio-delete")
        try{
            val s=FileBrainStore(root){RuntimeStatus()};val c=s.createCapture("a.wav",byteArrayOf(1,2));s.updateCapture(c.id){it.copy(status=CaptureStatus.READY)}
            Files.delete(root.resolve("brain.json"));Files.createDirectory(root.resolve("brain.json"));Files.writeString(root.resolve("brain.json/block"),"x")
            assertFails{s.discard(c.id)};assertNotNull(s.capture(c.id));assertTrue(Files.exists(s.resolveAudio(c)))
        }finally{root.toFile().deleteRecursively()}
    }
}
