/* Только платформенный адаптер: микрофон, журнал IndexedDB, HTMLAudio. UI и правила общие с macOS. */
(() => {
  'use strict';
  let recorder=null,stream=null,player=null,playerLoading=false,writes=Promise.resolve(),stopped=null,context=null,analyser=null;
  let generation=0,openPromise=null,openDatabase=null;
  const resetDb=()=>{
    try{openDatabase?.close();}catch{}
    openDatabase=null;
    openPromise=null;
  };
  const db=()=>{
    if(openPromise)return openPromise;
    openPromise=new Promise((resolve,reject)=>{
      const r=indexedDB.open('kasha-audio-v1',1);
      r.onupgradeneeded=()=>{
        if(!r.result.objectStoreNames.contains('sessions'))r.result.createObjectStore('sessions',{keyPath:'id'});
        if(!r.result.objectStoreNames.contains('chunks'))r.result.createObjectStore('chunks',{keyPath:['id','index']});
      };
      r.onsuccess=()=>{
        openDatabase=r.result;
        openDatabase.onversionchange=resetDb;
        resolve(openDatabase);
      };
      r.onerror=()=>reject(r.error||Error('Audio storage unavailable'));
      r.onblocked=()=>reject(Error('Audio storage is blocked'));
    }).catch(error=>{resetDb();throw error;});
    return openPromise;
  };
  const storageRetry=async operation=>{
    let last;
    for(let attempt=0;attempt<3;attempt++){
      try{return await operation();}
      catch(error){
        last=error;
        resetDb();
        if(attempt<2)await new Promise(resolve=>setTimeout(resolve,25*(attempt+1)));
      }
    }
    throw last;
  };
  const all=store=>storageRetry(async()=>{
    const database=await db();
    return new Promise((resolve,reject)=>{
      let tx;
      try{tx=database.transaction(store);}
      catch(error){reject(error);return;}
      const r=tx.objectStore(store).getAll();
      r.onsuccess=()=>resolve(r.result);
      r.onerror=()=>reject(r.error||Error('Audio persistence read failed'));
      tx.onabort=()=>reject(tx.error||Error('Audio persistence read aborted'));
    });
  });
  const put=(store,value)=>storageRetry(async()=>{
    const database=await db();
    return new Promise((resolve,reject)=>{
      let tx;
      try{tx=database.transaction(store,'readwrite');}
      catch(error){reject(error);return;}
      tx.objectStore(store).put(value);
      tx.oncomplete=resolve;
      tx.onerror=()=>reject(tx.error||Error('Audio persistence failed'));
      tx.onabort=()=>reject(tx.error||Error('Audio persistence failed'));
    });
  });
  const failure=e=>'ERROR:'+(e?.message||String(e));
  const release=()=>{stream?.getTracks().forEach(t=>t.stop());stream=null;analyser=null;context?.close().catch(()=>{});context=null;};
  const removeSession=async id=>{
    if(!id)return;
    await writes;
    const chunks=(await all('chunks')).filter(x=>x.id===id);
    await storageRetry(async()=>{
      const database=await db();
      return new Promise((resolve,reject)=>{
        let tx;
        try{tx=database.transaction(['sessions','chunks'],'readwrite');}
        catch(error){reject(error);return;}
        tx.objectStore('sessions').delete(id);
        chunks.forEach(x=>tx.objectStore('chunks').delete([x.id,x.index]));
        tx.oncomplete=resolve;
        tx.onerror=()=>reject(tx.error||Error('Audio cleanup failed'));
        tx.onabort=()=>reject(tx.error||Error('Audio cleanup failed'));
      });
    });
  };
  const pendingSession=async()=>{
    if(recorder&&recorder.state!=='inactive')return null;
    const sessions=(await all('sessions')).sort((a,b)=>a.created-b.created);
    const chunks=await all('chunks');
    for(const saved of sessions){
      if(chunks.some(x=>x.id===saved.id&&x.blob?.size>0))return saved;
      await removeSession(saved.id);
    }
    return null;
  };
  const upload=async base=>{
    await writes;
    const saved=await pendingSession();
    if(!saved)throw Error('No pending recording');
    const chunks=(await all('chunks')).filter(x=>x.id===saved.id&&x.blob?.size>0).sort((a,b)=>a.index-b.index);
    if(!chunks.length){await removeSession(saved.id);throw Error('No audio samples');}
    const blob=new Blob(chunks.map(x=>x.blob),{type:saved.mime});
    const form=new FormData();const ext=saved.mime.includes('mp4')?'m4a':saved.mime.includes('ogg')?'ogg':'webm';
    form.append('audio',blob,'capture.'+ext);
    const response=await fetch(base+'/api/captures/audio',{method:'POST',headers:{'X-Kasha-Client':'web','X-Capture-Id':saved.id},body:form});
    const text=await response.text();if(!response.ok)throw Error(text);
    const receipt=JSON.parse(text);if(receipt.id!==saved.id)throw Error('Invalid recording receipt');
    await removeSession(saved.id);
    return text;
  };
  const stopAudio=()=>{generation++;playerLoading=false;player?.pause();player=null;};
  globalThis.kashaPlatform={
    baseUrl:()=>location.port==='8080'?'http://127.0.0.1:8787':location.origin,
    consent:async()=>{
      try{
        if(navigator.permissions?.query){
          try{
            const status=await navigator.permissions.query({name:'microphone'});
            if(status.state==='granted'){
              try{localStorage.setItem('kasha.mic-consent','yes');}catch{}
              return true;
            }
            return false;
          }catch{}
        }
        try{return localStorage.getItem('kasha.mic-consent')==='yes';}catch{return false;}
      }catch{return false;}
    },
    pending:async()=>Boolean(await pendingSession()),
    phase:()=>recorder?.state==='recording'?'recording':recorder?.state==='paused'?'paused':'idle',
    level:()=>{
      if(!analyser||recorder?.state!=='recording')return 0;
      const values=new Float32Array(analyser.fftSize);analyser.getFloatTimeDomainData(values);
      let sum=0;for(const value of values)sum+=value*value;
      return Math.max(0,Math.min(1,Math.sqrt(sum/values.length)*5));
    },
    start:async()=>{
      let sessionId=null;
      try{
        if(recorder&&recorder.state!=='inactive')return 'ok';
        if(await pendingSession())throw Error('Recover pending audio first');
        if(player&&!player.ended)throw Error('Stop playback first');
        if(!navigator.mediaDevices?.getUserMedia||!globalThis.MediaRecorder)throw Error('Microphone unavailable');
        stream=await navigator.mediaDevices.getUserMedia({audio:true});
        const mime=['audio/webm;codecs=opus','audio/mp4','audio/ogg;codecs=opus'].find(t=>MediaRecorder.isTypeSupported(t));
        const own=new MediaRecorder(stream,mime?{mimeType:mime}:undefined);recorder=own;
        const id=crypto.randomUUID();sessionId=id;let index=0,total=0,persistError=null;
        await put('sessions',{id,mime:own.mimeType||mime||'audio/webm',created:Date.now()});
        context=new (globalThis.AudioContext||globalThis.webkitAudioContext)();
        analyser=context.createAnalyser();analyser.fftSize=1024;context.createMediaStreamSource(stream).connect(analyser);
        await context.resume().catch(()=>{});
        writes=Promise.resolve();
        own.ondataavailable=e=>{
          if(!e.data?.size)return;
          const chunk={id,index:index++,blob:e.data};writes=writes.then(()=>put('chunks',chunk)).catch(e=>{persistError=e;});
          total+=e.data.size;if(total>=60*1024*1024&&own.state!=='inactive')own.stop();
        };
        stopped=new Promise((resolve,reject)=>{
          own.onstop=async()=>{release();await writes;persistError?reject(persistError):resolve();};
          own.onerror=event=>{
            persistError=event?.error||Error('Recording failed');
            if(own.state!=='inactive'){
              try{own.stop();}catch{release();reject(persistError);}
            }else{release();reject(persistError);}
          };
        });stopped.catch(()=>{});own.start(500);
        try{localStorage.setItem('kasha.mic-consent','yes');}catch{}
        return 'ok';
      }catch(e){release();recorder=null;stopped=null;if(sessionId)await removeSession(sessionId).catch(()=>{});return failure(e);}
    },
    pause:()=>{try{if(recorder?.state!=='recording')throw Error('Not recording');recorder.pause();return 'ok';}catch(e){return failure(e);}},
    resume:()=>{try{if(recorder?.state!=='paused')throw Error('Not paused');recorder.resume();return 'ok';}catch(e){return failure(e);}},
    stop:async base=>{
      try{
        if(recorder?.state!=='inactive')recorder?.stop();
        await stopped;
        recorder=null;stopped=null;
        return await upload(base);
      }catch(e){
        if(recorder?.state==='inactive')recorder=null;
        stopped=null;
        return failure(e);
      }
    },
    recover:async base=>{try{return await upload(base);}catch(e){return failure(e);}},
    play:async(url,from,rate)=>{
      try{
        if(recorder&&recorder.state!=='inactive')throw Error('Stop recording first');
        stopAudio();const ownGeneration=generation;const own=new Audio();player=own;playerLoading=true;own.preservesPitch=true;own.playbackRate=rate;
        await new Promise((resolve,reject)=>{
          const timeout=setTimeout(()=>reject(Error('Audio load timeout')),15000);
          own.onloadedmetadata=()=>{clearTimeout(timeout);resolve();};own.onerror=()=>{clearTimeout(timeout);reject(Error('Audio unavailable'));};own.src=url;
        });
        if(generation!==ownGeneration)throw Error('Playback cancelled');
        own.currentTime=Math.max(0,Math.min(Number.isFinite(own.duration)?own.duration:from,from));await own.play();playerLoading=false;return 'ok';
      }catch(e){playerLoading=false;return failure(e);}
    },
    pauseAudio:()=>{player?.pause();return 'ok';},
    resumeAudio:async()=>{try{if(recorder&&recorder.state!=='inactive')throw Error('Stop recording first');await player?.play();return 'ok';}catch(e){return failure(e);}},
    seekAudio:seconds=>{
      try{
        if(!player||playerLoading||player.ended)throw Error('Playback unavailable');
        const duration=Number.isFinite(player.duration)?player.duration:0;
        if(!Number.isFinite(seconds)||duration<=0)throw Error('Playback is not seekable');
        player.currentTime=Math.max(0,Math.min(duration,seconds));
        return 'ok';
      }catch(e){return failure(e);}
    },
    audioState:()=>({phase:playerLoading?'loading':!player||player.ended?'idle':player.paused?'paused':'playing',position:player?.currentTime||0,duration:Number.isFinite(player?.duration)?player.duration:0,level:0}),
    stopAudio,
  };
  addEventListener('beforeunload',event=>{if(recorder&&recorder.state!=='inactive'){event.preventDefault();event.returnValue='';}});
})();
