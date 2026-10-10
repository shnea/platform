import {Icon} from '../../shared/Icon';
import {useEffect,useState} from "react";
import {auth} from "../../shared/auth";
import {readApiError} from "../../shared/api-error";
import {SectionTabs} from "../../shared/SectionTabs";
import "./developer.css";
import {AiGuide} from './AiGuide';

type Operation={summary?:string;description?:string;security?:Record<string,string[]>[];parameters?:unknown[];requestBody?:unknown;responses?:unknown};
type Spec={paths:Record<string,Record<string,Operation>>;components:unknown};
const workflow=[
 ["업로드 시작","POST /api/v1/files/uploads","요청 ID, 파일 이름·크기·전체 SHA-256을 보냅니다. 공개 범위와 보존 코드를 함께 지정하세요."],
 ["조각 전송·재개","PATCH /api/v1/files/uploads/{id}","수신 위치와 조각 SHA-256을 헤더로 보냅니다. 중단되면 GET으로 receivedBytes를 확인하고 같은 키로 재개하세요."],
 ["업로드 완료","POST /api/v1/files/uploads/{id}/complete","전체 파일의 크기·해시 검증 후 fileId를 받습니다. 호스트 서비스는 이 ID를 저장하세요."],
 ["변환 상태·보기 URL","POST /api/v1/files/{id}/view-ticket","썸네일·뷰어·HLS URL과 준비 상태를 받습니다. 비공개 URL은 임시이므로 만료 시 재조회하세요."],
 ["공유 또는 삭제","GET /api/v1/files/{id}/share · DELETE /api/v1/files/{id}","공개 공유 페이지에는 제목·대표 이미지가 표시됩니다. 비공개 파일에는 비밀번호 공유를 별도로 만들 수 있습니다."],
];
export function DeveloperCenter(){
 const [tab,setTab]=useState("start"),[spec,setSpec]=useState<Spec|null>(null),[error,setError]=useState(""),[loading,setLoading]=useState(false),[filter,setFilter]=useState(""),[notice,setNotice]=useState("");
 async function load(){setLoading(true);setError("");try{await auth.updateToken(30);const res=await fetch('/api/v1/files/admin/openapi',{headers:{Authorization:`Bearer ${auth.token}`},cache:'no-store'});if(!res.ok)throw await readApiError(res);setSpec(await res.json());}catch(e){setError(e instanceof Error?e.message:"명세를 불러오지 못했습니다.");}finally{setLoading(false);}}
 useEffect(()=>{if(tab==='api'&&!spec)void load();},[tab]);
 async function copy(text:string){try{await navigator.clipboard.writeText(text);setNotice("예제를 복사했습니다.");}catch{setNotice("자동 복사가 되지 않습니다. 예제를 선택해 복사해 주세요.");}}
 const command='python file-client.py upload ./sample.mp4 --state ./upload-state.json --wait 120';
 const operations=spec?Object.entries(spec.paths).flatMap(([path,methods])=>Object.entries(methods).map(([method,op])=>({path,method,op}))).filter(({path,method,op})=>`${method} ${path} ${op.summary??''}`.toLowerCase().includes(filter.toLowerCase())):[];
 return <section className="developer-center" aria-label="개발자 센터">
  <SectionTabs id="developer" label="개발자 자료" value={tab} disabled={false} onChange={value=>{setTab(value);setNotice("");}} items={[{value:'start',label:'빠른 시작'},{value:'example',label:'서버 연동 예제'},{value:'jobs-logs',label:'Job·로그 연결'},{value:'ai',label:'AI·임베딩'},{value:'editor',label:'에디터 연동'},{value:'api',label:'파일 API 명세'}]}/>
  {notice&&<p className="notice" role="status">{notice}</p>}
  <div id="developer-panel" role="tabpanel" aria-labelledby={`developer-${tab}`}>
   {tab==='ai'&&<AiGuide/>}
   {tab==='jobs-logs'&&<><h2>외부 프로젝트에 Job·로그 연결하기</h2><p>프로젝트 API 키에서 필요한 기능 권한을 선택하세요. 아래 문서·명세·예제는 로그인 없이 다른 PC에서도 읽을 수 있습니다.</p><a href="/integrations/SERVICE_INTEGRATION.md" target="_blank" rel="noreferrer">AI용 전체 서비스 연결 지침</a><h3>비동기 Job</h3><p>서버에서 작업을 등록하고 프로젝트 워커가 가져가 실행합니다. 권한은 jobs:write / jobs:read / jobs:work입니다. 워커는 점유를 연장하고 업무를 중복 처리하지 않도록 구현하세요.</p><div className="actions"><a href="/integrations/jobs.md" target="_blank" rel="noreferrer">Job 연결 지침</a><a href="/integrations/jobs.openapi.json" target="_blank" rel="noreferrer">Job OpenAPI</a><a href="/examples/jobs-client.py" download>Python 워커 다운로드</a></div><h3>공통 로그</h3><p>서버에서 비동기로 전송합니다. 권한은 logs:write / logs:read입니다. 환경별 7일·하루 10 MiB 한도를 적용하고 전송 전에 비밀값을 제외하세요.</p><div className="actions"><a href="/integrations/logs.md" target="_blank" rel="noreferrer">로그 연결 지침</a><a href="/integrations/logs.openapi.json" target="_blank" rel="noreferrer">로그 OpenAPI</a><a href="/examples/logs-client.py" download>Python 전송기 다운로드</a></div></>}
   {tab==='editor'&&<><h2>프로젝트에 에디터 연결하기</h2><p>React·Vue 연결 컴포넌트와 일반 JS·JSP용 번들을 제공합니다. 같은 문서 JSON과 첨부 계약을 사용하며 본문 저장은 프로젝트 서버가 담당합니다.</p><div className="actions"><a className="developer-download" href="/examples/editor/" target="_blank" rel="noopener noreferrer"><Icon name="external-link"/>실행 예제 열기</a><a className="developer-download" href="/examples/editor/INTEGRATION.md" download><Icon name="download"/>연동 지침 다운로드</a></div><p>예제에서 입력·실행 취소·메모리 보관·다시 불러오기·읽기 결과를 확인하세요. 새로고침하면 내용이 사라지며 실제 파일 업로드는 연결하지 않았습니다.</p><h3>연결 순서</h3><ol><li>프로젝트의 프레임워크에 맞는 에디터·뷰어와 공통 CSS를 연결합니다.</li><li>변경 이벤트로 받은 JSON을 호스트 API에 저장하고, 읽기 화면에 같은 JSON을 전달합니다.</li><li>첨부는 호스트 서버를 통한 업로드·조회 연결을 지정합니다. 서버 키는 브라우저에 넣지 않습니다.</li></ol><p className="small muted">현재 내부 검증용 패키지이며 공개 npm에는 발행하지 않았습니다. 지침의 로컬 패키지 설치·JSP 실행 절차를 사용하세요.</p></>}
   {tab==='start'&&<><h2>프로젝트 서버에서 파일 서비스 연결하기</h2><p className="muted">브라우저 → 프로젝트 서버 → 플랫폼 파일 서비스 순서로 연결합니다. 사용자별 업로드 허용 여부는 프로젝트 서버가 판단합니다.</p>
    <ol className="developer-steps"><li><h3>파일 서비스 사용 켜기</h3><p>프로젝트 생성 시 선택하거나 프로젝트 → 프로젝트 설정에서 켜세요. 모든 환경에 적용됩니다.</p></li><li><h3>환경별 서버 키 발급</h3><p>프로젝트 → API 키에서 파일 조회·업로드 권한을 선택하세요. 삭제·공유 관리 권한은 필요한 서버에만 추가하세요. 기존 키에 새 권한이 자동으로 붙지 않습니다.</p></li><li><h3>서버에서 호출하기</h3><p><code>X-Platform-Key</code> 헤더로 키를 전달합니다. 서버의 비밀값 저장소에 보관하고 프런트 코드·본문·URL에는 넣지 마세요.</p></li></ol>
    <h3>업로드부터 파일 사용까지</h3><ol className="developer-workflow">{workflow.map(([title,path,description])=><li key={title}><strong>{title}</strong><code>{path}</code><p>{description}</p></li>)}</ol>
    <button onClick={()=>setTab('example')}><Icon name="code-xml"/>실행 예제로 확인</button>
   </>}
   {tab==='example'&&<><h2>실행 가능한 서버 연동 예제</h2><p>Python 3.11 이상과 표준 라이브러리만 사용합니다. 프로젝트 서버나 개발 PC에서 실행하고, 실제 서비스에서는 사용자 권한 확인 후 <code>Client.upload()</code>를 호출하세요.</p>
    <a className="developer-download" href="/examples/file-client.py" download><Icon name="download"/>file-client.py 다운로드</a>
    <h3>1. 서버 환경변수 설정</h3><dl><dt><code>PLATFORM_URL</code></dt><dd>{location.origin}</dd><dt><code>PLATFORM_API_KEY</code></dt><dd>해당 프로젝트·환경에서 발급한 파일 권한 서버 키</dd></dl><p className="small muted">키를 입력하거나 저장하는 브라우저 폼은 제공하지 않습니다. 실행 서버의 환경변수로 지정하세요.</p>
    <h3>2. 업로드·변환 상태 확인</h3><pre tabIndex={0}><code>{command}</code></pre><button className="secondary" onClick={()=>void copy(command)}><Icon name="copy"/>업로드 명령 복사</button>
    <p>영상 자동 자막은 업로드 시작 시 <code>videoOptions.subtitles</code>로 선택합니다. <code>mode:sidecar</code>는 VTT 켜기·끄기, <code>mode:burned</code>는 모든 HLS 화질에 입히며 재생 중 끌 수 없습니다. 생략·null은 기존 처리입니다.</p><pre tabIndex={0}><code>{'python file-client.py upload ./sample.mp4 --state ./subtitle-upload.json --subtitles sidecar --subtitle-language ko --wait 120'}</code></pre><p className="small muted">같은 재개 기록에는 같은 옵션을 유지하세요. 자막·전사 URL은 보기 응답의 subtitleUrls를 사용하며 원본과 같은 접근 규칙을 따릅니다. 자동 생성 자막의 시각은 근삿값입니다. 별도 STT Job·MP4 출력·자막 번역은 요청하지 않습니다.</p>
    <p>최대 8MiB 조각으로 전송하고 최대 120초 동안 변환 상태를 확인합니다. 실패·중단 후 같은 명령을 실행하면 기록과 서버 수신 위치로 재개합니다. 업로드 기록에는 키를 저장하지 않습니다.</p>
    <h3>3. 호스트 서비스에 연결</h3><pre tabIndex={0}><code>{'from file_client import Client\n\n# 프로젝트 서버에서 요청자의 업로드 권한을 먼저 확인합니다.\nfile = Client().upload("/server/upload/sample.mp4", "/server/state/upload.json")\nfile_id = file["fileId"]  # 호스트 DB·에디터 본문에는 ID 저장\nviews = Client().views(file_id)  # 임시 URL은 필요할 때 재조회'}</code></pre><p className="small muted">모듈로 가져올 때는 파일명을 <code>file_client.py</code>로 바꾸세요. 서버의 임시 원본과 재개 기록은 호스트가 관리합니다. 브라우저 업로드 엔드포인트에는 호스트의 인증·용량 제한을 적용하세요.</p>
    <h3>4. 다시 보기·삭제</h3><pre tabIndex={0}><code>{'python file-client.py views FILE_ID\npython file-client.py delete FILE_ID'}</code></pre><p>삭제는 원본과 파생 콘텐츠를 정리합니다. 테스트용 파일 ID로 확인하세요.</p>
    <details><summary>오류와 운영 시 확인할 점</summary><ul><li>401: 키 만료·폐기·프로젝트 상태를 확인하세요.</li><li>403: 프로젝트 파일 사용 설정과 키의 기능 권한을 확인하세요.</li><li>409: 수신 위치·변경된 설정을 재조회하고 용량 부족 여부를 확인하세요.</li><li>429·503: 반복 요청을 멈추고 응답의 재시도 안내를 확인하세요.</li><li>오류 응답의 code·detail·requestId로 원인을 찾으세요. 키·임시 URL은 로그에서 가리세요.</li><li>파일 서비스를 꺼도 보존·자동 정리는 계속 적용됩니다. 이미 받은 파일과 외부 공유 카드 캐시는 회수할 수 없습니다.</li></ul></details>
   </>}
   {tab==='api'&&<><div className="section-line"><h2>파일 API 명세</h2><button className="secondary" disabled={loading} onClick={()=>void load()} aria-label="명세 새로고침" title="명세 새로고침" data-tooltip="명세 새로고침" data-icon-only="true"><Icon name="refresh-cw"/></button></div><p className="muted">실행 중인 파일 서비스의 OpenAPI 명세입니다. 서버 키 API와 관리자 전용 API를 구분해 확인하세요.</p>
    {loading&&<p role="status">명세를 불러오는 중…</p>}{error&&<p className="alert" role="alert">{error}</p>}
    {spec&&<><label>API 검색<input type="search" value={filter} onChange={e=>setFilter(e.target.value)} placeholder="예: uploads, 공유, DELETE"/></label><p className="small muted" role="status">{operations.length}개 작업{error?' · 이전 조회 결과':''}</p>
    <button className="secondary" onClick={()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(spec,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='platform-files.openapi.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}}><Icon name="download"/>OpenAPI JSON 다운로드</button>
    <div className="developer-operations">{operations.map(({path,method,op})=><details key={method+path}><summary><span className="identifier">{method.toUpperCase()}</span><span>{op.summary??path}</span><code>{path}</code></summary><p>{op.description}</p><p className="small muted">{path.includes('/admin/')?'관리자 Bearer JWT':op.security?.length?'서버 API 키 또는 명세에 지정한 인증':'공개 접근 · 파일 공개 범위/공유 상태 확인'}</p><pre tabIndex={0}><code>{JSON.stringify(op,null,2)}</code></pre></details>)}</div>
    <details><summary>공통 데이터 형식·인증 정의</summary><pre tabIndex={0}><code>{JSON.stringify(spec.components,null,2)}</code></pre></details></>}
   </>}
  </div>
 </section>;
}
