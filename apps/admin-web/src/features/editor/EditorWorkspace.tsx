import {Icon} from '../../shared/Icon';
import {useEffect,useRef,useState} from 'react';
import {fromMarkdown,parseDocument,renderViewer,type EditorDocument} from '@shnea/editor';
import {mountEditor} from '@shnea/editor/ui';
import '@shnea/editor/style.css';
import {SectionTabs} from '../../shared/SectionTabs';
import {Dialog} from '../../shared/Dialog';
import './editor-workspace.css';
import {api} from '../../shared/auth';
import {editorAttachments} from './editor-attachments';
type Project={id:string;name:string;status:string;filesEnabled:boolean};
type Environment={id:string;code:string;kind:string;state:string};

const example=`# 여기서 문서를 작성해 보세요

제목과 본문을 직접 바꾸거나 **굵게**, *기울임*, [링크](https://shnea.kr)를 선택해 보세요.

## 할 일 정리

- [x] 문서 작성하기
- [ ] 읽기 화면에서 확인하기
- [ ] JSON으로 내려받기

> / 를 입력하면 종류별 편집 메뉴가 열립니다. /table로 표를 추가해 보세요.

| 기능 | 확인할 내용 |
| :--- | :--- |
| 붙여넣기 | Markdown 서식 변환 |
| 읽기 | 편집한 문서 그대로 보기 |

\`\`\`js
const message = "안녕하세요";
\`\`\`
`;

export function EditorWorkspace({draft}:{draft:{current:unknown}}){
 const editRoot=useRef<HTMLDivElement>(null),viewRoot=useRef<HTMLDivElement>(null),editor=useRef<ReturnType<typeof mountEditor>|null>(null);
 const [tab,setTab]=useState<'edit'|'read'>('edit'),[error,setError]=useState(''),[jsonError,setJsonError]=useState(''),[notice,setNotice]=useState(''),[json,setJson]=useState(''),[changed,setChanged]=useState(!!draft.current),[pending,setPending]=useState<{value:EditorDocument;title:string}|null>(null);
 const [initial]=useState(()=>draft.current??fromMarkdown(example));
 const scope=useRef<string|undefined>(undefined),[attachments]=useState(()=>editorAttachments(()=>scope.current));
 const [projects,setProjects]=useState<Project[]>([]),[project,setProject]=useState(''),[environments,setEnvironments]=useState<Environment[]>([]),[environment,setEnvironment]=useState(''),[scopeError,setScopeError]=useState(''),[loading,setLoading]=useState(false),[reload,setReload]=useState(0);
 useEffect(()=>{let stopped=false;setLoading(true);setScopeError('');void (async()=>{const rows:Project[]=[];for(let offset=0;;offset+=100){const page=await api<Project[]>(`/projects?limit=100&offset=${offset}`);rows.push(...page);if(page.length<100)break;}if(!stopped)setProjects(rows);})().catch(e=>{if(!stopped)setScopeError(e.message);}).finally(()=>{if(!stopped)setLoading(false);});return()=>{stopped=true;};},[reload]);
 useEffect(()=>{let stopped=false;scope.current=undefined;setEnvironment('');setEnvironments([]);if(!project)return;setLoading(true);setScopeError('');void api<Environment[]>(`/projects/${project}/environments`).then(rows=>{if(!stopped)setEnvironments(rows);}).catch(e=>{if(!stopped)setScopeError(e.message);}).finally(()=>{if(!stopped)setLoading(false);});return()=>{stopped=true;};},[project,reload]);
 useEffect(()=>{
  try{editor.current=mountEditor({element:editRoot.current!,value:initial,attachments,onChange:({document})=>{draft.current=document;setChanged(true);setNotice('');},onError:e=>setError(e.message)});}
  catch(e){setError(e instanceof Error?e.message:'에디터를 열지 못했습니다.');}
  return ()=>{if(editor.current){draft.current=editor.current.getValue();editor.current.destroy();editor.current=null;}};
 },[]);
 useEffect(()=>{
  if(tab!=='read'||!editor.current||!viewRoot.current)return;
  try{return renderViewer(viewRoot.current,editor.current.getValue(),{attachments});}catch(e){setError(e instanceof Error?e.message:'읽기 화면을 열지 못했습니다.');}
 },[tab]);
 useEffect(()=>{if(!changed)return;const guard=(event:BeforeUnloadEvent)=>{event.preventDefault();event.returnValue='';};window.addEventListener('beforeunload',guard);return()=>window.removeEventListener('beforeunload',guard);},[changed]);
 function replace(){if(!pending||!editor.current)return;editor.current.setValue(pending.value,{emitChange:true});setPending(null);setTab('edit');setError('');setNotice('문서를 바꿨습니다. 이전 문서의 실행 취소 이력은 초기화됩니다.');}
 function getJSON(){return JSON.stringify(editor.current?.getValue()??initial,null,2);}
 function download(){const url=URL.createObjectURL(new Blob([getJSON()],{type:'application/json'}));const anchor=document.createElement('a');anchor.href=url;anchor.download='shnea-document-v3.json';anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000);setNotice('JSON 다운로드를 요청했습니다. 다운로드 목록을 확인해 주세요.');}
 return <section className="editor-workspace" aria-label="에디터 체험">
  <div className="editor-intro"><p><strong>/ 로 편집 기능을 골라 보세요.</strong> 내용은 메뉴 이동 시 유지되지만 서버에 저장하지 않으며 새로고침·로그아웃하면 사라집니다.</p></div>
  <details className="editor-attachment-settings"><summary>첨부 저장 위치 {environment?'· 선택됨':'· 프로젝트와 환경 선택'}</summary><p className="small muted">글 편집은 선택 없이 사용할 수 있습니다. 첨부는 선택한 환경에 공개 파일·기본 보존 정책으로 실제 저장됩니다. 문서에서 지워도 저장된 파일은 남으며 파일 메뉴에서 관리합니다.</p><div className="editor-scope-fields"><label>첨부 프로젝트<select aria-label="첨부 프로젝트" value={project} disabled={loading} onChange={e=>{scope.current=undefined;setProject(e.target.value);}}><option value="">프로젝트 선택</option>{projects.map(p=><option key={p.id} value={p.id} disabled={p.status!=='ACTIVE'||!p.filesEnabled}>{p.name}{!p.filesEnabled?' · 파일 사용 안 함':p.status!=='ACTIVE'?' · 중지됨':''}</option>)}</select></label><label>첨부 환경<select aria-label="첨부 환경" value={environment} disabled={loading||!project} onChange={e=>{setEnvironment(e.target.value);scope.current=e.target.value||undefined;}}><option value="">환경 선택</option>{environments.map(e=><option key={e.id} value={e.id} disabled={e.state!=='READY'}>{e.code} ({e.kind}){e.state!=='READY'?' · 준비되지 않음':''}</option>)}</select></label></div>{loading&&<p role="status">저장 위치 조회 중…</p>}{scopeError&&<p role="alert" className="alert">{scopeError}</p>}<button className="secondary" disabled={loading} onClick={()=>setReload(value=>value+1)} aria-label="목록 새로 조회" title="목록 새로 조회" data-tooltip="목록 새로 조회" data-icon-only="true"><Icon name="refresh-cw"/></button></details>
  <div className="editor-workspace-actions"><SectionTabs id="editor-mode" label="문서 화면" value={tab} disabled={false} onChange={value=>{setError('');setNotice('');setTab(value);}} items={[{value:'edit',label:'편집'},{value:'read',label:'읽기'}]}/><div className="actions"><button className="secondary" onClick={()=>setPending({value:fromMarkdown(example),title:'예제 문서로 바꿀까요?'})}><Icon name="folder-open"/>예제 불러오기</button><button className="secondary" onClick={()=>setPending({value:parseDocument(null),title:'문서를 비울까요?'})}><Icon name="trash-2"/>문서 비우기</button></div></div>
  {error&&<p className="alert" role="alert">{error}</p>}{notice&&<p className="notice" role="status">{notice}</p>}
  <div id="editor-mode-panel" role="tabpanel" aria-labelledby={`editor-mode-${tab}`}><div hidden={tab!=='edit'} ref={editRoot}/>{tab==='read'&&<div ref={viewRoot}/>}</div>
  <p className="small muted editor-limit">/file · /image · /video · /audio로 첨부하거나 이미지를 붙여넣고 파일을 본문에 놓아 보세요. 미디어 위의 ‘추가’로 이미지·영상을 한 줄에 최대 3개 배치합니다. 하나일 때는 모서리로 크기를 조절하고 정렬을 바꿀 수 있습니다. 모바일은 전체 너비로 표시합니다.</p>
  <details className="editor-data"><summary>문서 JSON 내보내기·가져오기</summary><p className="small muted">다른 서비스는 이 JSON을 저장하고 에디터에 다시 전달합니다. 아래 적용은 현재 문서를 교체합니다.</p><div className="actions"><button className="secondary" onClick={()=>{setJson(getJSON());setNotice('현재 문서를 아래 입력란에 표시했습니다.');}}><Icon name="file-json"/>현재 JSON 보기</button><button className="secondary" onClick={download}><Icon name="download"/>JSON 내려받기</button></div>
   <label>문서 JSON<textarea aria-label="문서 JSON" value={json} aria-invalid={!!jsonError} aria-describedby={jsonError?'editor-json-error':undefined} onChange={e=>{setJson(e.target.value);setJsonError('');}} rows={12} maxLength={5_000_000} spellCheck={false}/></label>{jsonError&&<p id="editor-json-error" className="alert" role="alert">{jsonError}</p>}<button className="secondary" disabled={!json.trim()} onClick={()=>{setNotice('');try{setPending({value:parseDocument(JSON.parse(json)),title:'입력한 JSON으로 바꿀까요?'});setJsonError('');}catch(e){setJsonError(e instanceof SyntaxError?'JSON 문법이 올바르지 않습니다. 괄호와 따옴표를 확인해 주세요.':e instanceof Error?e.message:'문서를 확인해 주세요.');}}}><Icon name="check"/>입력한 JSON 적용</button>
  </details>
  {pending&&<Dialog title={pending.title} busy={false} close={()=>setPending(null)}><p>현재 작성 내용과 실행 취소 이력이 바뀝니다. 필요한 내용은 먼저 JSON으로 내려받으세요.</p><div className="actions"><button className="destructive" onClick={replace}><Icon name="rotate-ccw"/>문서 바꾸기</button><button className="secondary" onClick={()=>setPending(null)}><Icon name="x"/>취소</button></div></Dialog>}
 </section>;
}
