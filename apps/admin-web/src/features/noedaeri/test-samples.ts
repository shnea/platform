import {initialFields,type TestFields} from './test-contract';
const prompts:Record<string,string>={
 'blog.tags':'플랫폼은 프로젝트별 인증·파일·알림·AI를 공통 API로 제공한다. 영상은 HLS와 자동 자막으로 처리하며 결과를 영속 저장한다. 이 글의 태그를 생성해 주세요.',
 'blog.summary':'플랫폼은 프로젝트와 환경별로 계정과 파일 권한을 분리한다. 외부 서비스는 서버 API 키로 공통 기능을 사용하고, 관리자는 실행 상태와 사용량을 확인한다. 이 내용을 요약해 주세요.',
 'comment.generate':'원문: 파일 결과를 저장한 뒤 수령 확인을 보내야 합니다. 이 글에 차분하고 친근한 댓글을 작성해 주세요. 없는 경험이나 사실을 만들지 마세요.',
 'article.draft':'공통 AI 서비스 연동을 주제로 실험글 초안을 작성해 주세요. 문제 정의·가설·검증 절차를 나누고 아직 하지 않은 실험을 완료했다고 쓰지 마세요.',
 'portfolio.search':'벡터 검색과 공통 서비스 플랫폼을 구현한 프로젝트 경험을 알려 주세요. 검색 문서에 없는 내용은 추측하지 마세요.',
 'ui.render':'프로젝트 실행 상태를 확인하는 대시보드의 읽기 전용 화면을 제안해 주세요. 실제 변경은 적용하지 말고 사용자의 검토를 기다려 주세요.',
 'document.analyze':'문서: 결과 저장은 모든 파일이 준비된 뒤 완료로 처리한다. 입력 삭제나 세대 변경이 발생하면 오래된 결과를 반영하지 않는다. 요구사항과 실패 처리 조건을 분석해 주세요.',
 'code.analyze':'다음 코드의 빈 배열 처리와 입력 검증을 검토해 주세요.\nfunction average(values) { return values.reduce((total, value) => total + value, 0) / values.length; }',
 'chat.general':'동기 작업과 비동기 작업의 차이를 간단한 예시로 설명해 주세요.',
};
export function sampleFields(menu:string,current:TestFields):TestFields {
 const result={...initialFields,task:current.task};
 if(menu==='jobs'||menu==='raya')return {...result,prompt:prompts[current.task]??prompts['chat.general'],inputJson:JSON.stringify(current.task==='portfolio.search'?{collection:'noedaeri-test'}:current.task==='article.draft'?{context:{topic:'공통 AI 서비스 연동'}}:{},null,2)};
 if(menu==='translation')return {...result,prompt:'Hello. The processing result is ready. Please review it before confirming receipt.',sourceLanguage:'en',targetLanguage:'ko'};
 if(menu==='embeddings')return {...result,batch:current.batch,prompt:current.batch?JSON.stringify(['프로젝트별 파일 권한 관리','영상 HLS와 자동 자막 처리'],null,2):'프로젝트별 파일 권한 관리와 벡터 검색',dimensions:'768'};
 if(menu==='indexing')return {...result,collection:'noedaeri-test',mode:'upsert',documents:JSON.stringify([{id:'sample-platform-1',title:'공통 플랫폼',content:'프로젝트별 인증과 파일 권한을 분리하고 공통 API로 제공한다.',metadata:{category:'platform'}},{id:'sample-video-1',title:'영상 처리',content:'HLS 영상과 자동 생성 자막을 영속 저장하고 화질을 선택해 재생한다.',metadata:{category:'media'}}],null,2)};
 if(menu==='search')return {...result,collection:'noedaeri-test',prompt:'자동 자막과 영상 재생을 지원하는 기능',queryLimit:'5',similarity:'0'};
 throw new Error('이 메뉴에는 텍스트 샘플이 없습니다.');
}
export const fileSamples:Record<string,{title:string;text:string}>={
 subtitles:{title:'영상 자막 샘플',text:'음성이 있는 짧은 MP4를 선택하고 한국어·ITN 켜기로 테스트하세요. 이 기능은 SRT/VTT만 생성하며 영상에 입히지 않습니다.'},
 pdf:{title:'PDF 추출 샘플',text:'텍스트가 선택되는 PDF와 스캔 PDF를 각각 자동 모드로 비교하세요. 페이지별 text/ocr 방법과 추출 문장을 확인합니다.'},
 ocr:{title:'이미지 OCR 샘플',text:'흰 배경에 선명한 문장이 있는 PNG/JPEG를 선택하세요. 줄별 좌표와 confidence를 함께 확인합니다.'},
 stt:{title:'음성 인식 샘플',text:'아래 문장을 읽은 짧은 음성 파일을 선택하세요: 안녕하세요. 공통 플랫폼의 음성 인식을 테스트합니다. 구간 시각은 근삿값입니다.'},
 tts:{title:'음성 생성 샘플',text:'안녕하세요. 공통 플랫폼의 음성 생성 테스트입니다. 오늘도 좋은 하루 보내세요.'},
 voices:{title:'목소리 등록 샘플',text:'프리셋 Sohee를 먼저 등록하거나, 본인에게 사용 권한이 있는 3초 초과·30초 이하 음성과 실제 말한 대본을 준비하세요. 3초 이하의 참조 음성은 등록할 수 없습니다. 타인의 목소리를 무단으로 등록하지 마세요.'},
 thumbnail:{title:'영상 썸네일 샘플',text:'5초 이상의 MP4를 선택하고 추출 시점 1초로 확인하세요. HLS·음성 인식은 실행하지 않습니다.'},
 image:{title:'이미지 처리 샘플',text:'회전 정보나 투명 배경이 있는 이미지를 선택해 원본·JPEG 썸네일·WebP 미리보기를 비교하세요. 애니메이션은 첫 프레임만 처리합니다.'},
 video:{title:'영상 처리 샘플',text:'음성이 있는 짧은 MP4를 선택해 없음·자막 파일·영상에 입히기를 각각 새 요청으로 검수하세요. 업로드 시작 전 선택해야 하며 burned 자막은 재생 중 끌 수 없습니다.'},
};
