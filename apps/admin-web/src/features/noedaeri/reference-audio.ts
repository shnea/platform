export function validateReferenceDuration(seconds:number) {
 if(!Number.isFinite(seconds)||seconds<=0)throw new Error('참조 음성의 길이를 확인할 수 없습니다.');
 if(seconds<=3)throw new Error('3초 이하의 참조 음성은 등록할 수 없습니다. 3초를 초과하는 음성을 선택해 주세요.');
 if(seconds>30)throw new Error('참조 음성은 30초 이하여야 합니다.');
}

export async function referenceAudioDuration(file:File,signal:AbortSignal):Promise<number|null> {
 if(signal.aborted)throw new DOMException('음성 길이 확인을 취소했습니다.','AbortError');
 const audio=document.createElement('audio'),url=URL.createObjectURL(file);
 return new Promise((resolve,reject)=>{
  let settled=false;
  function finish(seconds:number|null,error?:Error) {
   if(settled)return;settled=true;clearTimeout(timer);signal.removeEventListener('abort',abort);
   audio.onloadedmetadata=null;audio.onerror=null;audio.removeAttribute('src');
   try{audio.load();}catch{}
   URL.revokeObjectURL(url);if(error)reject(error);else resolve(seconds);
  }
  function abort(){finish(null,new DOMException('음성 길이 확인을 취소했습니다.','AbortError'));}
  const timer=setTimeout(()=>finish(null),5000);
  signal.addEventListener('abort',abort,{once:true});
  audio.onloadedmetadata=()=>finish(Number.isFinite(audio.duration)&&audio.duration>0?audio.duration:null);
  audio.onerror=()=>finish(null);audio.preload='metadata';audio.src=url;
  try{audio.load();}catch{finish(null);}
 });
}
