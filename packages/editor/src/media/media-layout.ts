import {createIcon,decorateAction} from '../icons/index.js';
export type MediaLayout={widthPercent:number;align:'left'|'center'|'right'};
export function mediaStyle(layout:MediaLayout){return `--se-media-width:${layout.widthPercent}%;margin-left:${layout.align==='left'?'0':'auto'};margin-right:${layout.align==='right'?'0':'auto'}`;}

/** Layout changes leave the live media player mounted; one drag is one history entry. */
export function mountMediaLayout(element:HTMLElement,read:()=>MediaLayout,enabled:()=>boolean,commit:(value:MediaLayout)=>void){
 const doc=element.ownerDocument,win=doc.defaultView!;
 const controls=doc.createElement('div');controls.className='se-media-layout-controls';controls.contentEditable='false';controls.setAttribute('aria-label','미디어 배치');
 const alignments=doc.createElement('div');alignments.className='se-media-align';
 for(const [align,label] of [['left','좌측'],['center','가운데'],['right','우측']] as const){const button=doc.createElement('button');button.type='button';decorateAction(button,align==='left'?'align-start-horizontal':align==='right'?'align-end-horizontal':'align-center-horizontal',`미디어 ${label} 정렬`,true);button.dataset.align=align;button.setAttribute('aria-label',`미디어 ${label} 정렬`);button.addEventListener('click',()=>{if(enabled())commit({...read(),align});});alignments.append(button);}
 const reset=doc.createElement('button');reset.type='button';decorateAction(reset,'maximize','전체 너비',true);reset.addEventListener('click',()=>{if(enabled())commit({...read(),widthPercent:100});});controls.append(alignments,reset);
 const handle=doc.createElement('button');handle.type='button';handle.className='se-media-resize';handle.contentEditable='false';handle.setAttribute('role','slider');handle.setAttribute('aria-label','미디어 너비 조절');handle.setAttribute('aria-valuemin','25');handle.setAttribute('aria-valuemax','100');handle.setAttribute('aria-orientation','horizontal');handle.title='드래그하거나 방향키로 너비 조절';
 handle.append(createIcon(doc,'move-diagonal-2'));
 element.append(controls,handle);
 let drag:{id:number;x:number;width:number;container:number;value:MediaLayout}|undefined;
 const clamp=(width:number)=>Math.max(25,Math.min(100,Math.round(width)));
 function paint(value:MediaLayout){element.style.cssText=mediaStyle(value);element.dataset.align=value.align;handle.setAttribute('aria-valuenow',String(value.widthPercent));handle.setAttribute('aria-valuetext',`${value.widthPercent}%`);controls.hidden=value.widthPercent===100;for(const button of alignments.querySelectorAll('button'))button.setAttribute('aria-pressed',String(button.dataset.align===value.align));}
 const refresh=()=>paint(read());
 function end(save:boolean){if(!drag)return;const current=drag;drag=undefined;element.classList.remove('se-media-resizing');if(handle.hasPointerCapture?.(current.id))handle.releasePointerCapture(current.id);if(save&&enabled())commit(current.value);else refresh();}
 handle.addEventListener('pointerdown',event=>{if(event.button!==0||!enabled())return;event.preventDefault();event.stopPropagation();const container=element.parentElement!.getBoundingClientRect().width;if(!container)return;handle.focus({preventScroll:true});drag={id:event.pointerId,x:event.clientX,width:element.getBoundingClientRect().width,container,value:read()};element.classList.add('se-media-resizing');handle.setPointerCapture(event.pointerId);});
 handle.addEventListener('pointermove',event=>{if(!drag||event.pointerId!==drag.id)return;const factor=drag.value.align==='center'?2:drag.value.align==='right'?-1:1;drag.value={...drag.value,widthPercent:clamp((drag.width+(event.clientX-drag.x)*factor)/drag.container*100)};paint(drag.value);});
 handle.addEventListener('pointerup',event=>{if(event.pointerId===drag?.id)end(true);});handle.addEventListener('pointercancel',()=>end(false));handle.addEventListener('lostpointercapture',()=>end(false));
 handle.addEventListener('keydown',event=>{if(event.key==='Escape'){end(false);return;}if(!enabled()||!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;event.preventDefault();event.stopPropagation();const value=read();commit({...value,widthPercent:event.key==='Home'?25:event.key==='End'?100:clamp(value.widthPercent+(event.key==='ArrowRight'?5:-5))});});
 const cancel=()=>end(false);win.addEventListener('blur',cancel);refresh();
 return {refresh,destroy(){cancel();win.removeEventListener('blur',cancel);controls.remove();handle.remove();}};
}
