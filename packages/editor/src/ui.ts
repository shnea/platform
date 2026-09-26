import {createEditorCore,type CoreOptions,type EditorCommand,type AttachmentKind} from './index.js';

type Category='전체'|'본문'|'목록'|'글자 서식'|'표'|'첨부'|'편집';
type Action=EditorCommand|AttachmentKind;
type Item={id:Action;label:string;keyword:string;category:Category;aliases?:string};
const categories:Category[]=['전체','본문','목록','글자 서식','표','첨부','편집'];
const isAttachment=(id:Action):id is AttachmentKind=>['file','image','video','audio'].includes(id);
export const editorCommands:Item[]=[
 {id:'file',label:'파일 업로드',keyword:'file',category:'첨부'},
 {id:'image',label:'이미지 업로드',keyword:'image',category:'첨부',aliases:'사진 picture'},
 {id:'video',label:'영상 업로드',keyword:'video',category:'첨부'},
 {id:'audio',label:'오디오 업로드',keyword:'audio',category:'첨부',aliases:'음악 소리'},
 {id:'paragraph',label:'본문',keyword:'text',category:'본문',aliases:'paragraph 문단'},
 {id:'h1',label:'제목 1',keyword:'h1',category:'본문',aliases:'heading title'},
 {id:'h2',label:'제목 2',keyword:'h2',category:'본문',aliases:'heading title'},
 {id:'h3',label:'제목 3',keyword:'h3',category:'본문',aliases:'heading title'},
 {id:'blockquote',label:'인용',keyword:'quote',category:'본문'},
 {id:'codeBlock',label:'코드 블록',keyword:'codeblock',category:'본문'},
 {id:'horizontalRule',label:'구분선',keyword:'divider',category:'본문',aliases:'hr'},
 {id:'bulletList',label:'글머리 목록',keyword:'bullet',category:'목록',aliases:'list'},
 {id:'orderedList',label:'번호 목록',keyword:'number',category:'목록',aliases:'ordered list'},
 {id:'taskList',label:'체크리스트',keyword:'task',category:'목록',aliases:'todo checklist'},
 {id:'indent',label:'목록 들여쓰기',keyword:'indent',category:'목록'},
 {id:'outdent',label:'목록 내어쓰기',keyword:'outdent',category:'목록'},
 {id:'bold',label:'굵게',keyword:'bold',category:'글자 서식'},
 {id:'italic',label:'기울임',keyword:'italic',category:'글자 서식'},
 {id:'underline',label:'밑줄',keyword:'underline',category:'글자 서식'},
 {id:'strike',label:'취소선',keyword:'strike',category:'글자 서식'},
 {id:'code',label:'인라인 코드',keyword:'code',category:'글자 서식'},
 {id:'link',label:'링크',keyword:'link',category:'글자 서식'},
 {id:'unlink',label:'링크 해제',keyword:'unlink',category:'글자 서식'},
 {id:'clear',label:'서식 지우기',keyword:'clear',category:'글자 서식'},
 {id:'table',label:'표 삽입',keyword:'table',category:'표'},
 {id:'addRow',label:'표 행 추가',keyword:'row',category:'표',aliases:'table addrow'},
 {id:'deleteRow',label:'표 행 삭제',keyword:'deleterow',category:'표',aliases:'table'},
 {id:'addColumn',label:'표 열 추가',keyword:'column',category:'표',aliases:'table addcolumn'},
 {id:'deleteColumn',label:'표 열 삭제',keyword:'deletecolumn',category:'표',aliases:'table'},
 {id:'deleteTable',label:'표 삭제',keyword:'deletetable',category:'표',aliases:'table'},
 {id:'undo',label:'실행 취소',keyword:'undo',category:'편집'},
 {id:'redo',label:'다시 실행',keyword:'redo',category:'편집'}
];
type UIOptions=Omit<CoreOptions,'onKeyDown'|'onStateChange'|'onBeforeInput'|'onMarkdownPaste'>;
let instance=0;

/** One selection-preserving slash palette; no permanent formatting toolbar. */
export function mountEditor(options:UIOptions){
 const doc=options.element.ownerDocument,win=doc.defaultView!,prefix=`shnea-menu-${++instance}`;
 const root=doc.createElement('section');root.className='shnea-editor';root.setAttribute('aria-label','문서 편집기');
 if(options.element.querySelector('.shnea-editor'))throw new Error('이 영역에는 이미 에디터가 있습니다.');
 const body=doc.createElement('div');body.className='se-body';
 const menu=doc.createElement('div');menu.className='se-insert-menu';menu.hidden=true;menu.setAttribute('role','dialog');menu.setAttribute('aria-label','편집 기능');
 const header=doc.createElement('div');header.className='se-menu-header';
 const search=doc.createElement('input');search.type='text';search.placeholder='명령 검색 · /table, /굵게';search.setAttribute('aria-label','편집 기능 검색');search.setAttribute('role','combobox');search.setAttribute('aria-autocomplete','list');search.setAttribute('aria-controls',`${prefix}-list`);search.setAttribute('aria-expanded','false');search.autocomplete='off';
 const closeButton=doc.createElement('button');closeButton.type='button';closeButton.textContent='닫기';closeButton.addEventListener('click',()=>close(true));header.append(search,closeButton);
 const tabs=doc.createElement('div');tabs.className='se-menu-tabs';tabs.setAttribute('role','tablist');tabs.setAttribute('aria-label','기능 종류');
 const panel=doc.createElement('div');panel.id=`${prefix}-panel`;panel.setAttribute('role','tabpanel');
 const list=doc.createElement('div');list.id=`${prefix}-list`;list.className='se-command-list';list.setAttribute('role','listbox');list.setAttribute('aria-label','편집 명령');
 const count=doc.createElement('p');count.className='se-menu-help';count.setAttribute('role','status');
 const help=doc.createElement('p');help.className='se-menu-help';help.textContent='↑↓ 이동 · Enter 선택 · Esc 닫기 · // 문자 입력';
 const form=doc.createElement('form');form.className='se-link-form';form.hidden=true;
 const linkLabel=doc.createElement('label');linkLabel.textContent='연결할 주소';const linkInput=doc.createElement('input');linkInput.type='text';linkInput.inputMode='url';linkInput.placeholder='https://';linkInput.required=true;linkLabel.append(linkInput);
 const apply=doc.createElement('button');apply.type='submit';apply.textContent='링크 적용';form.append(linkLabel,apply);
 const paste=doc.createElement('div');paste.className='se-paste-choice';paste.hidden=true;
 const pasteTitle=doc.createElement('p');pasteTitle.textContent='Markdown 서식이 포함되어 있습니다. 어떻게 붙여넣을까요?';
 const plain=doc.createElement('button'),formatted=doc.createElement('button');plain.type=formatted.type='button';plain.textContent='원문 그대로';formatted.textContent='Markdown 서식 적용';paste.append(pasteTitle,plain,formatted);
 panel.append(list,count);menu.append(header,tabs,panel,form,paste,help);
 const message=doc.createElement('p');message.className='se-message';message.setAttribute('role','status');message.hidden=true;
 const hint=doc.createElement('p');hint.className='se-hint';hint.textContent='/ 모든 편집 기능 · /table 표 · 글자 선택 후 / 서식 · Ctrl/Cmd+Z 실행 취소';
 root.append(body,menu,hint,message);options.element.append(root);
 let core:ReturnType<typeof createEditorCore>|undefined,disposed=false,category:Category='전체',selected=0,items:Item[]=[],removeSlash=false,dismissed='',pasteSource='';
 const status=(text:string)=>{message.textContent=text;message.hidden=!text;};
 const allowed=(item:Item)=>isAttachment(item.id)||item.id==='link'||!!core?.can(item.id);
 function position(){
  if(menu.hidden||!core)return;
  let rect:{left:number;bottom:number;top:number};try{rect=core.getMenuAnchor();}catch{rect=body.getBoundingClientRect();}
  const viewport=win.visualViewport,left=viewport?.offsetLeft??0,top=viewport?.offsetTop??0,width=viewport?.width??win.innerWidth,height=viewport?.height??win.innerHeight;
  const menuWidth=Math.min(460,width-24),menuHeight=Math.min(440,height-24);
  menu.style.width=`${menuWidth}px`;menu.style.maxHeight=`${menuHeight}px`;
  menu.style.left=`${Math.max(left+12,Math.min(rect.left,left+width-menuWidth-12))}px`;
  menu.style.top=`${Math.max(top+12,Math.min(rect.bottom+8,top+height-menuHeight-12))}px`;
 }
 function close(focus=false){menu.hidden=true;pasteSource='';search.setAttribute('aria-expanded','false');search.removeAttribute('aria-activedescendant');dismissed=`/${core?.getSlash()?.query??''}`;if(focus)core?.focus();}
 function open(query='',fromDocument=false){
  if(!core||options.editable===false)return;
  removeSlash=fromDocument;category='전체';selected=0;search.value=query;form.hidden=true;paste.hidden=true;header.hidden=false;help.hidden=false;panel.hidden=false;tabs.hidden=false;menu.hidden=false;menu.setAttribute('aria-label','편집 기능');search.setAttribute('aria-expanded','true');status('');render();position();search.focus();
 }
 function run(item:Item){
  if(!core||!allowed(item))return;
  if(item.id==='link'){panel.hidden=true;tabs.hidden=true;form.hidden=false;search.removeAttribute('aria-activedescendant');linkInput.value='';linkInput.setCustomValidity('');linkInput.focus();return;}
  try{
   if(isAttachment(item.id)){core.pickAttachment(item.id,{removeSlash,onClose:()=>close(true)});return;}
   close();
   if(!core.run(item.id,undefined,{removeSlash}))status('현재 위치에서는 사용할 수 없는 동작입니다.');else status('');
  }catch(e){status(e instanceof Error?e.message:'동작을 실행하지 못했습니다.');}
 }
 function render(){
  if(!core||disposed||menu.hidden)return;
  const query=search.value.replace(/^\//,'').trim().toLowerCase();
  items=editorCommands.filter(item=>(query||category==='전체'||item.category===category)&&(!query||`${item.label} ${item.keyword} ${item.aliases??''}`.toLowerCase().includes(query)));
  if(query)items.sort((a,b)=>Number(b.keyword===query)-Number(a.keyword===query));
  if(!items[selected]||!allowed(items[selected]))selected=items.findIndex(allowed);
  for(const tab of tabs.querySelectorAll<HTMLButtonElement>('button')){const active=tab.textContent===category;tab.setAttribute('aria-selected',String(active));tab.tabIndex=active?0:-1;}
  panel.setAttribute('aria-labelledby',`${prefix}-tab-${categories.indexOf(category)}`);
  list.replaceChildren();
  items.forEach((item,index)=>{
   const el=doc.createElement('button');el.type='button';el.tabIndex=-1;el.id=`${prefix}-${item.id}`;el.setAttribute('role','option');el.setAttribute('aria-selected',String(index===selected));el.setAttribute('aria-disabled',String(!allowed(item)));el.className=index===selected?'se-chosen':'';
   const name=doc.createElement('span');name.textContent=item.label;const key=doc.createElement('small');key.textContent=`/${item.keyword}`;
   if(!allowed(item))key.textContent+=' · 현재 위치 사용 불가';
   else if(core!.isActive(item.id))key.textContent+=' · 적용 중';
   el.append(name,key);el.addEventListener('mousedown',event=>event.preventDefault());el.addEventListener('click',()=>run(item));list.append(el);
  });
  count.textContent=items.length?`${items.length}개 기능${query?' · 전체 종류 검색':''}`:'일치하는 기능이 없습니다. 다른 이름으로 검색해 보세요.';
  if(selected>=0)search.setAttribute('aria-activedescendant',`${prefix}-${items[selected].id}`);else search.removeAttribute('aria-activedescendant');
 }
 for(const [index,name]of categories.entries()){
  const tab=doc.createElement('button');tab.type='button';tab.id=`${prefix}-tab-${index}`;tab.textContent=name;tab.setAttribute('role','tab');tab.setAttribute('aria-controls',panel.id);
  tab.addEventListener('click',()=>{category=name;search.value='';selected=0;render();});
  tab.addEventListener('keydown',event=>{if(!['ArrowRight','ArrowLeft','Home','End'].includes(event.key))return;event.preventDefault();const next=event.key==='Home'?0:event.key==='End'?categories.length-1:(index+(event.key==='ArrowRight'?1:-1)+categories.length)%categories.length;const target=tabs.children[next] as HTMLButtonElement;target.click();target.focus();});tabs.append(tab);
 }
 search.addEventListener('input',()=>{selected=0;category='전체';panel.hidden=false;tabs.hidden=false;form.hidden=true;render();});
 search.addEventListener('keydown',event=>{
  if(event.isComposing)return;
  if(event.key==='/'&&!search.value){event.preventDefault();close();dismissed='/';core?.insertText('/');core?.focus();return;}
  if(event.key==='ArrowDown'||event.key==='ArrowUp'){
   event.preventDefault();const enabled=items.map((item,index)=>allowed(item)?index:-1).filter(index=>index>=0);if(!enabled.length)return;
   selected=enabled[(enabled.indexOf(selected)+(event.key==='ArrowDown'?1:-1)+enabled.length)%enabled.length];render();list.children[selected]?.scrollIntoView?.({block:'nearest'});
  }else if(event.key==='Enter'){event.preventDefault();if(items[selected])run(items[selected]);}
 });
 menu.addEventListener('keydown',event=>{if(event.key==='Escape'&&!event.isComposing){event.preventDefault();event.stopPropagation();close(true);}});
 form.addEventListener('submit',event=>{event.preventDefault();try{if(core?.run('link',linkInput.value,{removeSlash})){close(true);status('링크를 적용했습니다.');}}catch(e){status(e instanceof Error?e.message:'주소를 확인해 주세요.');linkInput.setCustomValidity(message.textContent??'');linkInput.reportValidity();}});
 linkInput.addEventListener('input',()=>linkInput.setCustomValidity(''));
 for(const [button,markdown]of [[plain,false],[formatted,true]] as const)button.addEventListener('click',()=>{const source=pasteSource;close();try{if(markdown)core?.insertMarkdown(source);else core?.insertText(source);core?.focus();status(markdown?'Markdown 서식을 적용했습니다.':'원문 그대로 붙여넣었습니다.');}catch(e){status(e instanceof Error?e.message:'붙여넣지 못했습니다.');}});
 const pasteCancel=doc.createElement('button');pasteCancel.type='button';pasteCancel.textContent='취소';pasteCancel.addEventListener('click',()=>close(true));paste.append(pasteCancel);
 function choosePaste(source:string){close();pasteSource=source;menu.hidden=false;menu.setAttribute('aria-label','붙여넣기 방식');header.hidden=true;tabs.hidden=true;panel.hidden=true;form.hidden=true;help.hidden=true;paste.hidden=false;position();formatted.focus();}
 const outside=(event:Event)=>{if(!menu.hidden&&!menu.contains(event.target as Node))close();};doc.addEventListener('pointerdown',outside);
 const refresh=()=>{if(!core||disposed)return;const slash=core.getSlash();if(menu.hidden&&slash&&dismissed!==`/${slash.query}`)open(slash.query,true);if(!slash)dismissed='';};
 const trigger=()=>{if(!core?.canOpenSlash())return false;open();return true;};
 const reposition=()=>position();win.addEventListener('resize',reposition);win.addEventListener('scroll',reposition,true);win.visualViewport?.addEventListener('resize',reposition);
 function cleanup(){doc.removeEventListener('pointerdown',outside);win.removeEventListener('resize',reposition);win.removeEventListener('scroll',reposition,true);win.visualViewport?.removeEventListener('resize',reposition);}
 try{core=createEditorCore({...options,element:body,onStateChange:refresh,onMarkdownPaste:choosePaste,onError:error=>{status(error.message);options.onError?.(error);},onKeyDown:event=>event.key==='/'&&!event.ctrlKey&&!event.metaKey&&!event.altKey&&trigger(),onBeforeInput:event=>event.inputType==='insertText'&&event.data==='/'&&trigger()});}
 catch(error){cleanup();root.remove();throw error;}
 return {...core,setValue:(...args:Parameters<typeof core.setValue>)=>{close();core!.setValue(...args);},destroy:()=>{if(disposed)return;disposed=true;cleanup();core!.destroy();root.remove();}};
}
