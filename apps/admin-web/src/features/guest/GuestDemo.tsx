import {Icon} from '../../shared/Icon';
import {useEffect,useRef,useState} from 'react';
import {createRoot} from 'react-dom/client';
import {mountEditor} from '@shnea/editor/ui';
import {renderViewer,type EditorAppearance} from '@shnea/editor';
import {EditorAppearanceSettings} from '../editor/EditorAppearanceSettings';
import '@shnea/editor/style.css';
import '../../styles/style.css';
import '../files/files.css';
import './guest.css';
import mark from '../../assets/brand/shnea-mark.svg';
import {Dialog} from '../../shared/Dialog';
import {guestAttachments,samples,sampleDocument,uploadNotice} from './samples';

const sampleIcons:Record<string,import('../../shared/Icon').IconName>={image:'image',video:'video',audio:'music',file:'file'};
function GuestDemo(){
 const edit=useRef<HTMLDivElement>(null),read=useRef<HTMLDivElement>(null),editor=useRef<ReturnType<typeof mountEditor>|null>(null);
 const [tab,setTab]=useState<'edit'|'read'>('edit'),[message,setMessage]=useState(''),[reset,setReset]=useState(false),[dark,setDark]=useState(true);
 const [appearance,setAppearance]=useState<EditorAppearance>({});
 const changed=useRef(false);
 useEffect(()=>{document.documentElement.dataset.theme=dark?'dark':'light';},[dark]);
 useEffect(()=>{
  editor.current=mountEditor({element:edit.current!,value:sampleDocument(),attachments:guestAttachments,onChange:()=>{changed.current=true;setMessage('');},onError:error=>setMessage(error.code==='ATTACHMENT_ERROR'?uploadNotice:error.message)});
  const guard=(event:BeforeUnloadEvent)=>{if(changed.current){event.preventDefault();event.returnValue='';}};window.addEventListener('beforeunload',guard);
  return()=>{window.removeEventListener('beforeunload',guard);editor.current?.destroy();editor.current=null;};
 },[]);
 useEffect(()=>{if(tab==='read'&&editor.current&&read.current)return renderViewer(read.current,editor.current.getValue(),{attachments:guestAttachments,appearance});},[tab,appearance]);
 useEffect(()=>{editor.current?.setAppearance(appearance);},[appearance]);
 function insert(kind:string){setTab('edit');editor.current?.insertAttachment(samples[kind]);setMessage('샘플을 넣었습니다. 편집 화면에서 배치를 바꿔 보세요.');}
 function download(){const value=editor.current?.getValue();if(!value)return;const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='shnea-demo-document.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);setMessage('문서 JSON을 내려받았습니다.');}
 return <div className="guest-page"><a className="skip-link" href="#demo">체험 본문으로 이동</a><header className="guest-header"><a href="/demo.html" className="guest-brand" aria-label="SHNEA Platform 체험 홈"><img src={mark} alt=""/><span><span className="brand-wordmark" aria-hidden="true"/><small>Platform</small></span></a><nav aria-label="방문 메뉴"><button className="quiet" aria-label={dark?'밝은 화면':'어두운 화면'} title={dark?'밝은 화면':'어두운 화면'} data-tooltip={dark?'밝은 화면':'어두운 화면'} data-icon-only="true" onClick={()=>setDark(!dark)}><Icon name={dark ? 'sun' : 'moon'}/></button><a href="/"><Icon name="log-in"/>관리자 로그인</a></nav></header>
 <main id="demo" className="guest-main"><section className="guest-intro"><h1>만들고, 재생하고,<br/>직접 써보세요.</h1><p>SHNEA Platform의 에디터와 미디어 뷰어를<br className="guest-break"/> 로그인 없이 체험하는 공간입니다.</p><p className="guest-note">공개 샘플만 사용합니다. 작성 내용은 이 페이지에서만 유지되며 새로고침하면 사라집니다.</p></section>
 <section className="guest-workspace" aria-label="공개 에디터 체험"><div className="guest-controls"><div className="guest-modes" role="tablist" aria-label="문서 모드">{(['edit','read'] as const).map((mode,index)=><button key={mode} id={`guest-tab-${mode}`} role="tab" tabIndex={tab===mode?0:-1} aria-selected={tab===mode} aria-controls={`guest-${mode}`} onClick={()=>setTab(mode)} onKeyDown={event=>{const next=event.key==='Home'?0:event.key==='End'?1:['ArrowLeft','ArrowRight'].includes(event.key)?1-index:-1;if(next>=0){event.preventDefault();document.getElementById(`guest-tab-${next===0?'edit':'read'}`)?.focus();}}}><Icon name={mode === 'edit' ? 'pencil' : 'eye'}/>{mode==='edit'?'편집':'읽기'}</button>)}</div><div className="guest-document-actions"><button className="secondary" onClick={download}><Icon name="download"/>JSON 내려받기</button><button className="secondary" onClick={()=>setReset(true)}><Icon name="rotate-ccw"/>예제로 되돌리기</button></div></div>
 <div className="guest-samples" role="group" aria-label="샘플 넣기"><span>샘플 넣기</span>{[['image','이미지'],['video','영상'],['audio','오디오'],['file','파일']].map(([kind,label])=><button key={kind} className="secondary" onClick={()=>insert(kind)}><Icon name={sampleIcons[kind]}/>{label}</button>)}</div>
 <p className="guest-help">/로 편집 기능 선택 · 이미지를 눌러 확대 · 영상 화질·구간 이동 · 단일 미디어의 크기·정렬 조절</p>
 <EditorAppearanceSettings value={appearance} onChange={setAppearance}/>
 {message&&<p role="status" className="guest-feedback">{message}</p>}
 <div id="guest-edit" role="tabpanel" aria-labelledby="guest-tab-edit" hidden={tab!=='edit'} ref={edit}/><div id="guest-read" role="tabpanel" aria-labelledby="guest-tab-read" hidden={tab!=='read'} ref={read}/>
 </section><footer className="guest-footer"><p>파일 서비스와 연결된 실제 업로드·변환·관리는 관리자 공간에서 제공합니다.</p><a href="/"><Icon name="arrow-right"/>관리자 공간으로</a></footer></main>
 {reset&&<Dialog title="예제로 되돌릴까요?" close={()=>setReset(false)} busy={false}><p>현재 작성한 내용이 초기화됩니다. 보관하려면 먼저 JSON을 내려받아 주세요.</p><div className="form-actions"><button className="secondary" onClick={()=>setReset(false)}><Icon name="x"/>취소</button><button onClick={()=>{editor.current?.setValue(sampleDocument());changed.current=false;setTab('edit');setMessage('처음 예제로 되돌렸습니다.');setReset(false);}}><Icon name="rotate-ccw"/>예제로 되돌리기</button></div></Dialog>}
 </div>;
}
createRoot(document.getElementById('root')!).render(<GuestDemo/>);
