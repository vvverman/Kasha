package brain.desktop

import brain.domain.AudioGateway
import brain.studio.*
import brain.runtime.*
import kotlinx.coroutines.*
import java.nio.file.*
import javax.sound.sampled.*

class DesktopAudio(private val store:FileBrainStore,private val ffmpeg:String,private val root:Path,private val scope:CoroutineScope):AudioGateway {
    private val controlLock=Any()
    private var job:Job?=null
    @Volatile private var line:SourceDataLine?=null
    @Volatile private var generation=0L
    @Volatile private var phase="idle"
    @Volatile private var level=0f
    @Volatile private var offset=0.0
    @Volatile private var duration=0.0
    @Volatile private var rate=1.0

    override suspend fun playCapture(captureId:String,compact:Boolean,fromSeconds:Double,rate:Double){
        require(rate in .5..2.0&&fromSeconds.isFinite()&&fromSeconds>=0)
        val own=synchronized(controlLock){stop();phase="loading";generation}
        var path:Path?=null
        var stream:AudioInputStream?=null
        var output:SourceDataLine?=null
        var handedOff=false
        try{
            scope.ensureActive()
            val capture=store.capture(captureId)?:error("Audio unavailable")
            withContext(Dispatchers.IO){
                val target=Files.createTempFile(root,".playback-",".wav")
                path=target
                JvmCommandRunner().run(listOf(ffmpeg,"-nostdin","-v","error","-y","-ss",fromSeconds.toString(),"-i",store.resolveAudio(capture,compact).toString(),
                    "-af","atempo=$rate","-ar","16000","-ac","1","-c:a","pcm_s16le",target.toString()),300)
                ensureActive()
                if(generation!=own)return@withContext
                val input=AudioSystem.getAudioInputStream(target.toFile())
                stream=input
                val actual=AudioSystem.getSourceDataLine(input.format)
                output=actual
                actual.open(input.format)
                synchronized(controlLock){
                    // Stop may have happened during conversion or while opening the device.
                    if(generation!=own||!scope.isActive)return@withContext
                    actual.start()
                    line=actual;phase="playing"
                    this@DesktopAudio.offset=fromSeconds
                    this@DesktopAudio.duration=capture.durationSeconds
                    this@DesktopAudio.rate=rate
                    val playbackJob=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY){
                        val buffer=ByteArray(4096)
                        while(isActive&&generation==own){
                            if(phase=="paused"){delay(20);continue}
                            val count=input.read(buffer);if(count<0)break
                            level=SignalLevel.pcm16(buffer,count)
                            actual.write(buffer,0,count)
                        }
                        if(isActive&&generation==own)actual.drain()
                    }
                    job=playbackJob
                    // Also runs if the service scope is cancelled before the coroutine starts.
                    playbackJob.invokeOnCompletion{
                        runCatching{actual.close()}
                        runCatching{input.close()}
                        runCatching{Files.deleteIfExists(target)}
                        synchronized(controlLock){
                            if(generation==own){job=null;line=null;phase="idle";level=0f}
                        }
                    }
                    handedOff=true
                    playbackJob.start()
                }
            }
        }catch(e:Exception){
            synchronized(controlLock){if(generation==own)stop()}
            throw e
        }finally{
            if(!handedOff){
                runCatching{output?.close()}
                runCatching{stream?.close()}
                path?.let{runCatching{Files.deleteIfExists(it)}}
                synchronized(controlLock){if(generation==own){phase="idle";level=0f}}
            }
        }
    }

    override suspend fun pause(){synchronized(controlLock){check(phase=="playing");phase="paused";line?.stop();level=0f}}
    override suspend fun resume(){synchronized(controlLock){check(phase=="paused");line?.start();phase="playing"}}
    override fun telemetry():AudioTelemetry{
        val pos=runCatching{offset+(line?.longFramePosition?:0)/16000.0*rate}.getOrDefault(offset).coerceIn(0.0,duration.coerceAtLeast(0.0))
        return AudioTelemetry(phase,if(phase=="idle")0.0 else pos,duration,level)
    }
    override fun stop(){
        synchronized(controlLock){
            generation++
            val oldJob=job;job=null
            val oldLine=line;line=null
            phase="idle";level=0f;offset=0.0
            oldJob?.cancel()
            try{oldLine?.stop()}finally{oldLine?.close()}
        }
    }
}
