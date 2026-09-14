package brain.desktop

import brain.model.*
import brain.studio.*
import brain.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.*

object StudioSelfTest {
    suspend fun run(resources:Path,output:Path){
        Files.createDirectories(output)
        check(Files.exists(resources.resolve("demo-mode.txt")))
        check(!Files.exists(resources.resolve("models")))
        check(!Files.exists(resources.resolve("bin/whisper-cli"))&&!Files.exists(resources.resolve("bin/llama-completion")))
        val services=DesktopServices(output.resolve("data"),resources)
        try{
            val repo=services.repository
            repo.savePreferences(Preferences(autoRecord=false,language="ru",theme="light"))
            StudioState(repo,services.recorder,services.audio,"ru-RU").launch()
            val starter=repo.snapshot().projects.single()
            check(starter.title=="Твой первый проект" && starter.instruction.isEmpty())
            StudioState(repo,services.recorder,services.audio,"en-US").launch()
            check(repo.snapshot().projects==listOf(starter))
            val work=repo.createProject(ProjectDraft("Приложение"))
            val passwords=repo.createProject(ProjectDraft("Пароли"))
            val food=repo.createProject(ProjectDraft("Кулинария"))
            val pin=repo.createProject(ProjectDraft("Закреплённый"));repo.pinProject(pin.id,true)
            suspend fun ready(id:String):Capture=withTimeout(15000){
                while(true){val c=services.store.capture(id)!!;if(!c.status.isWorking){check(c.status==CaptureStatus.READY){c.message};return@withTimeout c};delay(100)}
                error("unreachable")
            }
            val first=ready(repo.createDemo().id)
            check(first.simulated&&!first.llmApplied&&first.transcript.contains("фигня"))
            check(first.textToSave==first.transcript)
            check(first.audioFinalized&&first.savedSpeed==1.5&&first.durationSeconds in .5..2.2)
            check(first.waveform.any{it>0f})
            val dir=services.store.resolveAudio(first).parent
            check(Files.list(dir).use{it.map{f->f.fileName.toString()}.toList()}==listOf("saved.m4a"))
            val tidy=repo.tidy(first.id);check(tidy.llmApplied&&!tidy.textToSave.contains("фигня"));check(tidy.textToSave.contains("нельзя"))
            val note=repo.distribute(first.id,DistributionRequest(work.id))
            check(repo.distribute(first.id,DistributionRequest(work.id)).id==note.id)
            repo.savePreferences(Preferences(autoRecord=true,autoRoute=true,quality=3,savedSpeed=1.0,language="ru",theme="dark",demoExample="password"))
            val second=ready(repo.createDemo().id)
            check((second.relevance[passwords.id]?:0)>(second.relevance[food.id]?:0))
            val ordered=brain.domain.ProjectOrder.sorted(repo.snapshot().projects,second.relevance)
            check(ordered.first().id==pin.id)
            val appended=repo.distribute(second.id,DistributionRequest(work.id,note.id))
            check(appended.body==note.body+"\n\n"+second.textToSave)
            check(repo.snapshot().captures.count{it.noteId==note.id}==2)
            val third=ready(repo.createDemo().id);val thirdDir=services.store.resolveAudio(third).parent
            repo.discard(third.id);check(!Files.exists(thirdDir));check(repo.snapshot().captures.none{it.noteId==null})
            val prefs=repo.preferences();check(prefs.autoRoute&&prefs.quality==3&&prefs.savedSpeed==1.0&&prefs.theme=="dark")
            val report=buildJsonObject{
                put("passed",true);put("simulatedAI",true);put("bundledModels",false);put("javaHome",System.getProperty("java.home"))
                put("realAudioProcessing",true);put("savedSpeed",first.savedSpeed);put("savedSeconds",first.durationSeconds)
                put("originalDeletedAfterVerification",true);put("titleOnlyRanking",true);put("appendPreservesOldText",true)
                put("discardDeletesAudio",true);put("preferencesPersisted",true);put("transcript",first.transcript);put("tidiedText",tidy.textToSave)
                put("physicalMicrophoneTested",false);put("starterProjectCreatedOnce",true)
            }
            Files.writeString(output.resolve("self-test.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),report))
        }finally{services.close()}
        DesktopServices(output.resolve("data"),resources).use{reopened->
            check(reopened.repository.preferences().theme=="dark");check(reopened.repository.snapshot().notes.size==1)
        }
        println("STUDIO BUNDLED SELF TEST PASSED")
    }
}
