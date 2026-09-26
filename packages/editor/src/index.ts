import {Editor} from '@tiptap/core';
import {EditorState,TextSelection} from '@tiptap/pm/state';
import {DOMSerializer} from '@tiptap/pm/model';
import {extensions,schema,safeLink} from './schema.js';
import {parseDocument,EditorError,type EditorDocument} from './document.js';
import {fromMarkdown} from './markdown.js';
import {attachmentRuntime,type AttachmentAdapter,type AttachmentKind,type AttachmentRef} from './attachments.js';
import {mountAttachmentView} from './attachment-view.js';
export type {AttachmentAdapter,AttachmentKind,AttachmentRef,AttachmentViews} from './attachments.js';
export {parseDocument,emptyDocument,EditorError,type EditorDocument,type EditorErrorCode} from './document.js';
export {fromMarkdown} from './markdown.js';

export type Change={document:EditorDocument;origin:'edit'|'replace'};
export type EditorCommand='paragraph'|'h1'|'h2'|'h3'|'bold'|'italic'|'underline'|'strike'|'code'|'bulletList'|'orderedList'|'taskList'|'blockquote'|'codeBlock'|'horizontalRule'|'table'|'addRow'|'deleteRow'|'addColumn'|'deleteColumn'|'deleteTable'|'indent'|'outdent'|'clear'|'link'|'unlink'|'undo'|'redo';
export type CoreOptions={element:HTMLElement;value?:unknown;editable?:boolean;label?:string;attachments?:AttachmentAdapter;onChange?:(change:Change)=>void;onError?:(error:EditorError)=>void;onStateChange?:()=>void;onKeyDown?:(event:KeyboardEvent)=>boolean;onBeforeInput?:(event:InputEvent)=>boolean;onMarkdownPaste?:(source:string)=>void};
const mounted=new WeakSet<HTMLElement>();

/** Browser-only editing engine. Slash menu and file transport are separate layers. */
export function createEditorCore(options:CoreOptions){
  if(mounted.has(options.element))throw new EditorError('EDITOR_MOUNTED','이 영역에는 이미 에디터가 있습니다. 먼저 해제해 주세요.');
  const initial=parseDocument(options.value);
  let destroyed=false,pasteMode:'markdown'|'text'='markdown';
  const ensure=()=>{if(destroyed)throw new EditorError('EDITOR_DESTROYED','이미 해제한 에디터입니다.');};
  const editable=()=>{ensure();if(!engine.isEditable)throw new EditorError('EDITOR_READ_ONLY','읽기 전용 문서는 편집할 수 없습니다.');};
  const value=():EditorDocument=>({format:'shnea-editor',version:2,content:engine.getJSON()});
  const attachments=attachmentRuntime(options.attachments,message=>options.onError?.(new EditorError('ATTACHMENT_ERROR',message)));
  const engine=new Editor({
    element:options.element,extensions:extensions(attachments.extension,attachments.rowExtension),content:initial.content,editable:options.editable??true,
    injectCSS:false,enableContentCheck:true,
    editorProps:{attributes:{role:'textbox','aria-label':options.label??'문서 본문','aria-multiline':'true'},
      handleKeyDown:(_view,event)=>event.isComposing||engine.view.composing?false:options.onKeyDown?.(event)??false,
      handleDOMEvents:{beforeinput:(_view,event)=>{
        if(event.isComposing||engine.view.composing||!options.onBeforeInput?.(event))return false;
        event.preventDefault();return true;
      }},
      handleDrop:(view,event)=>{
        if(!engine.isEditable||!event.dataTransfer?.files.length)return false;
        attachments.add(engine,[...event.dataTransfer.files],view.posAtCoords({left:event.clientX,top:event.clientY})?.pos);return true;
      },
      // HTML is intentionally ignored in this foundation. Plain text/Markdown is the declared paste source.
      handlePaste:(_view,event)=>{
        if(!engine.isEditable||!event.clipboardData)return false;
        syncSelection();
        if(event.clipboardData.files.length){attachments.add(engine,[...event.clipboardData.files]);return true;}
        const source=event.clipboardData.getData('text/markdown')||event.clipboardData.getData('text/plain');
        if(!source)return true;
        try{
          if(engine.isActive('codeBlock')||pasteMode==='text')engine.view.dispatch(engine.state.tr.insertText(source).scrollIntoView());
          else {
            const parsed=fromMarkdown(source);
            const hasMarkdown=parsed.content.content?.some(node=>node.type!=='paragraph'||node.content?.some(child=>child.type!=='text'||child.marks?.length));
            if(hasMarkdown&&options.onMarkdownPaste)options.onMarkdownPaste(source);
            else engine.commands.insertContent(parsed.content.content??[]);
          }
        }catch(error){options.onError?.(error instanceof EditorError?error:new EditorError('DOCUMENT_INVALID','붙여넣을 내용을 확인해 주세요.'));}
        return true;
      }
    },
    onUpdate:()=>{attachments.abortMissing(engine);options.onChange?.({document:value(),origin:'edit'});},
    onTransaction:()=>options.onStateChange?.(),
    onSelectionUpdate:()=>options.onStateChange?.()
  });
  // Native keyboard selection may precede ProseMirror's selectionchange notification.
  // Capture it before moving focus to a slash/paste dialog, scoped to this editor only.
  function syncSelection(){
    const dom=engine.view.dom,selection=dom.ownerDocument.getSelection();
    if(!(engine.state.selection instanceof TextSelection))return;
    if(dom.ownerDocument.activeElement!==dom||!selection?.anchorNode||!selection.focusNode||!dom.contains(selection.anchorNode)||!dom.contains(selection.focusNode))return;
    const anchor=engine.view.posAtDOM(selection.anchorNode,selection.anchorOffset),head=engine.view.posAtDOM(selection.focusNode,selection.focusOffset);
    const next=TextSelection.between(engine.state.doc.resolve(anchor),engine.state.doc.resolve(head));
    if(!next.eq(engine.state.selection))engine.view.dispatch(engine.state.tr.setSelection(next));
  }
  function command(name:EditorCommand,dry=false,payload?:string,removeSlash=false):boolean{
    if(!engine.isEditable)return false;
    let chain=dry?engine.can().chain():engine.chain();
    if(removeSlash){const range=slash();if(range)chain=chain.deleteRange({from:range.from,to:range.to});}
    switch(name){
      case 'paragraph':chain=chain.setParagraph();break;
      case 'h1':case 'h2':case 'h3':chain=chain.toggleHeading({level:Number(name[1]) as 1|2|3});break;
      case 'bold':chain=chain.toggleBold();break;case 'italic':chain=chain.toggleItalic();break;
      case 'underline':chain=chain.toggleUnderline();break;case 'strike':chain=chain.toggleStrike();break;case 'code':chain=chain.toggleCode();break;
      case 'bulletList':chain=chain.toggleBulletList();break;case 'orderedList':chain=chain.toggleOrderedList();break;case 'taskList':chain=chain.toggleTaskList();break;
      case 'blockquote':chain=chain.toggleBlockquote();break;case 'codeBlock':chain=chain.toggleCodeBlock();break;
      case 'horizontalRule':chain=chain.setHorizontalRule();break;case 'table':chain=chain.insertTable({rows:3,cols:3,withHeaderRow:true});break;
      case 'addRow':chain=chain.addRowAfter();break;case 'deleteRow':chain=chain.deleteRow();break;case 'addColumn':chain=chain.addColumnAfter();break;case 'deleteColumn':chain=chain.deleteColumn();break;case 'deleteTable':chain=chain.deleteTable();break;
      case 'indent':chain=chain.sinkListItem(engine.isActive('taskItem')?'taskItem':'listItem');break;
      case 'outdent':chain=chain.liftListItem(engine.isActive('taskItem')?'taskItem':'listItem');break;
      case 'clear':chain=chain.unsetAllMarks().clearNodes();break;
      case 'link':if(!payload||!safeLink(payload)){if(dry)return false;throw new EditorError('DOCUMENT_INVALID','http(s)·mailto·tel 형식의 링크를 입력해 주세요.');}chain=chain.extendMarkRange('link').setLink({href:payload});break;
      case 'unlink':chain=chain.extendMarkRange('link').unsetLink();break;
      case 'undo':chain=chain.undo();break;case 'redo':chain=chain.redo();break;
    }
    const applied=chain.run();if(!dry)engine.view.focus();return applied;
  }
  function slash(){
    const {$from,empty}=engine.state.selection;
    if(!empty||!engine.isEditable||engine.view.composing||$from.parent.type.name!=='paragraph'||$from.parentOffset!==$from.parent.content.size)return null;
    const query=$from.parent.textContent.match(/^\/([^\s/]{0,30})$/u);
    return query?{query:query[1],from:$from.start(),to:$from.pos}:null;
  }
  mounted.add(options.element);
  return {
    getValue(){ensure();return value();},
    setValue(input:unknown,{emitChange=false}:{emitChange?:boolean}={}){
      ensure();const next=parseDocument(input);
      attachments.reset();
      // A different host document must never be reachable through the previous document's undo history.
      engine.view.updateState(EditorState.create({schema:engine.schema,doc:engine.schema.nodeFromJSON(next.content),plugins:engine.state.plugins}));
      options.onStateChange?.();
      if(emitChange)options.onChange?.({document:value(),origin:'replace'});
    },
    insertMarkdown(source:string){editable();engine.commands.insertContent(fromMarkdown(source).content.content??[]);},
    insertText(source:string){editable();if(typeof source!=='string')throw new EditorError('DOCUMENT_INVALID','문자열이 필요합니다.');engine.view.dispatch(engine.state.tr.insertText(source));},
    undo(){editable();return engine.commands.undo();},
    redo(){editable();return engine.commands.redo();},
    run(name:EditorCommand,payload?:string,{removeSlash=false}:{removeSlash?:boolean}={}){editable();return command(name,false,payload,removeSlash);},
    can(name:EditorCommand){ensure();return command(name,true);},
    isActive(name:string){ensure();return /^h[1-3]$/.test(name)?engine.isActive('heading',{level:Number(name[1])}):engine.isActive(name);},
    getSlash(){ensure();return slash();},
    canOpenSlash(){ensure();syncSelection();const {empty,$from}=engine.state.selection;return engine.isEditable&&!engine.view.composing&&!engine.isActive('codeBlock')&&(!empty||$from.parentOffset===0||/\s$/u.test($from.parent.textBetween(0,$from.parentOffset)));},
    getMenuAnchor(){ensure();return engine.view.coordsAtPos(engine.state.selection.from);},
    pickAttachment(kind:AttachmentKind,{removeSlash=false,onClose}:{removeSlash?:boolean;onClose?:()=>void}={}){editable();if(!options.attachments?.scope()){onClose?.();options.onError?.(new EditorError('ATTACHMENT_ERROR','첨부를 올릴 프로젝트·환경을 먼저 선택해 주세요.'));return;}if(removeSlash){const range=slash();if(range)engine.commands.deleteRange(range);}attachments.pick(engine,kind,onClose);},
    setPasteMode(mode:'markdown'|'text'){ensure();pasteMode=mode;},
    focus(){ensure();engine.view.focus();},
    destroy(){if(destroyed)return;destroyed=true;attachments.destroy();engine.destroy();mounted.delete(options.element);}
  };
}

/** Read-only DOM renderer uses exactly the editing schema and never enables contenteditable. */
export function renderViewer(element:HTMLElement,input:unknown,options:{attachments?:AttachmentAdapter}={}):()=>void{
  if(mounted.has(element))throw new EditorError('EDITOR_MOUNTED','이 영역에는 이미 에디터 또는 뷰어가 있습니다.');
  const document=parseDocument(input),fragment=DOMSerializer.fromSchema(schema).serializeFragment(schema.nodeFromJSON(document.content).content,{document:element.ownerDocument});
  const wrapper=element.ownerDocument.createElement('article');wrapper.className='shnea-viewer';wrapper.setAttribute('aria-label','문서 내용');wrapper.append(fragment);
  const attachments=new Map<string,AttachmentRef>();schema.nodeFromJSON(document.content).descendants(node=>{if(node.type.name==='attachment')attachments.set(node.attrs.id,node.attrs as AttachmentRef);});
  const disposers:(()=>void)[]=[];for(const item of wrapper.querySelectorAll<HTMLElement>('[data-attachment-id]')){const ref=attachments.get(item.dataset.attachmentId!);if(ref){item.replaceChildren();item.classList.remove('shnea-attachment');disposers.push(mountAttachmentView(item,ref,options.attachments));}}
  // Retain the editing table's content width (including its action gutter) without exposing controls.
  for(const table of wrapper.querySelectorAll('table')){const frame=element.ownerDocument.createElement('div'),scroll=element.ownerDocument.createElement('div');frame.className='se-table';scroll.className='se-table-scroll';table.before(frame);frame.append(scroll);scroll.append(table);}
  for(const checkbox of wrapper.querySelectorAll<HTMLInputElement>('input[type=checkbox]')){checkbox.disabled=true;checkbox.setAttribute('aria-label',`완료 여부: ${checkbox.closest('li')?.querySelector('p')?.textContent||'빈 항목'}`);}
  element.append(wrapper);mounted.add(element);let disposed=false;
  return ()=>{if(disposed)return;disposed=true;disposers.forEach(dispose=>dispose());wrapper.remove();mounted.delete(element);};
}
