# SHNEA 에디터 연동 지침

`@shnea/editor@0.1.0-alpha.9` · 문서 version 3 · 내부 검증용. React/Vue 연결은 선택 사항이며 일반 JS 번들에는 두 프레임워크가 들어 있지 않다. 본문 저장·인증·사용자 권한·저장 실패 처리는 호스트 서비스가 담당한다.

## 설치와 전달

패키지: https://platform.shnea.kr/integrations/shnea-editor-0.1.0-alpha.9.tgz

SHA-256: https://platform.shnea.kr/integrations/checksums.json

패키지를 내려받아 체크섬을 확인한 뒤 호스트 프로젝트에서 설치한다. 플랫폼 저장소는 필요 없다.

```sh
npm install ./shnea-editor-0.1.0-alpha.9.tgz
```

공개 npm 발행은 하지 않았다. `react` 또는 `vue`는 호스트가 설치한다. React 18~19, Vue 3.5를 대상으로 하며 이번 검증 버전은 React 19.3.0·Vue 3.5.43이다. 프레임워크별 실제 하위 버전과 모바일 기기는 호스트에서 추가 검수한다. 번들러가 있는 호스트는 `@shnea/editor/style.css`를 한 번 불러온다. SSR에서는 빈 컨테이너만 출력하고 클라이언트 마운트 후 편집기를 만든다.

## React

```tsx
import {useState} from 'react';
import {ShneaEditor, ShneaViewer} from '@shnea/editor/react';
import {emptyDocument, type EditorDocument} from '@shnea/editor';
import '@shnea/editor/style.css';

export function Article() {
  const [value, setValue] = useState<EditorDocument>(emptyDocument());
  return <>
    <ShneaEditor value={value} documentKey="article-1"
      onChange={({document}) => setValue(document)}
      onError={error => { /* 호스트 오류 UI에 표시 */ }} />
    <ShneaViewer value={value} />
  </>;
}
```

`onReady(editor | null)`로 현재 인스턴스를 받아 `getValue`, `setValue`, `undo`, `moveBlock` 등을 호출할 수 있다. 해제 시 null을 받으며 예전 인스턴스를 다시 사용하면 안 된다. `value`를 불변 객체로 교체한다. 변경 이벤트 값을 그대로 다시 전달해도 에디터를 재생성하거나 실행 취소 이력을 지우지 않는다. 콜백만 바꿔도 다음 이벤트에 최신 콜백을 사용한다. 오류 콜백을 생략하면 React 오류 처리 경로로 전달한다.

## Vue

```vue
<script setup lang="ts">
import {shallowRef} from 'vue';
import {ShneaEditor, ShneaViewer} from '@shnea/editor/vue';
import {emptyDocument} from '@shnea/editor';
import '@shnea/editor/style.css';
const value = shallowRef(emptyDocument());
</script>

<template>
  <ShneaEditor v-model="value" document-key="article-1"
    @error="error => { /* 호스트 오류 UI에 표시 */ }" />
  <ShneaViewer :value="value" />
</template>
```

`v-model`은 `modelValue`·`update:modelValue`를 사용한다. `change` 이벤트에는 `{document, origin}`을 전달하고 `ready`에는 인스턴스 또는 null을 전달한다. `error`를 연결해 입력 검증·첨부 오류를 표시한다. 문서와 업로드 어댑터는 `shallowRef` 또는 안정된 객체로 유지하는 편이 좋다.

## 공통 갱신 규칙

- `value`/`modelValue`는 문서 객체 또는 null이다. JSON 문자열은 호스트에서 `JSON.parse`한 후 전달한다. 유효하지 않은 새 문서는 오류로 알리고 현재 문서를 보존한다.
- 편집 이벤트의 같은 내용이 되돌아오면 교체하지 않는다. 다른 내용의 외부 값은 `setValue`처럼 교체하고 실행 취소 이력을 초기화한다. 비동기 불러오기 응답이 늦게 도착해 새 편집을 덮어쓰지 않도록 호스트가 문서 ID·요청 순서를 관리한다.
- 다른 게시글을 열 때 `documentKey`를 바꾼다. 내용이 같아도 새 문서로 취급하며 이전 실행 취소 이력·진행 중 업로드를 정리한다.
- `appearance` 변경은 같은 인스턴스에 적용한다. 읽기에도 같은 옵션을 전달한다. 모양은 JSON에 저장되지 않는다.
- `attachments` 객체·`editable`·`label` 변경은 인스턴스를 다시 만든다. 새 외부 문서가 없으면 현재 본문을 보존하지만 실행 취소 이력과 진행 중 업로드는 초기화된다. 어댑터를 매 렌더마다 새 객체로 만들지 않는다.
- 컴포넌트를 해제하면 이벤트·편집 엔진·미디어·진행 중 업로드를 정리한다. 본문과 이미 완료한 파일의 영구 삭제는 수행하지 않는다.
- `ShneaViewer`는 편집 도구 없이 렌더링한다. 같은 문서를 다시 전달하면 불필요한 재마운트를 생략한다. 내용이나 모양이 달라지면 뷰어를 교체하므로 재생 중 미디어 상태 보존을 보장하지 않는다.

## 일반 JS

받은 tgz를 풀어 `package/dist/browser` 폴더 전체를 호스트 정적 자산의 `browser` 폴더로 복사한다. React/Vue·CDN·import map 없이 사용한다. `editor.js`, `editor.css`, 라이선스 파일과 `THIRD-PARTY-NOTICES.txt`를 함께 배포한다.

```html
<link rel="stylesheet" href="./browser/editor.css">
<div id="editor"></div>
<div id="viewer"></div>
<script type="module">
import {mountEditor, renderViewer, parseDocument} from './browser/editor.js';
const appearance = {fontSize: 16, lineHeight: 1.7};
// 저장된 값은 호스트 API로 조회한 뒤 parseDocument로 검증한다.
const editor = mountEditor({element: document.querySelector('#editor'),
  value: parseDocument(null), appearance,
  onChange({document}) { /* 호스트 상태에 반영 */ }
});
const disposeViewer = renderViewer(document.querySelector('#viewer'), editor.getValue(), {appearance});
// 화면을 떠날 때 disposeViewer(); editor.destroy();
</script>
```

모듈은 HTTP(S)에서 제공한다. `file://`로 열지 않는다. 실행 예제의 일반 JS는 메모리 보관·다시 불러오기·해제/재연결·BFCache 복원을 보여 준다. 본문을 실제로 저장하려면 호스트 API를 연결한다. `pagehide` 이후 복원하지 않는 서비스라면 해당 화면 수명 주기에 맞춰 해제한다.

## JSP 실행

JSP도 위 일반 JS 코드를 사용한다. 받은 패키지의 browser 폴더를 JSP 옆 정적 경로에 두고 같은 상대 경로로 모듈과 CSS를 불러온다. JSP 서버(Tomcat 등)에서 HTTP(S)로 제공한다.

본문 JSON을 JSP의 script 문자열에 직접 삽입하지 않는다. 호스트의 인증된 JSON API로 조회하고 parseDocument로 검증한 뒤 편집기에 전달한다. 조회 실패 시 현재 문서를 보존한다. 동적 HTML 속성에는 호스트 템플릿의 이스케이프를 적용한다.

## 첨부 업로드 연결

네 방식 모두 같은 `attachments` 옵션을 받는다. 아래는 **호스트가 구현할 API 계약의 예**이며 플랫폼에 이 경로가 생기는 것은 아니다. 브라우저에는 서버 키를 전달하지 않는다.

```js
const attachments = {
  platformImageOrigin: 'https://platform.shnea.kr',
  scope: () => 'host-project-dev',
  async upload(file, context) {
    const body = new FormData();
    body.set('file', file);
    body.set('kind', context.kind);
    body.set('requestId', context.requestId);
    const response = await fetch('/api/editor/files', {
      method: 'POST', body, credentials: 'same-origin', signal: context.signal
      // 호스트 인증 방식에 맞는 CSRF 보호를 추가한다.
    });
    if (!response.ok) throw new Error('파일을 올리지 못했습니다. 다시 시도해 주세요.');
    return response.json(); // {fileId, scope, kind, name, size}
  },
  async resolve(file, signal) {
    const response = await fetch(`/api/editor/files/${encodeURIComponent(file.fileId)}/views`,
      {credentials: 'same-origin', signal});
    if (!response.ok) throw new Error('파일 보기 정보를 불러오지 못했습니다.');
    return response.json(); // AttachmentViews 계약에 맞는 상태·보기 URL
  }
};
```

호스트 서버가 프로젝트·환경·사용자 권한·용량·형식을 검사하고 플랫폼 파일 API를 호출한다. 같은 requestId 재시도는 중복 파일을 만들지 않도록 처리한다. 큰 파일은 기존 분할/재개 업로드 어댑터를 연결한다. 위 FormData 예제는 대용량 분할 전송을 구현하지 않는다. 영상은 호스트의 `attachments.video(element, data)`로 HLS 플레이어를 연결할 수 있고, 생략하면 `viewerUrl`의 기본 뷰어를 iframe으로 연다. 영상 변환·URL 접근 권한은 파일 서비스가 담당한다. 상세 타입은 받은 패키지의 `AttachmentAdapter`·`AttachmentRef`·`AttachmentViews` 선언을 사용한다.

현재 플랫폼 기본 뷰어는 `frame-ancestors 'self'`이므로 다른 도메인에서 iframe으로 바로 표시할 수 없으며 파일 API의 임의 출처 CORS도 제공하지 않는다. 새 탭의 기본 뷰어로 먼저 확인하고, 본문 내 재생은 호스트 플레이어와 인증된 같은 출처 중계 또는 별도로 합의한 허용 출처/임베드 정책이 필요하다. 위 기본 iframe 동작은 이 제한을 우회하지 않는다. 다른 서비스의 연결 계약은 https://platform.shnea.kr/integrations/SERVICE_INTEGRATION.md 에서 찾는다.

## 파일 보기 URL 연결 규칙

**설치·업데이트할 때 호스트의 `attachments.resolve` 응답과 파일 중계 경로를 함께 확인한다. 패키지만 교체해도 호스트 코드의 잘못된 URL 매핑은 그대로 남는다.**

**기본 연결(권장):** 위 `platformImageOrigin`을 한 번 설정한다. 호스트 서버는 파일 접근 권한 확인 후 `POST /api/v1/files/{id}/view-ticket`을 서버 키(`files:read`)로 호출하고 응답 JSON을 수정 없이 반환한다. 에디터가 이미지의 상대 URL을 플랫폼 주소로 해석하고 용도별 경로·파일 ID·출처를 검증한다. 같은 원본 주소로 덮어쓰면 명시적 연결 오류가 표시된다. 호스트에서 이미지 URL 조립이나 PC/모바일 분기를 구현하지 않는다.

```text
호스트의 파일 보기 API:
  1. 현재 사용자에게 해당 fileId를 보여줘도 되는지 확인
  2. 플랫폼 POST /api/v1/files/{id}/view-ticket 호출 (X-Platform-Key: 서버 키)
  3. 실패는 해당 오류로 처리, 성공 JSON은 변경 없이 반환 (Cache-Control: no-store)
```

이미지는 플랫폼에서 직접 읽으므로 호스트의 CSP `img-src`에 플랫폼 원점을 허용한다. 보호 파일의 임시 토큰은 원래 권한·만료를 유지한다. 플랫폼/호스트 API 키를 브라우저에 넣지 않는다. 이 옵션은 **이미지 블록에만** 적용하며 영상 HLS·문서 중계와 기존 사용자 정의 어댑터를 바꾸지 않는다. 파일 서비스의 임의 출처 API CORS를 여는 기능이 아니므로 보기 정보 조회는 계속 호스트 서버를 거친다.

| 응답 필드 | 용도 | 플랫폼 경로 |
| --- | --- | --- |
| `thumbnailUrl` | 모바일 본문(600px 이하) | `/api/v1/files/{id}/content/thumbnail` |
| `previewUrl` | PC 본문(601px 이상)·모든 화면의 클릭 확대창 | `/api/v1/files/{id}/content/preview` |
| `originalUrl` | 확대창 안의 원본 보기 | `/api/v1/files/{id}/content/original` |
| `viewerUrl` | 독립 파일 보기 화면 | `/api/v1/files/{id}/view` |
| `downloadUrl` | 다운로드 | `/api/v1/files/{id}/content/download` |

- 표는 경로 구분이다. 파일 ID로 URL을 임의 생성하지 말고 플랫폼 보기 API가 반환한 URL·상태·만료 정보를 사용한다. URL의 토큰 쿼리를 보존하고, 제공되지 않은 URL의 `null`도 유지한다. 임시 URL을 본문 JSON에 저장하지 않는다.
- 기본 연결은 에디터가 플랫폼 상대 URL을 절대 URL로 변환한다. 블로그 주소를 기준으로 해석하지 않는다. 보호 파일의 링크는 호스트에서 권한을 확인한 뒤 발급·전달하고 서버 API 키는 브라우저에 보내지 않는다.
- 같은 출처 중계를 직접 구현해야 하는 경우에만 `platformImageOrigin`을 생략하고 호스트 주소를 전달한다. **PC 본문 → preview, 모바일 본문 → thumbnail, 확대창 → preview, 원본 버튼 → original** 구분을 서버까지 전달한다. 예를 들어 호스트가 `/api/files/{id}/content?variant=thumbnail`을 구현했다면 해당 요청을 플랫폼의 `/content/thumbnail`로 중계한다. 주소에 variant만 붙이고 서버가 계속 원본을 내려주면 수정된 것이 아니다. 중계 서버는 허용한 variant와 사용자·파일 접근 권한을 검사한다.
- `thumbnailUrl`, `previewUrl`, `originalUrl`, `viewerUrl`을 전부 `/api/files/{id}/content` 하나로 채우지 않는다. 에디터는 `thumbnailUrl`을 사용해도 실제 주소가 원본이면 원본을 받는다.
- 기존 이미지의 별도 미리보기가 없을 때 원본으로 대체하는 것은 **플랫폼의 preview 응답 내부 처리**다. 호스트가 `previewUrl`을 `originalUrl`로 덮어쓰거나 썸네일까지 원본으로 대체할 이유가 아니다.
- 이미지 블록은 자체 확대창을 사용하므로 `viewerUrl`로 이미지를 대신 로드하지 않는다. 기본 뷰어는 독립 화면용이며 영상의 기본 iframe 사용에는 위 출처 제한이 적용된다.

**완료 기준:** 실제 호스트의 편집/읽기 화면에서 캐시를 끄고 Network 요청을 확인한다. 처음에는 PC에서 preview만, 모바일에서 thumbnail만 요청해야 한다. 클릭하면 두 화면 모두 preview를 표시하고 원본 버튼을 누르면 original을 요청한다. 창 너비를 600px↔601px로 바꿔 본문 선택이 전환되는지도 확인한다. 호스트 중계라면 실제 플랫폼 요청 대상도 확인하고 응답의 형식·크기가 해당 파일과 일치하는지 검사한다. 기존 파일의 preview는 생성 전 원본일 수 있다. 합성 URL·패키지 테스트만으로 호스트 연결까지 완료됐다고 보고하지 않는다.

## 검수와 공식 참고

예제는 운영 API·인증·실제 파일 업로드를 호출하지 않는다. 사용 프로젝트에서는 Markdown 붙여넣기 → JSON 저장/조회 → 읽기와 재편집, 업로드 취소·만료 URL·권한 실패, 여러 인스턴스와 화면 해제, 실제 모바일 키보드·한글 IME·Safari를 검수한다.

연결 수명 주기와 양방향 값 전달은 [React Effect](https://react.dev/reference/react/useEffect), [Vue 수명 주기](https://vuejs.org/api/composition-api-lifecycle.html), [Vue v-model](https://vuejs.org/guide/components/v-model.html)에 따른다. 정적 번들은 [esbuild 번들링](https://esbuild.github.io/api/#bundle)을 사용한다.

이미지 조회 어댑터는 `thumbnailUrl`(모바일 본문), `previewUrl`(PC 본문·클릭 미리보기), `originalUrl`(미리보기 안의 원본 보기)을 각각 전달한다. 미리보기가 준비되지 않았으면 PC도 썸네일을 표시한다. URL을 같은 주소로 바꾸지 않으면 화면별 선택은 에디터가 처리하므로 호스트에서 기기를 판별하지 않는다. 미리보기가 없는 기존 이미지의 원본 대체와 후속 생성은 파일 서비스가 처리한다. `@shnea/editor/image-viewer`의 `previewUrl` 생략 시 `src`를 확대하며, 명시적 null은 준비 중으로 클릭을 막는다. 독립 공통 뷰어의 `desktopSrc`는 선택 사항이며, 에디터 이미지 블록이 PC용 미리보기 주소를 전달한다.
