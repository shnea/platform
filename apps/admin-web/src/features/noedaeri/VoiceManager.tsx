import {useEffect,useRef,useState} from 'react';
import {fileApi} from '../files/file-api';
import {Dialog} from '../../shared/Dialog';
import {IndependentTask} from './IndependentTask';

type Profile={id:string;name:string;kind:string;speaker:string|null;status:string;sample_available:boolean;sample_bytes:number;error_code:string|null;registration_job_id:string|null;reference_text:string};
export function VoiceManager({environmentId,available,onBusyChange,initialJobId}:{environmentId:string;available:boolean;onBusyChange:(value:boolean)=>void;initialJobId?:string}) {
 const [profiles,setProfiles]=useState<Profile[]>([]),[error,setError]=useState(''),[busy,setBusy]=useState(false),[registrationBusy,setRegistrationBusy]=useState(false),[loaded,setLoaded]=useState(false);
 const [selected,setSelected]=useState<Profile|null>(null),[action,setAction]=useState<'rename'|'delete'|null>(null),[name,setName]=useState(''),[sample,setSample]=useState(''),[sampleName,setSampleName]=useState('');
 const mounted=useRef(true),locked=useRef(false),urls=useRef<string[]>([]);
 const root='/noedaeri/voices';
 useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;urls.current.forEach(url=>URL.revokeObjectURL(url));onBusyChange(false);};},[onBusyChange]);
 useEffect(()=>{onBusyChange(busy||registrationBusy);},[busy,registrationBusy,onBusyChange]);
 async function refresh() {
  if(locked.current)return;locked.current=true;setBusy(true);setError('');
  try{const value=await fileApi<Profile[]>(environmentId,root);if(mounted.current){setProfiles(value);setLoaded(true);}}
  catch(error){if(mounted.current)setError(error instanceof Error?error.message:String(error));}
  finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 useEffect(()=>{if(available)void refresh();},[environmentId,available]);
 async function mutate() {
  if(!selected||!action||locked.current)return;locked.current=true;setBusy(true);setError('');
  try {
   const value=await fileApi<{deleted?:boolean}>(environmentId,root+'/'+selected.id,action==='rename'?'PATCH':'DELETE',action==='rename'?{name}:undefined,undefined,action==='delete'?{'X-Confirm-Voice':selected.id}:{});
   if(action==='delete'&&value.deleted!==true)throw new Error('참조 음성 정리에 실패했습니다. 같은 프로필 삭제를 다시 확인하세요.');
   if(mounted.current){if(action==='delete'){urls.current.forEach(url=>URL.revokeObjectURL(url));urls.current=[];setSample('');}setAction(null);setSelected(null);setProfiles(await fileApi<Profile[]>(environmentId,root));}
  }catch(error){if(mounted.current)setError(error instanceof Error?error.message:String(error));}
  finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 async function preview(profile:Profile) {
  if(locked.current)return;locked.current=true;setBusy(true);setError('');
  try {
   const ticket=await fileApi<{downloadUrl:string}>(environmentId,root+'/'+profile.id+'/sample-ticket','POST',{});
   if(!/^\/api\/v1\/files\/downloads\/[A-Za-z0-9_-]{43}$/.test(ticket.downloadUrl))throw new Error('잘못된 다운로드 주소입니다.');
   const response=await fetch(ticket.downloadUrl,{cache:'no-store'});if(!response.ok)throw new Error('참조 음성 권한·보존 상태를 확인하세요.');
   const blob=await response.blob();if(!mounted.current)return;
   urls.current.forEach(url=>URL.revokeObjectURL(url));const url=URL.createObjectURL(new Blob([blob],{type:'audio/wav'}));urls.current=[url];setSample(url);setSampleName(profile.name);
  }catch(error){if(mounted.current)setError(error instanceof Error?error.message:String(error));}
  finally{locked.current=false;if(mounted.current)setBusy(false);}
 }
 const disabled=busy||registrationBusy||!available;
 return <div><p>이 관리자·프로젝트·환경의 목소리만 관리합니다. 요청자 ID는 서버에서 확정하며 다른 이용자의 프로필을 선택하지 않습니다. 등록·삭제는 실제 뇌대리 저장소에 적용됩니다.</p>
  {error&&<p role="alert" className="alert">{error}</p>}
  <button className="secondary" disabled={disabled} onClick={()=>void refresh()}>목소리 목록 새로 조회</button>
  {loaded&&profiles.length===0&&<p>등록된 목소리가 없습니다. 등록하지 않아도 음성 생성에서 기본 Sohee를 사용할 수 있습니다.</p>}
  {loaded&&<details><summary>목소리 목록 원본 JSON</summary><pre className="noedaeri-output">{JSON.stringify(profiles,null,2)}</pre></details>}
  {profiles.length>0&&<div className="table-scroll"><table><thead><tr><th>이름</th><th>종류·상태</th><th>참조 검증·샘플</th><th>관리</th></tr></thead><tbody>{profiles.map(profile=><tr key={profile.id}><td>{profile.name}</td><td>{profile.kind==='preset'?'프리셋 '+profile.speaker:'참조 음성'} · {profile.status}<br/>{profile.error_code}</td><td>{profile.kind==='preset'?'기본 목소리는 참조 파일 없음':profile.sample_available?`${profile.sample_bytes} B · 샘플 사용 가능`:'참조 검증 중·실패 또는 파일 유실'}{profile.reference_text&&<details><summary>등록 대본</summary><p>{profile.reference_text}</p></details>}</td><td><div className="actions"><button className="secondary" disabled={disabled||profile.kind!=='clone'||profile.status!=='ready'||!profile.sample_available} onClick={()=>void preview(profile)}>참조 재생</button><button className="secondary" disabled={disabled} onClick={()=>{setSelected(profile);setName(profile.name);setAction('rename');}}>이름 수정</button><button className="danger" disabled={disabled} onClick={()=>{setSelected(profile);setAction('delete');}}>삭제</button></div></td></tr>)}</tbody></table></div>}
  {sample&&<section><h4>{sampleName} · 정규화된 참조 음성</h4><audio controls src={sample}/><a href={sample} download="reference.wav">WAV 다운로드</a><p className="small muted">샘플은 플랫폼에 비공개 tmp 파일로 저장한 뒤 재생합니다. 프로필 자체는 명시적 삭제까지 뇌대리에 보관됩니다.</p></section>}
  <h4>새 목소리 등록·검증</h4><IndependentTask menu="voices" environmentId={environmentId} available={available&&!busy} onBusyChange={setRegistrationBusy} initialJobId={initialJobId}/>
  {selected&&action&&<Dialog title={action==='delete'?'목소리 삭제 확인':'목소리 이름 수정'} busy={busy} close={()=>{setAction(null);setSelected(null);}}><p>{selected.name} · <code>{selected.id}</code></p>{action==='rename'?<label>이름<input required maxLength={120} value={name} onChange={event=>setName(event.target.value)}/></label>:<p className="warning">뇌대리 참조 음성과 프로필을 영구 삭제하고 플랫폼 미리보기 샘플도 정리합니다. 원본 입력 파일과 이미 저장한 합성 WAV는 삭제하지 않습니다. 원본 삭제는 파일 메뉴에서 별도로 처리하세요. 사용 중인 작업이 있으면 서버가 삭제를 거절합니다.</p>}<div className="actions"><button className={action==='delete'?'danger':undefined} disabled={busy||action==='rename'&&!name.trim()} onClick={()=>void mutate()}>{action==='delete'?'확인 후 영구 삭제':'이름 저장'}</button><button className="secondary" disabled={busy} onClick={()=>setAction(null)}>닫기</button></div></Dialog>}
 </div>;
}
