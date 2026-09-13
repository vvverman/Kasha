package brain.desktop

import brain.domain.AudioGateway
import brain.studio.*
import brain.runtime.*
import kotlinx.coroutines.*
import java.nio.file.*
import javax.sound.sampled.*

class DesktopAudio(private val store:FileBrainStore,private val ffmpeg:String,private val root:Path,private val scope:CoroutineScope):AudioGateway {
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
        stop();val own=generation;phase="loading"
        val capture=store.capture(captureId)?:error("Audio unavailable")
        this.offset=fromSeconds;this.duration=capture.durationSeconds;this.rate=rate
        val path=withContext(Dispatchers.IO){
            val target=Files.createTempFile(root,".playback-",".wav")
            try{JvmCommandRunner().run(listOf(ffmpeg,"-nostdin","-v","error","-y","-ss",fromSeconds.toString(),"-i",store.resolveAudio(capture,compact).toString(),
                "-af","atempo=$rate","-ar","16000","-ac","1","-c:a","pcm_s16le",target.toString()),300);target}
            catch(e:Exception){Files.deleteIfExists(target);if(generation==own)phase="idle";throw e}
        }
        if(generation!=own){Files.deleteIfExists(path);return}
        val stream=withContext(Dispatchers.IO){AudioSystem.getAudioInputStream(path.toFile())}
        val output=try{withContext(Dispatchers.IO){AudioSystem.getSourceDataLine(stream.format).also{it.open(stream.format);it.start()}}}
            catch(e:Exception){stream.close();Files.deleteIfExists(path);phase="idle";throw e}
        line=output;phase="playing"
        job=scope.launch(Dispatchers.IO){
            try{
                stream.use{audio->
                    val buffer=ByteArray(4096)
                    while(isActive&&generation==own){
                        if(phase=="paused"){delay(20);continue}
                        val count=audio.read(buffer);if(count<0)break
                        level=SignalLevel.pcm16(buffer,count);output.write(buffer,0,count)
                    }
                    if(isActive&&generation==own)output.drain()
                }
            }finally{output.close();if(generation==own){line=null;phase="idle";level=0f};Files.deleteIfExists(path)}
        }
    }
    override suspend fun pause(){check(phase=="playing");phase="paused";line?.stop();level=0f}
    override suspend fun resume(){check(phase=="paused");line?.start();phase="playing"}
    override fun telemetry():AudioTelemetry{
        val pos=runCatching{offset+(line?.longFramePosition?:0)/16000.0*rate}.getOrDefault(offset).coerceIn(0.0,duration.coerceAtLeast(0.0))
        return AudioTelemetry(phase,if(phase=="idle")0.0 else pos,duration,level)
    }
    override fun stop(){generation++;job?.cancel();job=null;line?.stop();line?.close();line=null;phase="idle";level=0f;offset=0.0}
}
