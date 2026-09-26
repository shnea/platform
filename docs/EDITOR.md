# 공통 에디터·뷰어

## 현재 제공 범위

첫 작업 단위는 `packages/editor`의 문서 계약과 편집 코어다. 전체 에디터 출시 완료가 아니다. Tiptap/ProseMirror를 기반으로 독립 ES 모듈을 구성했으며 관리자 앱의 React·인증·서버와 연결하지 않았다. 기존 `blocknote_shnea` 소스와 패키지는 사용하지 않았다.

| 항목 | 현재 상태 |
| --- | --- |
| 버전 있는 JSON·구조 검증·초기화·변경 이벤트 | 구현 |
| 편집·읽기 공통 스키마, 다중 인스턴스·자원 해제 | 구현 |
| Markdown 제목·서식·표·중첩 목록·체크리스트·코드·링크·이미지 참조 | 구현·DOM 자동 검증 |
| 도구막대·슬래시 메뉴·드래그 이동·글꼴·테마 | 다음 UI 작업 |
| 파일 식별자 기반 첨부·업로드 연결·이미지 두 열 배치 | 다음 파일 블록 작업 |
| 변환 상태 갱신·본문 내 HLS 영상 뷰어 | 다음 영상 블록 작업 |
| React/Vue 연결 컴포넌트·일반 JS/JSP 정적 번들·실행 예제 | 미구현 |
| 실제 한글 IME·모바일 편집·스크린리더·클립보드 검수 | 미검증 |

첫 출시의 전체 요구사항은 [REQUIREMENTS.md의 F-11](REQUIREMENTS.md#블록-에디터뷰어--f-11)을 유지한다.

## 버전과 문서

내부 패키지는 `@shnea/editor@0.1.0-alpha.1`, 문서 버전은 `1`이다. 알파 단계이며 외부 운영 도입을 권장하는 배포본은 아니다. 이후 파일·배치 노드 추가 시 기존 소비자가 새 노드를 무시하지 않도록 문서 버전과 호환 범위를 다시 정한다. 문서 형식 변경과 패키지 버전 변경은 별개다.

```json
{
  "format": "shnea-editor",
  "version": 1,
  "content": {
    "type": "doc",
    "content": [{"type": "paragraph", "content": [{"type": "text", "text": "본문"}]}]
  }
}
```

`parseDocument(value)`는 입력을 검증하고 기본 속성을 채운 별도 JSON을 반환한다. `null`·`undefined`만 빈 문단으로 초기화한다. 문자열·잘못된 구조·알 수 없는 노드/속성은 `DOCUMENT_INVALID`, 지원하지 않는 버전은 `DOCUMENT_VERSION_UNSUPPORTED`로 거부한다. 자동으로 빈 문서로 바꿔 원본을 잃지 않는다.

입력 검증 한도는 노드 20,000개·중첩 깊이 64·본문/속성 문자열 합계 2,000,000자, 개별 속성 문자열 10,000자다. 이는 5GB 파일 업로드 한도와 다르며 첨부 원본을 본문에 넣는 기준이 아니다. 편집 중 실시간 용량 제한 UI는 아직 없다. 호스트는 저장 API에서도 본문 크기·문서 버전·소유권을 검증해야 한다.

링크는 HTTP(S)·mailto·tel만 허용하고 속성 주입·실행 URL을 거부한다. 새 창 링크의 rel을 제거할 수 없다. 외부 이미지에는 절대 HTTP(S) URL만 실제 src로 사용하며 상대 경로는 문서에 보존하고 경로 확인 안내로 표시한다. data/blob/javascript 이미지 속성은 거부한다. 이 외부 이미지 노드는 Markdown 참조용이다. 플랫폼 첨부의 안정적인 파일 ID 노드는 다음 단계에서 별도로 추가한다.

## 코어 API

| API | 계약 |
| --- | --- |
| `createEditorCore({element,value,editable,label,onChange,onError})` | 전용 DOM 컨테이너에 편집 코어 생성. editable 기본 true, 한국어 본문 라벨 |
| `getValue()` | 현재 버전 문서의 별도 JSON 반환 |
| `setValue(value,{emitChange:false})` | 검증 성공 시 교체하고 실행 취소 이력 초기화. 잘못된 값이면 기존 문서 보존 |
| `onChange({document,origin})` | 편집은 edit, 명시적으로 알림을 켠 값 교체는 replace. 저장 성공을 뜻하지 않음 |
| `insertMarkdown(text)` / `insertText(text)` | 현재 선택 위치 삽입. 읽기 전용이면 거부 |
| `undo()` / `redo()` / `focus()` | 현재 인스턴스에만 적용 |
| `destroy()` | 엔진·이벤트 자원 해제. 반복 호출 가능, 이후 값 접근은 EDITOR_DESTROYED |
| `renderViewer(element,value)` | 같은 스키마로 읽기 전용 article 생성, 해제 함수 반환. 체크박스는 비활성 |
| `fromMarkdown(text)` | Markdown을 버전 문서로 변환. 서버 저장·파일 업로드 없음 |

같은 컨테이너에 두 인스턴스를 마운트하면 `EDITOR_MOUNTED`로 거부한다. 두 인스턴스의 문서·이벤트·실행 취소는 독립적이다. 외부 값 교체는 기본적으로 변경 이벤트를 발생시키지 않아 호스트의 양방향 바인딩 반복을 막는다. 본문 저장·저장 실패·동시 수정 충돌·사용자 권한은 호스트가 처리한다.

## Markdown·붙여넣기 정책

Markdown 변환은 markdown-it의 토큰에서 스키마 JSON을 직접 만든다. HTML 문자열을 페이지에 주입하지 않는다. 제목·문단·굵게/기울임/취소선·인라인 코드·인용·구분선·순서/비순서/중첩 목록·체크리스트·표/정렬·코드 언어/들여쓰기·링크·이미지 참조를 지원한다. 혼합 체크/일반 목록은 연속된 같은 종류끼리 나눠 보존한다.

현재 붙여넣기는 `text/markdown` → `text/plain` 순서이며 HTML 표현은 사용하지 않는다. HTML만 있는 클립보드는 삽입하지 않는다. 웹 페이지의 풍부한 HTML 서식 변환은 이 단계에 포함하지 않는다. 일반 붙여넣기를 한 번의 실행 취소 단위로 처리하고 코드블록 안에서는 원문을 넣는다. `insertText()`는 호스트가 원문 삽입 동작을 연결할 때 쓴다. 사용자용 원문 붙여넣기 버튼/단축키는 UI 단계에서 연결한다.

원시 HTML·수식 등의 미지원 문법은 텍스트로 보존하며 스크립트를 실행하지 않는다. 상대 링크는 경로를 텍스트로 남기고 자동 이동시키지 않는다. 원본 PC의 상대 이미지 파일을 자동 업로드하지 않는다. 외부 이미지 로딩 실패 UI와 파일 연결은 후속 단계다. 코드블록의 마지막 문법상 줄바꿈은 제거하고 내부 줄바꿈·공백을 보존한다.

## 호스트 적용과 검증

현재는 npm/ES 모듈 번들러로 연결하는 코어다. 사용 예는 [패키지 README](../packages/editor/README.md)에 있다. `npm pack`으로 고정 버전 tarball을 만들 수 있지만 공개 레지스트리에는 발행하지 않았다. `private: true`로 실수로 발행하는 것을 막는다. React/Vue 런타임을 필수 의존성으로 추가하지 않았다.

```sh
cd packages/editor
npm ci
npm test
npm pack --dry-run
```

자동 검증은 jsdom에서 문서 검증·보존·왕복·악성 입력·인스턴스 분리·실행 취소·붙여넣기·뷰어 읽기 전용 동작을 확인한다. jsdom 테스트를 실제 브라우저·IME·모바일 검수로 간주하지 않는다. 운영 서비스와 관리자 화면은 이 단계에서 변경하거나 배포하지 않는다.

엔진/의존성은 Tiptap 3.31.3(MIT), markdown-it 15.0.2(MIT)를 고정했다. Tiptap의 공식 Markdown 확장은 베타로 안내되어 이번 코어에서는 채택하지 않았다. 의존성 목록과 전이 버전은 패키지 lock 파일로 보존한다.

근거: [Tiptap 일반 JS 연동](https://tiptap.dev/docs/editor/getting-started/install/vanilla-javascript), [Tiptap Markdown 상태](https://tiptap.dev/docs/editor/markdown), [markdown-it](https://github.com/markdown-it/markdown-it).
