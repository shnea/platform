# SHNEA 에디터 코어

`@shnea/editor@0.1.0-alpha.1` — 편집 엔진·문서 계약의 내부 검증용 패키지다. 완성된 에디터 UI나 첫 출시 버전이 아니다. 공개 레지스트리에 발행하지 않았으며 `private: true`를 유지한다.

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

`element`에는 전용 빈 컨테이너를 전달한다. 화면·글꼴·스타일·도구막대는 다음 단계에서 제공한다. 현재 기본 엔진의 키보드 편집과 Markdown 붙여넣기를 사용할 수 있다. 문서 본문을 API·localStorage·IndexedDB 등에 저장하거나 플랫폼 인증을 수행하지 않는다.

상세 계약·현재 한계·다음 단계: 저장소의 [docs/EDITOR.md](../../docs/EDITOR.md). tarball을 전달할 때는 이 문서도 같이 제공한다.
