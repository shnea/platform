import {useEffect,useState} from 'react';
import {auth} from '../../shared/auth';
import {readApiError} from '../../shared/api-error';

type Feature={id:string;status:string;model?:string;defaultDimensions?:number;maxDimensions?:number;maxBatch?:number;taskTypes?:string[]};
type Services={configured:boolean;features:Feature[]};
const labels:Record<string,string>={'raya.route':'Raya 난이도 판단',embeddings:'공통 텍스트 임베딩','n8n.execute':'n8n AI 작업 실행',usage:'공통 AI 사용량','vector.index':'공통 벡터 인덱싱'};
const states:Record<string,string>={implemented:'플랫폼 연결 구현',awaiting_upstream_api:'뇌대리의 작업 접수·결과·사용량 API 제공 대기',workflow_example_only:'수동·내부 워크플로 예제만 제공'};
export function AiGuide(){
 const [data,setData]=useState<Services|null>(null),[error,setError]=useState(''),[loading,setLoading]=useState(false);
 async function load(){setLoading(true);setError('');try{await auth.updateToken(30);const response=await fetch('/api/v1/admin/ai/services',{headers:{Authorization:`Bearer ${auth.token}`},cache:'no-store'});if(!response.ok)throw await readApiError(response);setData(await response.json());}catch(e){setError(e instanceof Error?e.message:'AI 지원 상태를 조회하지 못했습니다.');}finally{setLoading(false);}}
 useEffect(()=>{void load();},[]);
 return <><h2>로그인 방식과 독립적인 공통 AI</h2><p>프로젝트가 다른 OIDC를 사용하거나 플랫폼 로그인 기능을 사용하지 않아도 환경별 서버 키로 호출할 수 있습니다. 이용자 인증·권한은 프로젝트 서버가 확인하며 키는 브라우저에 전달하지 않습니다.</p>
  <div className="actions"><a href="/integrations/ai.md" target="_blank" rel="noreferrer">AI·임베딩·n8n 전체 연결 지침</a><a href="/integrations/ai.openapi.json" target="_blank" rel="noreferrer">AI OpenAPI</a><a href="/examples/ai-client.py" download>Python 서버 예제</a><a href="/integrations/n8n-embeddings.sample.json" download>n8n 공통 임베딩</a><a href="/integrations/n8n-ai-jobs.sample.json" download>n8n AI 작업 호출</a><a href="/integrations/n8n-vector-indexing.sample.json" download>n8n 공통 인덱싱</a></div>
  <h3>서버 연결 순서</h3><ol><li>프로젝트 → API 키에서 ai:read·ai:route·ai:embed·ai:execute·ai:jobs:read·ai:cancel·ai:usage·ai:index:write·ai:index:read·ai:index:search 중 필요한 권한만 선택해 새 키를 발급합니다. 기존 키에 자동으로 추가되지 않습니다.</li><li>프로젝트 서버에 PLATFORM_URL·PLATFORM_API_KEY를 주입합니다. X-Platform-Key 헤더로 플랫폼을 호출합니다.</li><li>임베딩은 POST /api/v1/ai/embeddings, Raya는 POST /api/v1/ai/raya/route, AI·RAG 실행은 POST /api/v1/ai/jobs를 사용합니다. sync=false 접수 후 같은 ID로 상태·결과를 조회할 수 있습니다.</li></ol>
  <h3>문서 색인 전체 교체</h3><p>POST /api/v1/ai/indexing에 mode: replace_all과 한 컬렉션의 전체 documents를 보내면 그 범위만 원자적으로 교체합니다. 빈 documents는 전체 삭제입니다. 한 번에 최대 100건이며 긴 문서는 호스트에서 분할하세요. 작업 상태·컬렉션 통계·유사도 검색은 공개 AI 지침을 확인하세요. PostgreSQL 색인은 기존 n8n Qdrant 예제와 자동 동기화되지 않습니다.</p>
  <h3>작업 ID와 데이터 범위</h3><p>새 작업의 request_id를 호스트에 보관하세요. 같은 ID·내용은 기존 상태를 조회하며 실패·취소·통신 오류 후 모델을 자동 재실행하지 않습니다. 새 실행에는 새 ID를 사용합니다. 프로젝트·환경은 서버 키에서 결정하고 RAG 컬렉션도 해당 범위로 분리합니다. 결과는 뇌대리 expires_at까지 이용하며 만료 후 이력만 조회합니다.</p>
  <h3>현재 지원 상태</h3><button className="secondary" disabled={loading} onClick={()=>void load()}>{loading?'조회 중…':'지원 상태 새로고침'}</button>{error&&<p className="alert" role="alert">{error}</p>}
  {data&&<><p role="status">서버 연결 설정: {data.configured?'등록됨':'미등록'}{error?' · 이전 조회 결과':''}</p><p className="small muted">설정 등록은 실제 공급자 호출 성공을 의미하지 않습니다. 테스트용 텍스트로 서버 연결을 검수하세요.</p><dl>{data.features.map(feature=><div key={feature.id}><dt>{labels[feature.id]??feature.id}</dt><dd>{states[feature.status]??'지원 상태 확인 필요'}{feature.model&&<> · {feature.model}, 기본 {feature.defaultDimensions}차원 / 최대 {feature.maxDimensions}차원 · 최대 {feature.maxBatch}건</>}{feature.taskTypes&&<p>{feature.taskTypes.join(', ')}</p>}</dd></div>)}</dl></>}
  <h3>검색·인덱싱의 모델 일치</h3><p>단일 문자열 또는 최대 100건 배열을 보내며 기본 모델은 gemini-embedding-001, 기본 768차원입니다. 응답 index로 입력과 연결하세요. 기존 text-embedding-004 벡터와 같은 차원이어도 섞지 말고 같은 모델·차원으로 검색과 인덱싱을 맞추세요.</p>
  <p>Raya는 텍스트의 L1/L2/L3 난이도를 판단하며 실제 답변이나 이미지 분석을 제공하지 않습니다. 원문·벡터·결과는 플랫폼에 저장하지 않고 요청 식별자·내용 해시만 보관합니다.</p><p className="warning">뇌대리 v12의 AI 실행·조회·취소·사용량 API를 연결했습니다. 취소 접수는 실행 프로세스 종료를 보장하지 않습니다. 사용량은 뇌대리 보고값이며 실제 공급자 토큰·비용·잔여 한도와 대조하기 전까지 실측값으로 단정하지 않습니다. n8n 예제는 비활성 수동 검수용으로, 운영자가 Credentials·컬렉션 모델/차원·데이터 보존 정책을 확인한 뒤 연결합니다.</p>
 </>;
}
