# 공통 에디터·뷰어

## 현재 제공 범위

`packages/editor`의 문서 계약·편집 코어와 공통 슬래시 UI를 제공한다. 관리자 **에디터** 메뉴에서 편집/읽기를 체험할 수 있다. 전체 에디터 출시 완료는 아니다. Tiptap/ProseMirror 기반의 독립 ES 모듈이며 기존 `blocknote_shnea` 소스와 패키지는 사용하지 않았다.

| 항목 | 현재 상태 |
| --- | --- |
| 버전 있는 JSON·구조 검증·초기화·변경 이벤트 | 구현 |
| 편집·읽기 공통 스키마, 다중 인스턴스·자원 해제 | 구현 |
| Markdown 제목·서식·표·중첩 목록·체크리스트·코드·링크·이미지 참조 | 구현·DOM 자동 검증 |
| 종류별 슬래시 메뉴·서식·표 직접 조작·붙여넣기 선택·양 테마 | 구현·관리자 체험 제공 |
| 최상위 블록 드래그·위/아래 이동, 호스트 모양 설정 API·체험 UI | 구현·브라우저 검증 |
| 파일 식별자 기반 네 종류 첨부·업로드·붙여넣기·드롭 | 구현·관리자 파일 서비스 연결 |
| 이미지 한 줄 1~3개·옆에 추가·개별 삭제·모바일 세로 배치 | 구현 |
| 변환 상태 갱신·본문 내 HLS·오디오·공통 이미지 확대 | 구현 |
| React/Vue 연결 컴포넌트·일반 JS/JSP 정적 번들·실행 예제 | 구현·Chromium 및 Tomcat 실행 검증 |
| 실제 한글 IME·휴대폰 키보드·스크린리더·OS 클립보드 | 미검증; Chromium 자동 입력·붙여넣기 이벤트·390px 화면은 검증 |

첫 출시의 전체 요구사항은 [REQUIREMENTS.md의 F-11](REQUIREMENTS.md#블록-에디터뷰어--f-11)을 유지한다.

## 버전과 문서

내부 패키지는 `@shnea/editor@0.1.0-alpha.9`, 문서 버전은 `3`다. version 1·2 문서를 입력하면 구조를 보존해 3으로 반환한다. version 2의 imageRow는 mediaRow로 이전한다. 첨부 노드는 2부터 허용하며 이전 패키지는 새 문서를 거부한다. 알파 단계이며 외부 운영 도입을 권장하는 배포본은 아니다. 문서 형식과 패키지 버전은 별개다.

```json
{
  "format": "shnea-editor",
  "version": 3,
  "content": {
    "type": "doc",
    "content": [{"type": "paragraph", "content": [{"type": "text", "text": "본문"}]}]
  }
}
```

`parseDocument(value)`는 입력을 검증하고 기본 속성을 채운 별도 JSON을 반환한다. `null`·`undefined`만 빈 문단으로 초기화한다. 문자열·잘못된 구조·알 수 없는 노드/속성은 `DOCUMENT_INVALID`, 지원하지 않는 버전은 `DOCUMENT_VERSION_UNSUPPORTED`로 거부한다. 자동으로 빈 문서로 바꿔 원본을 잃지 않는다.

입력 검증 한도는 노드 20,000개·중첩 깊이 64·본문/속성 문자열 합계 2,000,000자, 개별 속성 문자열 10,000자다. 이는 5GB 파일 업로드 한도와 다르며 첨부 원본을 본문에 넣는 기준이 아니다. 편집 중 실시간 용량 제한 UI는 아직 없다. 호스트는 저장 API에서도 본문 크기·문서 버전·소유권을 검증해야 한다.

링크는 HTTP(S)·mailto·tel만 허용하고 속성 주입·실행 URL을 거부한다. 새 창 링크의 rel을 제거할 수 없다. 외부 이미지에는 절대 HTTP(S) URL만 실제 src로 사용하며 상대 경로는 문서에 보존하고 경로 확인 안내로 표시한다. data/blob/javascript 이미지 속성은 거부한다. 이 외부 이미지 노드는 Markdown 참조용이다. 업로드 첨부는 별도 `attachment` 노드로 저장한다.

## 코어 API

공통 UI 대신 코어만 연결할 수도 있다. `mountEditor`는 아래 코어의 입출력·해제 API를 그대로 제공한다.

| API | 계약 |
| --- | --- |
| `createEditorCore({element,value,editable,label,attachments,onChange,onError})` | 전용 DOM 컨테이너에 편집 코어 생성. editable 기본 true, 한국어 본문 라벨 |
| `getValue()` | 현재 버전 문서의 별도 JSON 반환 |
| `setValue(value,{emitChange:false})` | 검증 성공 시 교체하고 실행 취소 이력 초기화. 잘못된 값이면 기존 문서 보존 |
| `onChange({document,origin})` | 편집은 edit, 명시적으로 알림을 켠 값 교체는 replace. 저장 성공을 뜻하지 않음 |
| `insertMarkdown(text)` / `insertText(text)` | 현재 선택 위치 삽입. 읽기 전용이면 거부 |
| `undo()` / `redo()` / `focus()` | 현재 인스턴스에만 적용 |
| `moveBlock(fromIndex,toIndex)` | 0부터 시작하는 최상위 블록 순서를 변경. 같은 위치·범위 밖은 false, 읽기 전용은 거부. 한 번에 실행 취소 |
| `captureSelection()` | 외부 UI로 포커스를 옮기기 전에 현재 에디터의 DOM 선택을 엔진에 반영. 다른 에디터의 선택은 가져오지 않음 |
| `run(command,payload?)` / `can(command)` / `isActive(command)` | 현재 선택 위치에서 서식·블록·표·실행 취소 명령 실행/가능 여부/적용 상태 |
| `onMarkdownPaste(source)` / `setPasteMode(mode)` | 코어의 Markdown 선택 UI 연결 또는 원문/변환 모드. 공통 UI는 선택 팝업을 직접 제공 |
| `destroy()` | 엔진·이벤트 자원 해제. 반복 호출 가능, 이후 값 접근은 EDITOR_DESTROYED |
| `renderViewer(element,value,{attachments,appearance})` | 같은 스키마로 읽기 전용 article 생성, 해제 함수 반환. 체크박스는 비활성 |
| `fromMarkdown(text)` | Markdown을 버전 문서로 변환. 서버 저장·파일 업로드 없음 |

같은 컨테이너에 두 인스턴스를 마운트하면 `EDITOR_MOUNTED`로 거부한다. 두 인스턴스의 문서·이벤트·실행 취소는 독립적이다. 외부 값 교체는 기본적으로 변경 이벤트를 발생시키지 않아 호스트의 양방향 바인딩 반복을 막는다. 본문 저장·저장 실패·동시 수정 충돌·사용자 권한은 호스트가 처리한다.

## Markdown·붙여넣기 정책

굵게·기울임은 편집/읽기 본문에서 `font-synthesis: weight style`과 명시적 굵기·기울기를 적용한다. 사이트 전역의 `font-synthesis: none`이 한글 대체 글꼴의 서식 표시를 막던 문제를 본문 범위에서 수정했다. 전역 스타일은 변경하지 않는다. [CSS font-synthesis](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/font-synthesis)

Markdown 변환은 markdown-it의 토큰에서 스키마 JSON을 직접 만든다. HTML 문자열을 페이지에 주입하지 않는다. 제목·문단·굵게/기울임/취소선·인라인 코드·인용·구분선·순서/비순서/중첩 목록·체크리스트·표/정렬·코드 언어/들여쓰기·링크·이미지 참조를 지원한다. 혼합 체크/일반 목록은 연속된 같은 종류끼리 나눠 보존한다.

붙여넣기는 `text/markdown` → `text/plain` 순서이며 HTML 표현은 사용하지 않는다. HTML만 있는 클립보드는 삽입하지 않는다. 웹 페이지의 풍부한 HTML 서식 변환은 이 단계에 포함하지 않는다. 공통 UI는 지원 블록·글자 서식이 발견되면 삽입 전에 **원문 그대로 / Markdown 서식 적용 / 취소**를 제공한다. 일반 텍스트와 코드 블록 안은 바로 붙인다. 변환은 한 번의 실행 취소 단위다. 코어만 사용할 때는 기본 자동 변환이며 `onMarkdownPaste(source)`로 호스트가 선택 UI를 연결할 수 있다. `setPasteMode('text'|'markdown')`은 코어의 원문 모드 설정이다.

원시 HTML·수식 등의 미지원 문법은 텍스트로 보존하며 스크립트를 실행하지 않는다. 상대 링크는 경로를 텍스트로 남기고 자동 이동시키지 않는다. 원본 PC의 상대 이미지 파일을 자동 업로드하지 않는다. 외부 이미지 로딩 실패 UI와 파일 연결은 후속 단계다. 코드블록의 마지막 문법상 줄바꿈은 제거하고 내부 줄바꿈·공백을 보존한다.

## 블록 이동과 호스트 모양 설정

공통 UI 하단 왼쪽은 현재 선택한 최상위 블록의 위치와 드래그 손잡이·위/아래 버튼을 제공한다. 본문에서 옮길 블록을 선택하고 손잡이를 드래그하면 삽입 위치를 표시한다. 모바일·키보드는 위/아래 버튼으로 같은 동작을 수행한다. 읽기 화면에는 편집 도구를 표시하지 않는다. 표·목록·미디어 묶음은 통째로 이동하며 내부 행·항목을 개별 드래그하는 기능은 아직 없다.

이동은 노드·서식·첨부 ID를 보존하는 한 번의 실행 취소 단위다. 업로드 중 이동해도 완료 파일을 원래 ID에 연결하며, 완료 후 이동을 취소해도 파일 연결을 유지한다. 드래그 도중 본문이 변경되면 이동을 취소하고 다시 선택하도록 안내한다. 완료 메타데이터는 문서 교체·해제 시 비우며 원본 바이트를 보관하지 않는다. 이동으로 영상 뷰어가 다시 마운트될 수 있으므로 재생 상태 유지를 보장하지 않는다.

```js
import {mountEditor} from '@shnea/editor/ui';
import {renderViewer} from '@shnea/editor';
import '@shnea/editor/style.css';

const appearance = {fontSize: 18, lineHeight: 1.7, paragraphSpacing: 16, radius: 8};
const editor = mountEditor({element: editorElement, value, appearance});
editor.moveBlock(0, 2); // 첫 최상위 블록을 세 번째로 이동
editor.setAppearance(appearance); // 설정을 바꿀 때도 편집/읽기에 같은 값을 전달
const disposeViewer = renderViewer(viewerElement, editor.getValue(), {appearance});
editor.setAppearance(); // 호스트 기본 모양으로 복원
// 화면 해제 시 disposeViewer(); editor.destroy();
```

`appearance`는 `mountEditor`와 `renderViewer`의 선택 옵션이다. `setAppearance`는 공통 UI 인스턴스에서 제공하며 전체 설정을 교체한다. 생략한 속성은 기본값으로 돌아가고, 잘못된 값은 일부 적용 없이 오류를 발생시킨다. 이미 만든 읽기 뷰어의 모양을 변경하려면 해제 후 새 설정으로 다시 렌더링한다. 모양만 바꿔도 본문·변경 이벤트·실행 취소 이력은 바뀌지 않으며 JSON 입출력에는 포함되지 않는다.

| 속성 | 허용 값 |
| --- | --- |
| `fontFamily` | 글꼴·대체 글꼴 문자열, 1~200자. `;`, 중괄호, 줄바꿈 제외. 글꼴은 호스트가 제공하며 자동 다운로드 없음 |
| `fontSize` | 12~32px |
| `lineHeight` | 1.2~2.4배 |
| `paragraphSpacing` | 0~48px |
| `contentPadding` | 8~64px. 모바일 실제 여백은 최대 20px |
| `radius` | 0~24px |
| `colors` | `background`, `text`, `muted`, `border`, `accent`, `raised`의 선택 값. 각각 `#RRGGBB` |

관리자·게스트의 접힌 **에디터 모양 설정**에서 편집/읽기에 같은 옵션을 전달한다. 모양은 페이지 메모리에서만 유지한다. 코어만 사용하는 호스트는 자체 스타일/UI를 연결한다.

## 호스트 적용과 검증

### React·Vue·일반 JS·JSP 연결

설치와 코드 예제는 패키지에 동봉되는 [INTEGRATION.md](../packages/editor/INTEGRATION.md)를 따른다. 관리자 **개발자 센터 → 에디터 연동**에서 지침을 내려받고 [공개 실행 예제](https://platform.shnea.kr/examples/editor/)를 열 수 있다. 실제 운영 데이터·인증·파일 업로드는 예제에 연결하지 않는다.

`@shnea/editor/react`·`@shnea/editor/vue`는 각각 `ShneaEditor`, `ShneaViewer`를 제공하며 같은 코어와 공통 UI를 사용한다. React는 `value`·`onChange`, Vue는 `v-model`로 문서를 주고받는다. 편집 이벤트가 되돌아와도 같은 문서는 교체하지 않으므로 실행 취소·진행 중 업로드를 유지한다. 다른 게시글에는 `documentKey`를 변경하고, 어댑터 객체는 안정적으로 유지한다. 해제 시 엔진·이벤트·진행 중 작업을 정리한다.

`npm run build`는 React/Vue가 들어 있지 않은 `dist/browser/editor.js`·CSS·라이선스 고지를 생성한다. `npm run example:build`는 세 정적 실행 예제와 `dist/jsp`를 생성한다. JSP는 같은 번들을 로딩하며 본문 JSON을 HTML에 직접 끼워 넣지 않는다. 프레임워크·브라우저 하위 버전과 실제 호스트의 저장/권한/첨부는 별도 검수한다.

### 공통 UI와 관리자 체험

```js
import {mountEditor} from '@shnea/editor/ui';
import '@shnea/editor/style.css';

const editor = mountEditor({element, value, onChange});
// editor.getValue(), editor.setValue(value), editor.destroy()
```

상단 문단 선택·도구막대는 없다. `/`는 **전체·본문·목록·글자 서식·표·첨부·편집** 탭과 검색 목록을 연다. `/table`은 표 삽입, `/h2`는 제목 2, `/bold` 또는 `/굵게`는 글자 서식이다. 검색은 종류 탭과 관계없이 전체에서 찾는다. 글자를 선택하고 `/`를 입력해도 선택을 유지한다. 목록의 ↑↓·Enter, 탭의 ←→·Home/End, Esc 닫기, 화면 경계 안의 메뉴 배치와 현재 위치 사용 불가 안내를 제공한다. 빈 위치에서 문자 `/` 자체를 넣으려면 `//`를 입력한다. URL 중간과 코드 블록은 슬래시 메뉴를 열지 않는다.

표 셀은 긴 문장·공백 없는 URL도 셀 너비 안에서 여러 줄로 표시한다. 코드 블록 자체는 가로 스크롤을 유지한다. 표의 오른쪽 `+`는 끝 열, 아래 `+`는 끝 행을 추가한다. 선택 셀의 행·열 번호와 삭제 버튼은 해당 표에 있다. 가장자리 버튼의 마지막 행/열 삭제는 막고 전체 삭제는 `/deletetable`로 구분한다. 이 조작 UI는 읽기 뷰어와 JSON에 포함하지 않는다.

관리자 로그인 → **에디터**에서 편집/읽기·예제·비우기·JSON 보기/다운로드/입력을 체험한다. 글 편집에는 프로젝트 선택이 필요 없다. 실제 첨부는 접힌 ‘첨부 저장 위치’에서 파일 서비스가 켜진 프로젝트와 준비된 환경을 선택한다. 메뉴 이동 시 메모리 문서를 유지하지만 새로고침·로그아웃하면 사라진다. 본문을 서버나 브라우저 저장소에 저장하지 않는다. 문서 교체에는 확인과 실행 취소 이력 초기화 안내가 있다. 에디터 코드는 메뉴 진입 시 지연 로딩한다.

모바일(600px 이하)은 편집 영역 하단 오른쪽의 격자 아이콘·**삽입 메뉴**로도 같은 기능을 연다. 메뉴는 화면 아래에 배치하며 이미지·영상·파일·오디오·표를 먼저 보여 준다. 버튼으로 열 때 검색창에 자동 포커스를 주지 않고, 필요할 때 검색창을 눌러 입력한다. 원래 커서·선택 범위를 유지하고 닫기는 버튼으로 포커스를 돌려준다. 읽기 화면과 데스크톱에는 이 하단 버튼을 표시하지 않는다. 미디어 옆 `+`는 같은 줄에 추가하는 별도 기능이다.

이미지 Ctrl+V 업로드·드롭 위치 파일 삽입·재시도/취소·파일 ID·본문 내 HLS를 구현했다. 실제 한글 IME·휴대폰 키보드·스크린리더·운영체제 클립보드는 별도 검수가 남아 있다. 네이티브 앱은 웹뷰 연결이 가능하지만 사진 선택·키보드·앱 수명주기 연동은 아직 검증하지 않았다.

네 종류 첨부는 아래처럼 제공한다. 모두 공통 업로드 계약과 파일 ID를 사용하고 붙여넣기·드롭의 실제 삽입 위치와 순서를 보존한다.

| 명령 | 본문 표시·동작 |
| --- | --- |
| `/file` | `[test.txt]` 형태의 파일명·용량·작은 형식 표시. 지원 파일은 미리보기, 원본 다운로드 제공 |
| `/image` | 업로드 완료 후 썸네일·미리보기 표시. 클릭 확대·원본 보기·다운로드 |
| `/video` | 변환 상태 → 썸네일·재생 버튼. 자동 재생 없이 본문 내 HLS·화질 선택·시간 이동·전체 화면 |
| `/audio` | 파일명과 오디오 플레이어. 자동 재생 없이 재생·일시정지·시간 이동·음량 조절, 가능한 경우 재생 시간 표시 |

일반 파일 항목은 파일명·용량 중심으로 표시한다. 이미지·영상에는 카드 외곽·파일명·용량·상시 작업 목록을 두지 않는다. 이미지는 클릭 확대, 영상은 썸네일 위 재생 버튼으로 조작한다. 미디어 줄은 `mediaRow`의 `id`와 1~3개의 이미지/영상 attachment를 저장한다. version 2의 `imageRow`는 이미지 전용 구조를 검증한 뒤 이전한다. 첨부의 `widthPercent`는 정수 25~100(기본 100), `align`은 left/center/right(기본 center)이다. 단일 미디어에 적용하며 데스크톱 최소 표시 너비는 본문 범위 안의 160px다. 혼합 줄은 균등 분배한다. 모바일(600px 이하)은 전체 너비로 세로 표시하며 JSON의 너비·정렬·줄 구성은 보존한다. 편집 시 ‘추가 → 이미지 추가/영상 추가’와 개별 제거를 이미지 위에 표시하고 3개면 추가 버튼을 숨긴다. 마지막 미디어를 제거하면 빈 줄도 제거한다. 미지원 미디어는 원본 다운로드로 안내한다. 비공개 접근·삭제·URL 만료와 업로드 실패는 종류와 관계없이 해당 블록에서 구분한다.

이미지 확대는 **공통 이미지 뷰어**로 만들고 파일 서비스 기본 뷰어와 에디터 편집/읽기에서 재사용한다. 돋보기 확대·축소, 화면 맞춤, 실제 크기(100%), 확대 후 드래그 이동과 모바일 핀치 확대가 기본 범위다. 본문 썸네일 클릭으로 열며 닫기·키보드 조작·원본 보기/다운로드도 제공한다. 관리자 파일 상세와 기본 이미지 뷰어에도 같은 구현을 사용한다.

현재는 npm/ES 모듈 번들러로 연결하는 코어다. 사용 예는 [패키지 README](../packages/editor/README.md)에 있다. `npm pack`으로 고정 버전 tarball을 만들 수 있지만 공개 레지스트리에는 발행하지 않았다. `private: true`로 실수로 발행하는 것을 막는다. React/Vue 런타임을 필수 의존성으로 추가하지 않았다.

```sh
cd packages/editor
npm ci
npm test
npm pack --dry-run
```

자동 검증은 jsdom에서 문서 검증·보존·왕복·악성 입력·인스턴스 분리·실행 취소·붙여넣기·뷰어 읽기 전용 동작을 확인한다. jsdom 테스트를 실제 브라우저·IME·모바일 검수로 간주하지 않는다. 개발 관리자·파일 서비스에 반영하며 운영 배포는 별도다.

엔진/의존성은 Tiptap 3.31.3(MIT), markdown-it 15.0.2(MIT)를 고정했다. Tiptap의 공식 Markdown 확장은 베타로 안내되어 이번 코어에서는 채택하지 않았다. 의존성 목록과 전이 버전은 패키지 lock 파일로 보존한다.

근거: [Tiptap 일반 JS 연동](https://tiptap.dev/docs/editor/getting-started/install/vanilla-javascript), [Tiptap Markdown 상태](https://tiptap.dev/docs/editor/markdown), [markdown-it](https://github.com/markdown-it/markdown-it).

## 첨부 연결 계약

호스트가 `AttachmentAdapter`를 만들어 `mountEditor({attachments})`와 `renderViewer(element,value,{attachments})`에 동일하게 전달한다. 패키지는 서버 키나 관리자 로그인을 포함하지 않는다.

```ts
import type {AttachmentAdapter} from '@shnea/editor';
const attachments: AttachmentAdapter = {
  scope: () => selectedEnvironment, // 새 업로드의 저장 범위. 미선택이면 undefined
  upload: (file, context) => hostUpload(file, context),
  resolve: (reference, signal) => hostResolve(reference, signal),
  // 선택: HLS 플레이어를 마운트하고 update(data) / destroy()를 반환한다.
  video: (element, data) => hostVideo(element, data)
};
```

- `upload(file,{scope,kind,requestId,signal,progress})`는 `{fileId,scope,kind,name,size}`를 반환한다. requestId는 동일 첨부 재시도에서 유지하며 호스트 서버는 내용 해시·환경까지 확인해 멱등 처리한다. 진행은 `progress(0..100,한국어안내)`로 알린다. 범위는 삽입 시 고정하므로 화면 선택을 바꿔도 진행 중 파일을 다른 환경으로 보내지 않는다.
- 한 인스턴스에서 순서대로 전송하고 최대 20개를 대기한다. 각 파일은 5GB 이하이다. 업로드 중 편집해도 블록 ID로 완료 위치를 찾는다. 취소·삭제·문서 교체·해제는 AbortSignal로 전송을 중단한다. 파일 선택부터 이어 올리려면 호스트 업로드의 멱등 계약을 사용한다. 관리자 구현은 SHA-256 확인과 기존 분할 업로드를 재사용한다.
- `resolve(reference,signal)`는 현재 권한의 보기 정보를 반환한다. `fileId,kind,state,originalUrl,downloadUrl,viewerUrl,previewUrl,thumbnailUrl,expiresAt`과 선택적인 `video,streamUrl,streamExpiresAt`을 사용한다. 변환 중은 5초 간격, 만료 주소는 만료 10초 전에 다시 조회한다. 실패하면 링크와 미디어를 숨기고 다시 조회를 제공한다. 영상 update는 재생 위치를 유지하도록 연결한다. video 어댑터가 없으면 기본 뷰어 iframe을 사용한다.
- 문서에는 `attachment`의 `id,fileId,scope,kind,name,size`와 이미지·영상의 `widthPercent,align`을 저장한다. URL·토큰·원본 바이트는 저장하지 않는다. 아직 업로드하지 못한 블록은 빈 fileId를 가지며 다시 열면 같은 원본 재선택을 요구한다. 호스트 저장 시 미완료 첨부의 허용 여부를 결정한다.
- JSON의 scope/fileId는 권한 증명이 아니다. 호스트 서버는 로그인 사용자·문서 소유권·파일 접근 권한을 매 요청마다 확인하고 서버 API 키를 브라우저에 보내지 않는다. 관리자 어댑터는 기존 관리자 JWT와 환경별 파일 API를 사용한다.
- 문서에서 제거·실행 취소해도 완료 파일은 삭제하지 않는다. 다른 문서가 참조할 수 있으므로 호스트의 보존/삭제 정책이 담당한다. 관리자 체험은 공개·default 보존으로 실제 저장하며 파일 메뉴에서 관리한다. 전송 중 취소는 알고 있는 미완료 세션을 취소하고, 응답 유실로 세션 ID를 받지 못하면 서버 세션 만료 정리에 맡긴다.

공통 이미지 뷰어는 `@shnea/editor/image-viewer`의 `mountImageViewer(element,{src,originalUrl,downloadUrl,name,onError})`로 단독 사용하고 공통 CSS를 로드한다. 반환한 해제 함수를 호출한다. 화면 맞춤·100%·돋보기, 키보드 +/-·0·1·방향키, 드래그·핀치와 Esc 닫기를 제공한다. 만료/접근 권한은 호스트가 새 URL을 전달해 처리한다. 원본이 바뀌면 확대 상태를 초기화한다.

크기 조절 손잡이는 드래그와 좌우 방향키(5% 단위), Home(25%), End(100%)를 지원한다. 드래그는 한 번의 실행 취소로 복원하며 Escape·포인터 취소 시 저장하지 않는다. 너비·정렬 변경은 재생 중인 영상 인스턴스를 다시 만들지 않는다. 모바일과 2~3개 묶음에서는 개별 크기·정렬 도구를 숨긴다.


## 저장된 첨부 삽입과 공개 체험

`editor.insertAttachment({fileId,scope,kind,name,size})`는 호스트가 이미 저장한 첨부를 현재 위치에 넣는다. 종류·필드 검증 후 새 블록 ID를 만들고 실행 취소를 지원한다. 새 파일 업로드를 수행하지 않으며 보기 URL은 동일한 `attachments.resolve`가 제공한다. scope/fileId는 권한 증명이 아니다.

`/demo.html`은 인증 모듈 없이 정적 샘플 어댑터만 사용하는 체험이다. 선택한 샘플 ID·종류·범위가 허용 목록과 같을 때만 고정된 공개 URL을 제공한다. 작성 내용은 메모리에만 있고 JSON 내려받기와 확인 후 초기화를 지원한다. 실제 파일 업로드는 관리자 체험에서 확인한다.

소스 위치와 공개 패키지 진입점은 [소스 구조](SOURCE_LAYOUT.md)를 참고한다.
