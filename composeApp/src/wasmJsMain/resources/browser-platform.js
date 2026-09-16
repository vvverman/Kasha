/* Только платформенный адаптер: микрофон, журнал IndexedDB, HTMLAudio. UI и правила общие с macOS. */
(() => {
  'use strict';
  let recorder=null,stream=null,player=null,playerLoading=false,writes=Promise.resolve(),stopped=null,context=null,analyser=null;
  let generation=0,openPromise,activeSessionId=null,recordingOperation=null,lastCancelledId=null,cancelAudioLoad=null;
  const db=()=>{
    if(openPromise)return openPromise;
    const opening=new Promise((resolve,reject)=>{
      const r=indexedDB.open('kasha-audio-v1',1);
      let abandoned=false;
      const fail=error=>{abandoned=true;reject(error||Error('Audio storage unavailable'));};
      r.onupgradeneeded=()=>{r.result.createObjectStore('sessions',{keyPath:'id'});r.result.createObjectStore('chunks',{keyPath:['id','index']});};
      r.onerror=()=>fail(r.error);
      r.onblocked=()=>fail(Error('Audio storage blocked by another tab'));
      r.onsuccess=()=>{if(abandoned)r.result.close();else resolve(r.result);};
    });
    const retryable=opening.then(database=>{
      const invalidate=()=>{if(openPromise===retryable)openPromise=null;};
      database.onclose=invalidate;
      database.onversionchange=()=>{invalidate();database.close();};
      return database;
    }).catch(error=>{
      // Cache only a live connection: Retry must not reuse a rejected open promise.
      if(openPromise===retryable)openPromise=null;
      throw error;
    });
    openPromise=retryable;
    return retryable;
  };
  const all=async store=>{
    const database=await db();
    return new Promise((resolve,reject)=>{
      const tx=database.transaction(store),r=tx.objectStore(store).getAll();
      // A successful request can still belong to an aborted transaction.
      // Never reconcile/delete a journal using such an incomplete read.
      r.onerror=()=>reject(r.error||Error('Audio read failed'));
      tx.oncomplete=()=>resolve(r.result);
      tx.onerror=()=>reject(tx.error||Error('Audio read failed'));
      tx.onabort=()=>reject(tx.error||Error('Audio read aborted'));
    });
  };
  const put=async(store,value)=>{
    const database=await db();
    return new Promise((resolve,reject)=>{const tx=database.transaction(store,'readwrite');tx.objectStore(store).put(value);tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error||Error('Audio persistence failed'));});
  };
  const failure=e=>'ERROR:'+(e?.message||String(e));
  const release=()=>{stream?.getTracks().forEach(t=>t.stop());stream=null;analyser=null;context?.close().catch(()=>{});context=null;};
  const removeSession=async id=>{
    if(!id)return;
    await writes.catch(()=>{});
    const database=await db();
    const chunks=(await all('chunks')).filter(x=>x.id===id);
    await new Promise((resolve,reject)=>{const tx=database.transaction(['sessions','chunks'],'readwrite');tx.objectStore('sessions').delete(id);chunks.forEach(x=>tx.objectStore('chunks').delete([x.id,x.index]));tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);tx.onabort=()=>reject(tx.error||Error('Audio cleanup failed'));});
  };
  const pendingSessions=async()=>{
    const protectedId=activeSessionId;
    const result=[];
    const sessions=(await all('sessions')).sort((a,b)=>a.created-b.created);
    const chunks=await all('chunks');
    for(const saved of sessions){
      // A scan started before getUserMedia/IndexedDB completed must not delete the live journal.
      if(saved.id===protectedId||saved.id===activeSessionId)continue;
      if(chunks.some(x=>x.id===saved.id&&x.blob?.size>0))result.push(saved);
      else await removeSession(saved.id);
    }
    return result;
  };
  const pendingSession=async()=>(await pendingSessions())[0]||null;
  const upload=async(base,pendingId)=>{
    await writes;
    const sessions=await pendingSessions();
    const saved=pendingId?sessions.find(s=>s.id===pendingId):(sessions.length===1?sessions[0]:null);
    if(!saved)throw Error('Expected one matching pending recording');
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
  const releaseAudio=own=>{
    if(!own)return;
    own.onloadedmetadata=null;own.onerror=null;
    try{own.pause();}catch{}
    // Сбрасываем загрузку и ожидающие play-promises, а не только ссылку адаптера.
    try{own.removeAttribute('src');own.load();}catch{}
  };
  const stopAudio=()=>{
    generation++;
    const own=player;player=null;playerLoading=false;
    const cancel=cancelAudioLoad;cancelAudioLoad=null;cancel?.();
    releaseAudio(own);
  };
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
    pendingRecordings:async()=>{try{return JSON.stringify((await pendingSessions()).map(s=>({id:s.id,createdAt:s.created})));}catch(e){return failure(e);}},
    sessionState:()=>{
      const phase=recorder?.state==='recording'?'RECORDING':recorder?.state==='paused'?'PAUSED':activeSessionId&&recordingOperation!=='starting'?'FINALIZING':'IDLE';
      return JSON.stringify({phase,activeSessionId:phase==='IDLE'?null:activeSessionId});
    },
    phase:()=>JSON.parse(globalThis.kashaPlatform.sessionState()).phase.toLowerCase(),
    level:()=>{
      if(!analyser||recorder?.state!=='recording')return 0;
      const values=new Float32Array(analyser.fftSize);analyser.getFloatTimeDomainData(values);
      let sum=0;for(const value of values)sum+=value*value;
      return Math.max(0,Math.min(1,Math.sqrt(sum/values.length)*5));
    },
    start:async()=>{
      if(recordingOperation)return failure(Error('Recording operation in progress'));
      if(recorder&&recorder.state!=='inactive')return 'ok';
      recordingOperation='starting';
      let sessionId=null;
      try{
        if(await pendingSession())throw Error('Recover pending audio first');
        if(player&&!player.ended)throw Error('Stop playback first');
        if(!navigator.mediaDevices?.getUserMedia||!globalThis.MediaRecorder)throw Error('Microphone unavailable');
        stream=await navigator.mediaDevices.getUserMedia({audio:true});
        const mime=['audio/webm;codecs=opus','audio/mp4','audio/ogg;codecs=opus'].find(t=>MediaRecorder.isTypeSupported(t));
        const own=new MediaRecorder(stream,mime?{mimeType:mime}:undefined);recorder=own;
        const id=crypto.randomUUID();sessionId=id;activeSessionId=id;let index=0,total=0,persistError=null;
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
          own.onstop=async()=>{
            release();await writes;
            if(recorder===own){recorder=null;activeSessionId=null;}
            persistError?reject(persistError):resolve();
          };
          own.onerror=event=>{
            persistError=event?.error||Error('Recording failed');
            if(own.state!=='inactive'){
              try{own.stop();}catch{release();reject(persistError);}
            }else{release();reject(persistError);}
          };
        });stopped.catch(()=>{});own.start(500);
        try{localStorage.setItem('kasha.mic-consent','yes');}catch{}
        return 'ok';
      }catch(e){release();recorder=null;activeSessionId=null;stopped=null;if(sessionId)await removeSession(sessionId).catch(()=>{});return failure(e);}
      finally{recordingOperation=null;}
    },
    pause:()=>{try{if(recorder?.state!=='recording')throw Error('Not recording');recorder.pause();return 'ok';}catch(e){return failure(e);}},
    resume:()=>{try{if(recorder?.state!=='paused')throw Error('Not paused');recorder.resume();return 'ok';}catch(e){return failure(e);}},
    stop:async base=>{
      if(recordingOperation)return failure(Error('Recording operation in progress'));
      const id=activeSessionId;
      if(!id||!recorder||recorder.state==='inactive')return failure(Error('Not recording'));
      recordingOperation='finalizing';
      try{
        recorder.stop();
        await stopped;
        recorder=null;activeSessionId=null;stopped=null;
        return await upload(base,id);
      }catch(e){return failure(e);}
      finally{recordingOperation=null;}
    },
    recover:async(base,id)=>{
      if(recordingOperation||activeSessionId)return failure(Error('Recording operation in progress'));
      recordingOperation='recovering';
      try{return await upload(base,id);}catch(e){return failure(e);}
      finally{recordingOperation=null;}
    },
    cancel:async id=>{
      if(recordingOperation)return failure(Error('Recording operation in progress'));
      if(!activeSessionId&&lastCancelledId===id)return 'ok';
      if(!id||id!==activeSessionId||!recorder||recorder.state==='inactive')return failure(Error('Recording changed'));
      recordingOperation='cancelling';
      try{
        recorder.stop();
        // Deletion was explicit: a failed chunk write must not prevent deleting this journal.
        await stopped.catch(()=>{});
        await removeSession(id);
        recorder=null;activeSessionId=null;stopped=null;lastCancelledId=id;
        return 'ok';
      }catch(e){return failure(e);}
      finally{recordingOperation=null;}
    },
    discardPending:async id=>{
      if(recordingOperation||activeSessionId)return failure(Error('Recording operation in progress'));
      recordingOperation='discarding';
      try{
        if(!id||(await pendingSessions()).every(s=>s.id!==id))throw Error('Pending recording changed');
        await removeSession(id);return 'ok';
      }catch(e){return failure(e);}
      finally{recordingOperation=null;}
    },
    play:async(url,from,rate)=>{
      let own=null;
      try{
        if(activeSessionId||recordingOperation==='starting')throw Error('Stop recording first');
        stopAudio();const ownGeneration=generation;own=new Audio();player=own;playerLoading=true;
        own.preservesPitch=true;own.playbackRate=rate;
        await new Promise((resolve,reject)=>{
          let settled=false;
          const finish=error=>{
            if(settled)return;settled=true;clearTimeout(timeout);
            own.onloadedmetadata=null;
            if(cancelAudioLoad===cancel)cancelAudioLoad=null;
            error?reject(error):resolve();
          };
          const cancel=()=>finish(Error('Playback cancelled'));
          const timeout=setTimeout(()=>finish(Error('Audio load timeout')),15000);
          cancelAudioLoad=cancel;
          own.onloadedmetadata=()=>finish();
          own.onerror=()=>{
            finish(Error('Audio unavailable'));
            if(player===own)stopAudio();
          };
          own.src=url;
        });
        if(generation!==ownGeneration||player!==own)throw Error('Playback cancelled');
        own.currentTime=Math.max(0,Math.min(Number.isFinite(own.duration)?own.duration:from,from));
        await own.play();
        if(generation!==ownGeneration||player!==own)throw Error('Playback cancelled');
        playerLoading=false;return 'ok';
      }catch(e){
        // Ошибка старой загрузки не меняет состояние уже выбранного нового источника.
        if(own&&player===own)stopAudio();else releaseAudio(own);
        return failure(e);
      }
    },
    pauseAudio:()=>{player?.pause();return 'ok';},
    resumeAudio:async()=>{
      const own=player,ownGeneration=generation;
      try{
        if(activeSessionId||recordingOperation==='starting')throw Error('Stop recording first');
        if(!own||playerLoading)throw Error('Playback unavailable');
        await own.play();
        if(generation!==ownGeneration||player!==own)throw Error('Playback cancelled');
        return 'ok';
      }catch(e){
        if(own&&player===own)stopAudio();else releaseAudio(own);
        return failure(e);
      }
    },
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
