import {createIcon,decorateAction,type IconName} from '../icons/index.js';
export type ImageViewOptions={src:string;originalUrl?:string;downloadUrl?:string;name:string;onError?:()=>void};

/** Shared, framework-neutral image viewer. Zoom state never enters the document. */
export function mountImageViewer(element:HTMLElement,options:ImageViewOptions){
 const doc=element.ownerDocument,win=doc.defaultView!;
 function url(value:string){const parsed=new URL(value,doc.baseURI);if(!['http:','https:'].includes(parsed.protocol))throw Error('이미지 주소가 올바르지 않습니다.');return parsed.href;}
 const source=url(options.src),original=url(options.originalUrl??options.src),download=options.downloadUrl?url(options.downloadUrl):null;
 const root=doc.createElement('div');root.className='shnea-image-viewer';
 const trigger=doc.createElement('button');trigger.type='button';trigger.className='siv-thumbnail';trigger.setAttribute('aria-label',`${options.name} 확대 보기`);
 const thumbnail=doc.createElement('img');thumbnail.src=source;thumbnail.alt=options.name;thumbnail.loading='lazy';thumbnail.referrerPolicy='no-referrer';trigger.append(thumbnail);
 const hint=doc.createElement('span');hint.append(createIcon(doc,'zoom-in'));hint.setAttribute('aria-hidden','true');hint.className='siv-open';trigger.append(hint);root.append(trigger);element.append(root);
 const dialog=doc.createElement('dialog');dialog.className='shnea-image-viewer siv-dialog';dialog.setAttribute('aria-label',`${options.name} 이미지 확대 보기`);
 const tools=doc.createElement('div');tools.className='siv-tools';
 const name=doc.createElement('strong');name.textContent=options.name;name.className='siv-name';
 const stage=doc.createElement('div');stage.className='siv-stage';stage.tabIndex=0;stage.setAttribute('aria-label','이미지 확대 영역. 더하기·빼기로 확대·축소, 방향키로 이동, 0으로 화면 맞춤');
 const full=doc.createElement('img');full.alt=options.name;full.draggable=false;full.referrerPolicy='no-referrer';stage.append(full);
 const state=doc.createElement('span');state.className='siv-scale';state.setAttribute('role','status');
 const error=doc.createElement('p');error.className='siv-error';error.setAttribute('role','alert');error.hidden=true;
 let loaded=false,disposed=false,scale=1,fitScale=1,x=0,y=0,fitted=true;
 const points=new Map<number,{x:number;y:number}>();
 const button=(label:string,action:()=>void,icon?:IconName)=>{const el=doc.createElement('button');el.type='button';if(icon)decorateAction(el,icon,label,true);else {el.textContent=label;el.title='실제 크기(100%)';}el.addEventListener('click',action);tools.append(el);return el;};
 const minus=button('축소',()=>zoom(scale/1.25),'zoom-out'),plus=button('확대',()=>zoom(scale*1.25),'zoom-in');tools.append(state);
 const fit=button('화면 맞춤',()=>{fitted=true;layout();},'scan'),actual=button('100%',()=>{fitted=false;scale=1;x=y=0;paint();});
 if(original){const anchor=doc.createElement('a');anchor.href=original;anchor.target='_blank';anchor.rel='noopener noreferrer';decorateAction(anchor,'external-link','원본 보기',true);tools.append(anchor);}
 if(download){const anchor=doc.createElement('a');anchor.href=download;anchor.rel='noreferrer';decorateAction(anchor,'download','다운로드',true);tools.append(anchor);}
 const close=button('닫기',()=>dialog.close(),'x');const heading=doc.createElement('div');heading.className='siv-heading';heading.append(name,close);dialog.append(heading,tools,stage,error);root.append(dialog);
 function paint(){
  if(!loaded)return;
  const maxX=Math.max(0,(full.naturalWidth*scale-stage.clientWidth)/2),maxY=Math.max(0,(full.naturalHeight*scale-stage.clientHeight)/2);
  x=Math.max(-maxX,Math.min(maxX,x));y=Math.max(-maxY,Math.min(maxY,y));full.style.transform=`translate(-50%,-50%) translate(${x}px,${y}px) scale(${scale})`;
  state.textContent=`${Math.round(scale*100)}%`;minus.disabled=scale<=fitScale;plus.disabled=scale>=8;fit.disabled=actual.disabled=false;
 }
 function layout(){if(!loaded||!dialog.open)return;fitScale=Math.min(1,stage.clientWidth/full.naturalWidth,stage.clientHeight/full.naturalHeight);if(fitted){scale=fitScale;x=y=0;}paint();}
 function zoom(next:number,atX=0,atY=0){if(!loaded)return;const value=Math.max(fitScale,Math.min(8,next));x=atX-(atX-x)*value/scale;y=atY-(atY-y)*value/scale;scale=value;fitted=false;paint();}
 function fail(){loaded=false;error.textContent='이미지를 불러오지 못했습니다. 파일이 삭제되었거나 보기 주소가 만료되었을 수 있습니다.';error.hidden=false;options.onError?.();for(const el of [minus,plus,fit,actual])el.disabled=true;}
 full.addEventListener('load',()=>{loaded=true;error.hidden=true;layout();});full.addEventListener('error',fail);thumbnail.addEventListener('error',()=>{hint.textContent='이미지를 표시하지 못했습니다. 다시 열어 확인하세요.';options.onError?.();});
 trigger.addEventListener('click',()=>{if(disposed)return;loaded=false;fitted=true;x=y=0;error.hidden=true;state.textContent='불러오는 중';for(const el of [minus,plus,fit,actual])el.disabled=true;dialog.showModal();full.src=original;if(full.complete&&full.naturalWidth){loaded=true;layout();}close.focus();});
 dialog.addEventListener('close',()=>{points.clear();full.removeAttribute('src');loaded=false;if(!disposed)trigger.focus();});
 stage.addEventListener('keydown',event=>{if(['+','=','-','0','1','ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();if(event.key==='+'||event.key==='=')zoom(scale*1.25);else if(event.key==='-')zoom(scale/1.25);else if(event.key==='0')fit.click();else if(event.key==='1')actual.click();else{x+=event.key==='ArrowLeft'?40:event.key==='ArrowRight'?-40:0;y+=event.key==='ArrowUp'?40:event.key==='ArrowDown'?-40:0;paint();}}});
 stage.addEventListener('wheel',event=>{if(event.ctrlKey||event.metaKey)return;event.preventDefault();const r=stage.getBoundingClientRect();zoom(scale*(event.deltaY<0?1.15:1/1.15),event.clientX-r.left-r.width/2,event.clientY-r.top-r.height/2);},{passive:false});
 stage.addEventListener('pointerdown',event=>{if(!loaded||event.button!==0)return;stage.focus();points.set(event.pointerId,{x:event.clientX,y:event.clientY});stage.setPointerCapture(event.pointerId);});
 stage.addEventListener('pointermove',event=>{
  const old=points.get(event.pointerId);if(!old)return;
  const before=[...points.values()];points.set(event.pointerId,{x:event.clientX,y:event.clientY});const after=[...points.values()];
  if(after.length===2){const distance=(p:{x:number;y:number}[])=>Math.hypot(p[0].x-p[1].x,p[0].y-p[1].y);const first=distance(before);if(first>0){const r=stage.getBoundingClientRect();zoom(scale*distance(after)/first,(after[0].x+after[1].x)/2-r.left-r.width/2,(after[0].y+after[1].y)/2-r.top-r.height/2);}}
  else if(after.length===1){x+=event.clientX-old.x;y+=event.clientY-old.y;paint();}
 });
 for(const type of ['pointerup','pointercancel','lostpointercapture'])stage.addEventListener(type,event=>points.delete((event as PointerEvent).pointerId));
 const observer=new win.ResizeObserver(layout);observer.observe(stage);
 return ()=>{if(disposed)return;disposed=true;observer.disconnect();points.clear();dialog.close();full.removeAttribute('src');root.remove();};
}
