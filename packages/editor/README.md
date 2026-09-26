# SHNEA 에디터·뷰어

`@shnea/editor@0.1.0-alpha.2` — 편집 엔진·문서 계약·공통 슬래시 UI의 내부 검증용 패키지다. 파일·영상 연결 등이 남아 있어 첫 출시 전체 완료는 아니다. 공개 레지스트리에 발행하지 않았으며 `private: true`를 유지한다.

## 실행

```sh
npm ci
npm test
npm run build
```

브라우저 번들러에서 사용한다. React·Vue에 종속되지 않는다. `dist`는 ES 모듈과 타입 선언이며 의존성을 포함한 일반 JS/JSP용 단일 번들은 아직 제공하지 않는다.

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

관리자 **에디터** 메뉴에서 편집/읽기·예제·JSON 입출력을 확인할 수 있다. 메뉴 간 이동은 메모리 문서를 유지하지만 새로고침·로그아웃하면 사라진다. 이미지 Ctrl+V 업로드·파일 드롭 위치 삽입·HLS는 후속 파일 연결 단계다.

상세 계약·현재 한계·다음 단계: 저장소의 [docs/EDITOR.md](../../docs/EDITOR.md). tarball을 전달할 때는 이 문서도 같이 제공한다.
