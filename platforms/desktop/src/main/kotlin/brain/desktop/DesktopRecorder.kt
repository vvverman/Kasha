package brain.desktop

import brain.domain.RecorderGateway
import brain.model.Capture
import brain.runtime.FileBrainStore
import brain.studio.SignalLevel
import kotlinx.coroutines.*
import java.nio.file.*
import java.util.UUID
import javax.sound.sampled.*
import kotlin.concurrent.thread

class DesktopRecorder(private val root:Path,private val store:FileBrainStore,private val enqueue:suspend(String)->Unit):RecorderGateway,AutoCloseable {
    private val pending=root.resolve("pending")
    private val consentFile=root.resolve("microphone-consent")
    @Volatile private var currentPhase="idle"
    @Volatile private var running=false
    @Volatile private var signal=0f
    private var input:TargetDataLine?=null
    private var worker:Thread?=null
    init{Files.createDirectories(pending)}
    private fun journals()=Files.list(pending).use{f->f.filter{it.fileName.toString().endsWith(".wav")}.sorted().toList()}
    override suspend fun hasConsent()=withContext(Dispatchers.IO){Files.exists(consentFile)}
    override suspend fun hasPending()=withContext(Dispatchers.IO){currentPhase=="idle"&&journals().isNotEmpty()}
    override fun phase()=currentPhase
    override fun level()=if(currentPhase=="recording")signal else 0f
    override suspend fun start():Unit=withContext(Dispatchers.IO){
        check(currentPhase=="idle"&&journals().isEmpty())
        var line:TargetDataLine?=null
        for(rate in listOf(16000,44100,48000)){
            val format=AudioFormat(rate.toFloat(),16,1,true,false)
            try{line=AudioSystem.getTargetDataLine(format);line.open(format);break}
            catch(_:LineUnavailableException){line?.close();line=null}
            catch(_:IllegalArgumentException){line?.close();line=null}
            catch(_:SecurityException){line?.close();error("audioFailed")}
        }
        val actual=line?:error("audioFailed")
        var journal:WavJournal?=null
        try{
            val writer=WavJournal(pending.resolve("${UUID.randomUUID()}.wav"),actual.format.sampleRate.toInt());journal=writer
            input=actual;running=true;actual.start();currentPhase="recording";Files.writeString(consentFile,"yes")
            worker=thread(name="Kasha-microphone",isDaemon=true){
                try{
                    val buffer=ByteArray(2048);var syncedAt=System.nanoTime()
                    while(running){
                        if(currentPhase=="paused"){Thread.sleep(25);continue}
                        val count=actual.read(buffer,0,buffer.size)
                        if(count>0&&currentPhase=="recording"){writer.append(buffer,count);signal=SignalLevel.pcm16(buffer,count)}
                        if(System.nanoTime()-syncedAt>1_000_000_000){writer.flush();syncedAt=System.nanoTime()}
                    }
                } catch(e:Exception){runCatching{Files.writeString(root.resolve("microphone-error.log"),e.javaClass.simpleName)}}
                finally{running=false;signal=0f;actual.close();runCatching{writer.close()};currentPhase="idle"}
            }
        }catch(e:Exception){running=false;currentPhase="idle";actual.close();runCatching{journal?.close()};throw e}
    }
    override suspend fun pause():Unit=withContext(Dispatchers.IO){check(currentPhase=="recording");currentPhase="paused";signal=0f;input?.stop();input?.flush()}
    override suspend fun resume():Unit=withContext(Dispatchers.IO){check(currentPhase=="paused");input?.flush();input?.start();currentPhase="recording"}
    override suspend fun stopAndUpload():Capture=withContext(Dispatchers.IO){close();recoverPending()}
    override suspend fun recoverPending():Capture=withContext(Dispatchers.IO){
        check(currentPhase=="idle")
        val source=journals().firstOrNull()?:error("No pending audio")
        val size=WavJournal.repair(source)
        if(size==0L){Files.delete(source);error("audioFailed")}
        val capture=store.createCapture("original.wav",Files.readAllBytes(source),source.fileName.toString().removeSuffix(".wav"))
        Files.delete(source);enqueue(capture.id);capture
    }
    override fun close(){
        running=false;input?.stop();input?.close();worker?.join(3000)
        check(worker?.isAlive!=true);worker=null;input=null;signal=0f;currentPhase="idle"
    }
}
