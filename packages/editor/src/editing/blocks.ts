import type {Editor} from '@tiptap/core';
import type {Node as ProseMirrorNode} from '@tiptap/pm/model';
import {Plugin,PluginKey,Selection,TextSelection} from '@tiptap/pm/state';
import {Decoration,DecorationSet} from '@tiptap/pm/view';
import {closeHistory} from '@tiptap/pm/history';
import {decorateAction} from '../icons/index.js';

const names:Record<string,string>={paragraph:'문단',heading:'제목',blockquote:'인용',codeBlock:'코드',bulletList:'목록',orderedList:'번호 목록',taskList:'체크리스트',horizontalRule:'구분선',table:'표',mediaRow:'미디어 묶음',imageRow:'이미지 묶음',attachment:'첨부'};
function blocks(editor:Editor){const result:{node:ProseMirrorNode;pos:number;index:number}[]=[];editor.state.doc.forEach((node,pos,index)=>result.push({node,pos,index}));return result;}

/** Move whole top-level blocks atomically, preserving every node and attachment ID. */
export function moveBlock(editor:Editor,from:number,to:number){
 if(!editor.isEditable||!Number.isInteger(from)||!Number.isInteger(to))return false;
 const rows=blocks(editor);if(from===to||!rows[from]||!rows[to])return false;
 const source=rows[from],target=rows[to],at=to>from?target.pos+target.node.nodeSize-source.node.nodeSize:target.pos;
 const selection=editor.state.selection;
 const tr=closeHistory(editor.state.tr).delete(source.pos,source.pos+source.node.nodeSize).insert(at,source.node);
 if(selection instanceof TextSelection&&selection.from>source.pos&&selection.to<source.pos+source.node.nodeSize){
  tr.setSelection(TextSelection.create(tr.doc,at+selection.anchor-source.pos,at+selection.head-source.pos));
 }else tr.setSelection(Selection.near(tr.doc.resolve(Math.min(tr.doc.content.size,at+1))));
 editor.view.dispatch(tr.scrollIntoView());editor.view.dispatch(closeHistory(editor.state.tr));return true;
}

/** Controls live outside contenteditable; files and cross-editor drags never enter this path. */
export function mountBlockControls(editor:Editor,container:HTMLElement){
 const doc=container.ownerDocument,win=doc.defaultView!;
 const group=doc.createElement('div');group.className='se-block-controls';group.setAttribute('role','group');group.setAttribute('aria-label','블록 순서');
 const grip=doc.createElement('button'),up=doc.createElement('button'),down=doc.createElement('button'),label=doc.createElement('span');
 for(const button of [grip,up,down])button.type='button';
 decorateAction(grip,'grip-vertical','선택한 블록 위치 확인',true);grip.draggable=true;grip.classList.add('se-block-grip');
 decorateAction(up,'arrow-up','블록 위로 이동',true);decorateAction(down,'arrow-down','블록 아래로 이동',true);
 label.className='se-block-label';group.append(grip,up,down,label);container.append(group);
 const live=doc.createElement('span');live.className='se-sr-only';live.setAttribute('role','status');group.append(live);
 const line=doc.createElement('div');line.className='se-block-drop-line';line.hidden=true;container.append(line);
 let active=0,activeNode:Element|null=null,drag:{doc:ProseMirrorNode;from:number;to:number}|undefined;
 const indexAt=(pos:number)=>Math.min(editor.state.doc.childCount-1,editor.state.doc.resolve(Math.min(editor.state.doc.content.size,Math.max(0,pos))).index(0));
 const highlight=new PluginKey<number>('shnea-block-highlight');
 editor.registerPlugin(new Plugin({key:highlight,state:{init:()=>0,apply:(tr,value,_old,next)=>{
  const requested=tr.getMeta(highlight);return Math.min(next.doc.childCount-1,typeof requested==='number'?requested:tr.selectionSet||tr.docChanged?next.selection.$from.index(0):value);
 }},props:{decorations:state=>{
  const index=highlight.getState(state)??0;let at=0;for(let i=0;i<index;i++)at+=state.doc.child(i).nodeSize;
  return DecorationSet.create(state.doc,[Decoration.node(at,at+state.doc.child(index).nodeSize,{class:'se-active-block'})]);
 }}}));
 function refresh(){
  const rows=blocks(editor);active=Math.max(0,Math.min(active,rows.length-1));const row=rows[active];
  const kind=names[row.node.type.name]??'블록',name=`${kind} · ${active+1}/${rows.length}`;
  label.textContent=name;grip.setAttribute('aria-label',`${name} · 드래그하여 이동 또는 눌러 위치 확인`);grip.title=`${name} · 드래그하여 이동`;grip.dataset.tooltip=grip.title;
  up.disabled=active===0;down.disabled=active===rows.length-1;
  activeNode=editor.view.nodeDOM(row.pos) as Element|null;
 }
 const changed=({transaction}:{transaction:{selectionSet:boolean;docChanged:boolean;mapping:{map:(pos:number)=>number}}})=>{
  if(drag&&drag.doc!==editor.state.doc){drag=undefined;line.hidden=true;live.textContent='본문이 바뀌어 이동을 취소했습니다. 다시 선택해 주세요.';}
  active=highlight.getState(editor.state)??indexAt(editor.state.selection.from);
  refresh();
 };
 editor.on('transaction',changed);
 const pick=(event:Event)=>{
  const target=event.target as globalThis.Node;const row=blocks(editor).find(row=>{const node=editor.view.nodeDOM(row.pos);return node===target||node?.contains(target);});
  if(row){active=row.index;editor.view.dispatch(editor.state.tr.setMeta(highlight,active));refresh();}
 };
 editor.view.dom.addEventListener('pointerdown',pick,true);
 grip.addEventListener('click',()=>{activeNode?.scrollIntoView({block:'nearest'});});
 function step(offset:number){const to=active+offset;if(moveBlock(editor,active,to)){active=to;refresh();live.textContent=`${label.textContent} 위치로 이동했습니다.`;}}
 up.addEventListener('click',()=>step(-1));down.addEventListener('click',()=>step(1));
 grip.addEventListener('dragstart',event=>{
  if(!event.dataTransfer)return;drag={doc:editor.state.doc,from:active,to:active};event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('application/x-shnea-block','move');
  if(activeNode)event.dataTransfer.setDragImage(activeNode,0,0);
 });
 const stop=()=>{drag=undefined;line.hidden=true;};grip.addEventListener('dragend',stop);win.addEventListener('blur',stop);
 const destination=(y:number)=>{
  const rows=blocks(editor);let boundary=rows.length;
  for(const row of rows){const node=editor.view.nodeDOM(row.pos) as Element|null;if(node){const rect=node.getBoundingClientRect();if(y<rect.top+rect.height/2){boundary=row.index;break;}}}
  const anchor=rows[Math.min(boundary,rows.length-1)],element=editor.view.nodeDOM(anchor.pos) as Element|null;
  if(element){const rect=element.getBoundingClientRect();line.style.left=`${rect.left}px`;line.style.width=`${rect.width}px`;line.style.top=`${boundary===rows.length?rect.bottom:rect.top}px`;line.hidden=false;}
  return boundary>drag!.from?boundary-1:boundary;
 };
 const over=(event:DragEvent)=>{
  if(!drag)return;event.preventDefault();event.stopImmediatePropagation();if(event.dataTransfer)event.dataTransfer.dropEffect='move';drag.to=destination(event.clientY);
  // Native dragging keeps touch scrolling available; buttons are the touch/keyboard alternative.
  const root=editor.view.dom;let scroller:HTMLElement|null=root.parentElement;
  while(scroller&&scroller!==doc.body){const style=win.getComputedStyle(scroller);if(/auto|scroll/.test(style.overflowY)&&scroller.scrollHeight>scroller.clientHeight)break;scroller=scroller.parentElement;}
  const rect=scroller&&scroller!==doc.body?scroller.getBoundingClientRect():{top:0,bottom:win.innerHeight};const dy=event.clientY<rect.top+48?-28:event.clientY>rect.bottom-48?28:0;
  if(dy){if(scroller&&scroller!==doc.body)scroller.scrollTop+=dy;else win.scrollBy(0,dy);}
 };
 const drop=(event:DragEvent)=>{
  if(!drag)return;event.preventDefault();event.stopImmediatePropagation();const current=drag;const to=destination(event.clientY);stop();
  if(current.doc===editor.state.doc&&moveBlock(editor,current.from,to)){active=to;refresh();live.textContent=`${label.textContent} 위치로 이동했습니다.`;grip.focus({preventScroll:true});}
 };
 editor.view.dom.addEventListener('dragover',over,true);editor.view.dom.addEventListener('drop',drop,true);
 refresh();
 return {refresh(){stop();active=indexAt(editor.state.selection.from);refresh();},destroy(){stop();editor.off('transaction',changed);editor.unregisterPlugin(highlight);editor.view.dom.removeEventListener('pointerdown',pick,true);editor.view.dom.removeEventListener('dragover',over,true);editor.view.dom.removeEventListener('drop',drop,true);win.removeEventListener('blur',stop);group.remove();line.remove();}};
}
