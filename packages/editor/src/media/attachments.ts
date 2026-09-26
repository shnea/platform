import {createIcon,decorateAction} from '../icons/index.js';
import {Node,type Editor} from '@tiptap/core';
import {closeHistory} from '@tiptap/pm/history';
import {mountMediaLayout,mediaStyle,type MediaLayout} from './media-layout.js';
import {Plugin,TextSelection} from '@tiptap/pm/state';
import {mountAttachmentView,attachmentSize,type AttachmentAdapter,type AttachmentKind,type AttachmentRef} from './attachment-view.js';
export type {AttachmentAdapter,AttachmentKind,AttachmentRef,AttachmentViews} from './attachment-view.js';
type Task={file:File;ref:AttachmentRef;requestId:string;state:'queued'|'uploading'|'failed'|'done';progress:number;label:string;controller?:AbortController;listeners:Set<()=>void>};
export const Attachment=Node.create({
 name:'attachment',group:'block',atom:true,draggable:true,
 addAttributes(){return {id:{default:''},fileId:{default:''},scope:{default:''},kind:{default:'file'},name:{default:''},size:{default:0},widthPercent:{default:100},align:{default:'center'}};},
 parseHTML(){return [];},
 renderHTML({node}){return ['section',{'data-attachment-id':node.attrs.id,class:['image','video'].includes(node.attrs.kind)?'se-media-frame':'',style:['image','video'].includes(node.attrs.kind)?mediaStyle(node.attrs as MediaLayout):undefined},`${node.attrs.name} · ${attachmentSize(node.attrs.size)}`];}
});
export const MediaRow=Node.create({
 name:'mediaRow',group:'block',content:'attachment{1,3}',isolating:true,
 addAttributes(){return {id:{default:''}};},parseHTML(){return [];},
 renderHTML({node}){return ['div',{class:'se-image-row','data-image-row':node.attrs.id,'data-count':node.childCount},0];}
});
// Legacy schema is used only to validate version 2 before migration.
export const ImageRow=MediaRow.extend({name:'imageRow'});
const autoKind=(file:File):AttachmentKind=>file.type.startsWith('image/')?'image':file.type.startsWith('video/')?'video':file.type.startsWith('audio/')?'audio':'file';

export function attachmentRuntime(adapter:AttachmentAdapter|undefined,report:(message:string)=>void){
 const tasks=new Map<string,Task>(),pickers=new Set<HTMLInputElement>();let disposed=false,running=false;
 // Undo may restore an earlier pending node after a move. Keep completed metadata (never bytes)
 // for this document session so undo/redo cannot turn an uploaded file back into an orphan.
 const completed=new Map<string,AttachmentRef>();
 function selectFiles(editor:Editor,accept:string,multiple:boolean,receive:(files:File[])=>void,onClose?:()=>void){
  const doc=editor.view.dom.ownerDocument,input=doc.createElement('input'),previous=doc.activeElement as HTMLElement|null;
  input.type='file';input.accept=accept;input.multiple=multiple;input.tabIndex=-1;input.setAttribute('aria-label','첨부할 파일 선택');input.className='se-file-picker';
  input.style.cssText='position:fixed;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0;cursor:default';
  const finish=(selected:boolean)=>{if(!pickers.has(input))return;const files=selected?[...input.files??[]]:[];pickers.delete(input);input.remove();if(disposed||editor.isDestroyed)return;onClose?.();if(files.length){receive(files);editor.view.focus();}else if(previous?.isConnected&&previous.getClientRects().length)previous.focus({preventScroll:true});else editor.view.focus();};
  input.addEventListener('change',()=>finish(true));input.addEventListener('cancel',()=>finish(false));pickers.add(input);doc.body.append(input);input.click();
 }
 const notify=(task:Task)=>{for(const listener of [...task.listeners])listener();};
 const position=(editor:Editor,id:string)=>{let found:number|undefined;editor.state.doc.descendants((node,pos)=>{if(node.type.name==='attachment'&&node.attrs.id===id)found=pos;});return found;};
 async function process(editor:Editor){
  if(running||disposed||!adapter)return;running=true;
  try{for(const [id,task]of tasks){
   if(disposed)break;if(task.state!=='queued'||position(editor,id)===undefined)continue;
   task.state='uploading';task.controller=new AbortController();notify(task);
   try{
    const result=await adapter.upload(task.file,{scope:task.ref.scope,kind:task.ref.kind,requestId:task.requestId,signal:task.controller.signal,progress:(value,label)=>{task.progress=Math.max(0,Math.min(100,value));task.label=label;notify(task);}});
    if(disposed||task.controller.signal.aborted)continue;
    if(typeof result.fileId!=='string'||!result.fileId||result.fileId.length>1000||typeof result.name!=='string'||!result.name||result.name.length>1000||result.scope!==task.ref.scope||result.kind!==task.ref.kind||!Number.isSafeInteger(result.size)||result.size<0||result.size>5_000_000_000)throw Error('업로드 결과가 올바르지 않습니다.');
    const at=position(editor,id);if(at!==undefined){completed.set(id,{fileId:result.fileId,scope:result.scope,kind:result.kind,name:result.name,size:result.size});editor.view.dispatch(editor.state.tr.setNodeMarkup(at,undefined,{...editor.state.doc.nodeAt(at)!.attrs,id,...result}).setMeta('addToHistory',false));}task.state='done';tasks.delete(id);
   }catch(error){if(disposed)break;task.state='failed';task.label=task.controller.signal.aborted?'업로드가 중단되었습니다.':error instanceof Error?error.message:'업로드하지 못했습니다.';notify(task);}
  }}finally{running=false;}
 }
 function add(editor:Editor,files:File[],at?:number,kind?:AttachmentKind,rowId?:string){
  if(!adapter){report('첨부 업로드 연결이 필요합니다.');return;}
  const scope=adapter.scope();if(!scope){report('첨부를 올릴 프로젝트·환경을 먼저 선택해 주세요.');return;}
  if(files.length+tasks.size>20){report('한 번에 대기할 수 있는 첨부는 최대 20개입니다.');return;}
  if(files.some(file=>file.size>5_000_000_000)){report('파일 하나의 최대 크기는 5GB입니다.');return;}
  if(kind&&kind!=='file'&&files.some(file=>file.type&&!file.type.startsWith(`${kind}/`))){report('선택한 첨부 종류에 맞는 파일을 골라 주세요.');return;}
  if(at!==undefined){const resolved=editor.state.doc.resolve(at);for(let depth=resolved.depth;depth>0;depth--)if(resolved.node(depth).type.name==='mediaRow'){if(files.every(file=>['image','video'].includes(kind??autoKind(file))))rowId=resolved.node(depth).attrs.id;else at=resolved.after(depth);break;}}
  let rowAt:number|undefined;
  if(rowId){editor.state.doc.descendants((node,pos)=>{if(node.type.name==='mediaRow'&&node.attrs.id===rowId)rowAt=pos;});if(rowAt===undefined){report('미디어 줄이 삭제되었습니다. 본문에서 다시 추가해 주세요.');return;}const row=editor.state.doc.nodeAt(rowAt)!;if(row.childCount+files.length>3){report(`한 줄에는 이미지·영상을 합쳐 최대 3개까지 넣을 수 있습니다. 현재 ${3-row.childCount}개를 추가할 수 있습니다.`);return;}at=rowAt+row.nodeSize-1;}
  const nodes=files.map(file=>{const id=crypto.randomUUID(),ref:AttachmentRef={fileId:'',scope,kind:kind??autoKind(file),name:file.name,size:file.size};tasks.set(id,{file,ref,requestId:id,state:'queued',progress:0,label:'업로드 대기',listeners:new Set()});return {type:'attachment',attrs:{id,...ref}};});
  insert(editor,nodes,at,rowId);void process(editor);
 }
 function insert(editor:Editor,nodes:{type:string;attrs:{id:string;kind:AttachmentKind}}[],at?:number,rowId?:string){
  if(!nodes.length)return;
  const content=rowId?nodes:nodes.map(node=>['image','video'].includes(node.attrs.kind)?{type:'mediaRow',attrs:{id:crypto.randomUUID()},content:[node]}:node);
  editor.commands.insertContentAt(at??{from:editor.state.selection.from,to:editor.state.selection.to},content);
  const last=position(editor,nodes[nodes.length-1].attrs.id);
  if(last!==undefined){const resolved=editor.state.doc.resolve(last);let after=last+1;for(let depth=resolved.depth;depth>0;depth--)if(resolved.node(depth).type.name==='mediaRow'){after=resolved.after(depth);break;}const tr=editor.state.tr;if(!tr.doc.nodeAt(after)?.isTextblock)tr.insert(after,editor.schema.nodes.paragraph.create());tr.setSelection(TextSelection.near(tr.doc.resolve(after+1)));editor.view.dispatch(tr);}
 }
 const rowExtension=MediaRow.extend({addNodeView(){return ({node,editor,view})=>{
  const doc=view.dom.ownerDocument,dom=doc.createElement('div'),contentDOM=doc.createElement('div'),tools=doc.createElement('div'),addButton=doc.createElement('button');let current=node;
  dom.className='se-image-row';contentDOM.className='se-image-row-content';tools.className='se-image-row-tools';tools.contentEditable='false';addButton.type='button';decorateAction(addButton,'plus','옆에 미디어 추가',true);addButton.setAttribute('aria-label','옆에 미디어 추가');addButton.setAttribute('aria-expanded','false');const choices=doc.createElement('div');choices.className='se-media-choices';choices.hidden=true;for(const [kind,label] of [['image','이미지 추가'],['video','영상 추가']] as const){const option=doc.createElement('button');option.type='button';decorateAction(option,kind==='image'?'image':'video',label);option.addEventListener('click',()=>{choices.hidden=true;addButton.setAttribute('aria-expanded','false');if(!adapter?.scope()){report('첨부를 올릴 프로젝트·환경을 먼저 선택해 주세요.');return;}const id=current.attrs.id;selectFiles(editor,`${kind}/*`,true,files=>add(editor,files,undefined,kind,id));});choices.append(option);}tools.append(addButton,choices);dom.append(contentDOM);if(editor.isEditable)dom.append(tools);
  const refresh=()=>{dom.dataset.count=String(current.childCount);addButton.hidden=current.childCount>=3;const first=current.firstChild!,width=first.attrs.widthPercent??100,align=first.attrs.align??'center';dom.style.setProperty('--se-tools-offset',current.childCount!==1||align==='left'?'0px':`calc((100% - min(100%, max(160px, ${width}%))) / ${align==='center'?2:1})`);};refresh();
  addButton.addEventListener('click',()=>{choices.hidden=!choices.hidden;addButton.setAttribute('aria-expanded',String(!choices.hidden));if(!choices.hidden)(choices.firstElementChild as HTMLElement).focus();});tools.addEventListener('keydown',event=>{if(event.key==='Escape'){event.stopPropagation();choices.hidden=true;addButton.setAttribute('aria-expanded','false');addButton.focus();}});tools.addEventListener('focusout',event=>{if(!tools.contains(event.relatedTarget as globalThis.Node)){choices.hidden=true;addButton.setAttribute('aria-expanded','false');}});
  return {dom,contentDOM,update:next=>{if(next.type!==current.type)return false;current=next;refresh();return true;},stopEvent:event=>tools.contains(event.target as globalThis.Node),ignoreMutation:mutation=>mutation.type!=='selection'&&(mutation.target===dom||tools.contains(mutation.target))};
 };}});
 const extension=Attachment.extend({addProseMirrorPlugins(){return [new Plugin({appendTransaction:(transactions,_old,state)=>{
  if(!transactions.some(tr=>tr.docChanged)||!completed.size)return null;
  const tr=state.tr;state.doc.descendants((node,pos)=>{if(node.type.name!=='attachment'||node.attrs.fileId)return;const saved=completed.get(node.attrs.id);if(saved)tr.setNodeMarkup(pos,undefined,{...node.attrs,...saved});});
  return tr.docChanged?tr.setMeta('addToHistory',false):null;
 }})];},addNodeView(){return ({node,editor,getPos,view})=>{
  const doc=view.dom.ownerDocument,dom=doc.createElement('div');dom.className='se-attachment-node';dom.contentEditable='false';let layout:ReturnType<typeof mountMediaLayout>|undefined;let current=node,cleanup:(()=>void)|undefined,unsubscribe:(()=>void)|undefined;
  function render(){
   layout?.destroy();layout=undefined;cleanup?.();cleanup=undefined;unsubscribe?.();unsubscribe=undefined;dom.replaceChildren();const id=String(current.attrs.id),ref=current.attrs as AttachmentRef,task=tasks.get(id);const media=['image','video'].includes(ref.kind);dom.classList.toggle('se-media-frame',media);dom.style.cssText=media?mediaStyle(current.attrs as MediaLayout):'';
   const remove=()=>{task?.controller?.abort();tasks.delete(id);const at=getPos();if(typeof at!=='number')return;const resolved=editor.state.doc.resolve(at);if(resolved.parent.type.name==='mediaRow'&&resolved.parent.childCount===1)editor.commands.deleteRange({from:resolved.before(),to:resolved.after()});else editor.commands.deleteRange({from:at,to:at+current.nodeSize});};
   if(ref.fileId){cleanup=mountAttachmentView(dom,ref,adapter);if(editor.isEditable&&['image','video'].includes(ref.kind)){const removeButton=doc.createElement('button');removeButton.type='button';removeButton.className='se-media-remove';removeButton.append(createIcon(doc,'x'));removeButton.setAttribute('aria-label',`${ref.name} 본문에서 제거`);removeButton.title='본문에서 제거';removeButton.addEventListener('click',remove);dom.append(removeButton);layout=mountMediaLayout(dom,()=>current.attrs as MediaLayout,()=>{const at=getPos();if(typeof at!=='number'||!editor.isEditable||doc.defaultView!.matchMedia?.('(max-width:600px)').matches)return false;const parent=editor.state.doc.resolve(at).parent;return parent.type.name!=='mediaRow'||parent.childCount===1;},value=>{const at=getPos();if(typeof at!=='number')return;const tr=closeHistory(editor.state.tr).setNodeMarkup(at,undefined,{...current.attrs,...value});editor.view.dispatch(tr);});}return;}
   const box=doc.createElement('section');box.className='shnea-attachment sa-pending';const name=doc.createElement('strong');name.textContent=`${ref.name} · ${attachmentSize(ref.size)}`;const status=doc.createElement('p');status.setAttribute('role','status');const progress=doc.createElement('progress');progress.max=100;progress.setAttribute('aria-label',`${ref.name} 업로드 진행`);
   const retry=doc.createElement('button');retry.type='button';decorateAction(retry,task?'refresh-cw':'folder-open',task?'다시 시도':'원본 다시 선택');
   const cancel=doc.createElement('button');cancel.type='button';decorateAction(cancel,'x','첨부 취소');
   const refresh=()=>{status.textContent=task?.label??'업로드할 원본 파일을 다시 선택해 주세요.';progress.value=task?.progress??0;progress.hidden=!task||task.state==='failed';retry.hidden=!!task&&task.state!=='failed';};
   retry.addEventListener('click',()=>{if(task){task.state='queued';task.label='재시도 대기';notify(task);void process(editor);}else selectFiles(editor,'',false,files=>{const file=files[0];if(file.name!==ref.name||file.size!==ref.size){report('이름과 크기가 같은 원본 파일을 선택해 주세요.');return;}tasks.set(id,{file,ref,requestId:id,state:'queued',progress:0,label:'원본 확인 중',listeners:new Set()});render();void process(editor);});});
   cancel.addEventListener('click',remove);
   box.append(name,status,progress,retry,cancel);dom.append(box);refresh();if(task){task.listeners.add(refresh);unsubscribe=()=>task.listeners.delete(refresh);}
  }
  render();return {dom,stopEvent:event=>!['drop','dragover','dragenter'].includes(event.type),ignoreMutation:()=>true,update:next=>{if(next.type!==current.type)return false;const changed=['id','fileId','scope','kind','name','size'].some(key=>next.attrs[key]!==current.attrs[key]);current=next;if(changed)render();else {dom.style.cssText=['image','video'].includes(current.attrs.kind)?mediaStyle(current.attrs as MediaLayout):'';layout?.refresh();}return true;},destroy:()=>{layout?.destroy();cleanup?.();unsubscribe?.();}};
 };}});
 const reset=()=>{for(const task of tasks.values())task.controller?.abort();tasks.clear();completed.clear();for(const input of pickers)input.remove();pickers.clear();};
 return {extension,rowExtension,add,reset,insert,pick(editor:Editor,kind:AttachmentKind,onClose?:()=>void){selectFiles(editor,kind==='file'?'':`${kind}/*`,true,files=>add(editor,files,undefined,kind),onClose);},abortMissing(editor:Editor){for(const [id,task]of tasks)if(position(editor,id)===undefined){task.controller?.abort();tasks.delete(id);}},destroy(){disposed=true;reset();}};
}
