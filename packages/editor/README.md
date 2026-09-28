# SHNEA 에디터·뷰어

`@shnea/editor@0.1.0-alpha.10` — 편집 엔진·문서 계약·공통 UI와 React/Vue 연결, 일반 JS/JSP 번들을 제공하는 내부 검증용 패키지다. 실제 호스트 인증·저장·업로드 및 모바일 검수가 남아 있어 첫 출시 전체 완료는 아니다. 공개 레지스트리에 발행하지 않았으며 `private: true`를 유지한다.

## 실행

```sh
npm ci
npm test
npm run build
npm run example:build
```

번들러에서는 코어·UI·선택적 `@shnea/editor/react`·`@shnea/editor/vue`를 사용한다. 프레임워크 없이 쓰려면 `dist/browser` 전체를 정적 자산으로 복사한다. 이 ES 모듈 번들은 편집 의존성을 포함하고 React/Vue·CDN은 필요 없다. 라이선스·고지 파일을 함께 배포한다. [연동 지침](INTEGRATION.md)에 설치, 값 갱신, 수명 주기, 첨부 교체와 JSP 실행을 정리했다. `example:build`는 React/Vue/JS 예제와 Tomcat용 `dist/jsp`를 생성한다.

```js
import {createEditorCore, fromMarkdown, renderViewer} from '@shnea/editor';

const editor = createEditorCore({
  element: document.querySelector('#editor'),
  value: fromMarkdown('# 문서\n\n첫 문단'),
  onChange({document: value, origin}) {
    // 호스트의 미저장 상태를 표시한다. 저장 시점·권한·오류 처리는 호스트가 결정한다.
    console.log(origin); // 본문이나 키를 로그에 남기지 않는다.
  },
  onError(error) { console.error(error.code, error.message); }
});

const value = editor.getValue(); // 호스트 저장 API에 전달할 JSON
editor.setValue(value);         // 저장된 다른 문서 불러오기, 실행 취소 이력 초기화
const disposeViewer = renderViewer(document.querySelector('#viewer'), value);

// 호스트 화면이 사라질 때
disposeViewer();
editor.destroy();
```

`element`에는 전용 빈 컨테이너를 전달한다. 문서 본문을 API·localStorage·IndexedDB 등에 저장하거나 플랫폼 인증을 수행하지 않는다.

공통 UI는 `import {mountEditor} from '@shnea/editor/ui'`와 `import '@shnea/editor/style.css'`로 연결한다. `createEditorCore`와 같은 입출력·해제 API를 제공한다. 상단 도구막대 없이 `/`로 전체 목록·종류 탭을 열고 `/table`·`/h2`·`/bold` 등을 검색한다. 선택한 글자의 서식 적용, 표 가장자리 행·열 추가와 선택 삭제, Markdown 붙여넣기 시 원문/서식 선택을 제공한다. Ctrl/Cmd+Z 등 엔진의 기본 단축키도 유지한다.

관리자 **에디터** 메뉴에서 편집/읽기·예제·JSON 입출력을 확인할 수 있다. 메뉴 간 이동은 메모리 문서를 유지하지만 새로고침·로그아웃하면 사라진다. 첨부 저장 위치를 고른 뒤 /file·/image·/video·/audio, 이미지 Ctrl+V와 파일 드롭으로 실제 업로드를 체험한다. 이미지는 공통 돋보기 뷰어, 영상은 HLS, 오디오는 본문 플레이어를 사용한다. 본문은 저장하지 않지만 완료 파일은 파일 서비스에 남는다. 문서 version 1·2는 3으로 이전하며 첨부 ID·환경만 저장하고 임시 URL은 제외한다. 호스트의 `AttachmentAdapter`와 접근 권한 처리는 상세 계약을 따른다.

상세 계약·현재 한계·다음 단계: 저장소의 [docs/EDITOR.md](../../docs/EDITOR.md). tarball을 전달할 때는 이 문서도 같이 제공한다.

공통 UI의 하단 왼쪽에서 선택한 최상위 블록을 드래그하거나 위·아래로 이동한다. `editor.moveBlock(0, 2)`는 첫 블록을 세 번째로 옮긴다. 표·목록·미디어 묶음은 통째로 이동하며 실행 취소를 지원한다. `mountEditor({element, appearance: {fontSize: 18, lineHeight: 1.7}})`와 `editor.setAppearance(...)`로 모양을 지정하고, 읽기에는 `renderViewer(element, value, {appearance})`로 같은 설정을 전달한다. 설정은 본문 JSON에 저장하지 않는다.

모바일 600px 이하는 하단 오른쪽의 격자 아이콘·삽입 메뉴로도 기능을 연다. 원래 커서와 선택 글자를 유지하며 이미지·영상·파일·오디오·표를 먼저 보여 준다. /도 계속 지원한다. 공통 아이콘은 로컬 Lucide SVG이며 @shnea/editor/icons, @shnea/editor/icons.css로 재사용한다. 배포 시 LICENSE-LUCIDE를 보존한다.
