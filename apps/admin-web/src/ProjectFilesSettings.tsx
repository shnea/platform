import {useState} from "react";
import {api} from "./auth";
import {Dialog} from "./Dialog";

export function ProjectFilesSettings({project,onChanged,onBusyChange}:{project:{id:string;filesEnabled:boolean;revision:number;status:string};onChanged:()=>void;onBusyChange:(busy:boolean)=>void}){
 const [confirm,setConfirm]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState("");
 async function save(){setBusy(true);onBusyChange(true);setError("");try{await api(`/projects/${project.id}/files`,"PUT",{enabled:!project.filesEnabled,revision:project.revision});setConfirm(false);onChanged();}catch(e){setError(e instanceof Error?e.message:"파일 사용 설정을 저장하지 못했습니다.");}finally{setBusy(false);onBusyChange(false);}}
 return <section className="project-files-setting" aria-labelledby="project-files-title"><h3 id="project-files-title">파일 서비스</h3>
  <p><strong>{project.filesEnabled?"사용 중":"사용 안 함"}</strong> · 프로젝트의 모든 환경에 적용</p>
  <p className="muted">업로드할 사용자는 이 프로젝트의 서버에서 판단합니다. 플랫폼에는 서버 키로 연결하세요.</p>
  <button className="secondary" disabled={busy||!project.filesEnabled&&project.status!=="ACTIVE"} onClick={()=>{setError("");setConfirm(true);}}>{project.filesEnabled?"파일 서비스 사용 끄기":"파일 서비스 사용 켜기"}</button>
  {confirm&&<Dialog title={project.filesEnabled?"파일 서비스 사용을 끌까요?":"파일 서비스를 사용할까요?"} busy={busy} close={()=>setConfirm(false)}>
   <p>{project.filesEnabled?"모든 환경의 파일 API·관리 화면과 공개·비밀번호 공유 링크 접근이 차단됩니다. 이미 전송 중인 응답과 다운로드한 파일은 회수되지 않습니다.":"이 프로젝트의 파일 기능을 사용할 수 있습니다. API 키에는 사용할 파일 권한을 별도로 선택해 발급하세요."}</p>
   <p className="small muted">기존 파일과 키 권한은 유지됩니다. 보존 기간과 자동 정리 설정은 계속 적용되며, 다시 켜면 유효한 기존 링크와 키도 사용할 수 있습니다.</p>
   {error&&<p className="alert" role="alert">{error}</p>}<div className="actions"><button className={project.filesEnabled?"destructive":undefined} disabled={busy} onClick={()=>void save()}>{busy?"저장 중…":project.filesEnabled?"사용 끄기":"사용 켜기"}</button><button className="secondary" disabled={busy} onClick={()=>setConfirm(false)}>취소</button></div>
  </Dialog>}
 </section>;
}
