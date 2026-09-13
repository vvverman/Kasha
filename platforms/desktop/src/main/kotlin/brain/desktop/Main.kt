package brain.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import brain.studio.*
import kotlinx.coroutines.*
import java.awt.Dimension
import java.nio.file.*
import java.util.Locale
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main(args:Array<String>){
    val resources=Path.of(System.getProperty("compose.application.resources.dir")?:System.getenv("KASHA_BUNDLE_RESOURCES")?:"platforms/desktop/bundle-test/common").toAbsolutePath()
    val demo=Files.exists(resources.resolve("demo-mode.txt"))
    val name=if(demo)"Kasha Test" else "Kasha"
    System.setProperty("apple.awt.application.name",name)
    if("--install-smoke" in args){
        try{
            val env=bundledEnvironment(resources)
            check(env.values.all{Files.exists(Path.of(it))})
            println("KASHA DESKTOP INSTALL SMOKE PASSED")
            return
        }catch(e:Exception){e.printStackTrace();exitProcess(1)}
    }
    if("--self-test-demo" in args){
        try{val index=args.indexOf("--self-test-demo");runBlocking{StudioSelfTest.run(resources,Path.of(args[index+1]))};return}
        catch(e:Exception){e.printStackTrace();exitProcess(1)}
    }
    if("--self-test" in args){
        try{val index=args.indexOf("--self-test");runBlocking{SelfTest.run(resources,Path.of(args[index+1]),Path.of(args[index+2]))};return}
        catch(e:Exception){e.printStackTrace();exitProcess(1)}
    }
    val root=DesktopPlatform.dataRoot(name)
    val services=try{DesktopServices(root.toAbsolutePath(),resources)}catch(e:Exception){JOptionPane.showMessageDialog(null,e.message,name,JOptionPane.ERROR_MESSAGE);return}
    val smokeAt=args.indexOf("--ui-smoke")
    val smokeOutput=if(smokeAt>=0)Path.of(args[smokeAt+1])else null
    val screen=if(smokeAt>=0&&args.size>smokeAt+2)args[smokeAt+2]else "home"
    if(smokeOutput!=null)runBlocking{services.repository.savePreferences(Preferences(autoRecord=false,language="ru",theme=if(screen.endsWith("dark"))"dark"else"light"))}
    val state=StudioState(
        services.repository,
        services.recorder,
        services.audio,
        Locale.getDefault().toLanguageTag(),
        DesktopReminder(),
    )
    Thread.setDefaultUncaughtExceptionHandler{_,error->runCatching{Files.writeString(root.resolve("last-error.log"),error.stackTraceToString())};error.printStackTrace()}
    try{
        application{
            val scope=rememberCoroutineScope();var closing by remember{mutableStateOf(false)}
            Window(onCloseRequest={if(!closing){closing=true;scope.launch{
                try{state.flush();withContext(Dispatchers.IO){services.close()};exitApplication()}
                catch(_:Exception){state.error="saveFailed";closing=false}
            }}},title=name,state=rememberWindowState(width=430.dp,height=850.dp,position=WindowPosition(Alignment.Center)),resizable=true){
                LaunchedEffect(Unit){
                    window.minimumSize=Dimension(390,680)
                    if(smokeOutput!=null){
                        state.launch()
                        when{
                            screen.startsWith("note")-> {state.demo();repeat(80){delay(100);state.refresh();if(state.current?.status==brain.model.CaptureStatus.READY)return@repeat}}
                            screen.startsWith("settings")->state.navigate(Tab.SETTINGS)
                            screen.startsWith("language")-> {state.navigate(Tab.SETTINGS);state.languagePage=true}
                            screen.startsWith("projects")-> {state.createProject("Пароли","");state.createProject("Разработка приложения","Идеи, интерфейсы, голосовые заметки");state.navigate(Tab.PROJECTS)}
                        }
                        delay(2200);Files.createDirectories(smokeOutput)
                        val image=java.awt.Robot().createScreenCapture(java.awt.Rectangle(window.locationOnScreen,window.size))
                        javax.imageio.ImageIO.write(image,"png",smokeOutput.resolve("$screen.png").toFile())
                        Files.writeString(smokeOutput.resolve("$screen-ready.txt"),"visible=${window.isShowing}; ${window.width}x${window.height}; Java=${System.getProperty("java.home")}; simulated=${services.simulated}")
                        delay(200);state.flush();services.close();exitApplication()
                    }
                }
                StudioApp(state)
            }
        }
    }finally{runCatching{services.close()}}
}
