# 공통 아이콘 사용 기준

[사용자가 지정한 아이콘 목록](https://tools.shnea.kr/icons/)의 Lucide·Tabler 중 Lucide로 통일했다. `lucide-static@1.48.0`에서 필요한 SVG 노드만 선택해 로컬에 포함한다. 런타임 CDN·아이콘 폰트·전체 카탈로그 의존성은 추가하지 않는다.

## 표시와 의미

| 동작 | 아이콘 예 | 글자 |
| --- | --- | --- |
| 확대·축소·화면 맞춤 | zoom-in / zoom-out / scan | 한국어 접근성 이름·툴팁, 100%는 숫자 유지 |
| 원본·복사·다운로드 | external-link / copy / download | 문맥이 명확하면 아이콘만 |
| 새로고침·닫기 | refresh-cw / x | 한국어 접근성 이름·툴팁 |
| 저장·삭제·복구·권한 변경 | save / trash-2 / rotate-ccw / lock | 결과를 판단하도록 글자 유지 |
| 메뉴 탐색·기능 선택 | 기능에 맞는 아이콘 | 이름 유지 |
| 모바일 에디터 메뉴 열기 | layout-grid | ‘삽입 메뉴’, 하단 오른쪽 |
| 같은 미디어 줄·표 행/열 추가 | plus | 기존 접근성 설명 유지 |

기본 SVG는 20px, 2px 선, `currentColor`를 사용한다. 조작 영역은 최소 44px이며 SVG 자체는 `aria-hidden="true"`, `focusable="false"`로 둔다. 아이콘 전용 버튼의 `aria-label`·`title`은 한국어로 작성하고 호버와 키보드 포커스에 설명을 보여 준다. 같은 SVG라도 주변 문맥에 맞는 이름을 지정한다. 비활성·로딩·확인 대화상자와 기존 권한 동작을 유지한다.

## 코드 위치

- 공통 노드와 DOM 도우미: `packages/editor/src/icons/`, 공개 import `@shnea/editor/icons`
- 독립 아이콘 CSS: `@shnea/editor/icons.css`; 에디터 `style.css`에도 포함
- React: `apps/admin-web/src/shared/Icon.tsx`, 예: `<Icon name="download"/>`
- DOM: `decorateAction(button, 'zoom-in', '확대', true)` 또는 `createIcon(document, 'image')`
- 공개/비밀번호 공유 HTML: 서버 템플릿의 정적 inline SVG. 외부 이미지 요청 없이 기존 CSP 안에서 표시

새 아이콘은 동일한 고정 버전의 SVG를 확인한 뒤 노드만 추가한다. 사용자가 준 문자열이나 SVG를 직접 주입하지 않는다. 브라우저 기본 재생 조작, Keycloak의 기본 컨트롤, 소셜 제공자 로고는 해당 기능의 기존 표시를 따른다.

## 라이선스

[Lucide 공식 라이선스](https://lucide.dev/license)는 ISC이며 일부 Feather 기반 아이콘에 MIT 고지가 포함된다. 원문을 에디터 패키지의 `LICENSE-LUCIDE`와 파일 서비스의 `src/main/resources/META-INF/LICENSE-LUCIDE`에 포함했다. 패키지 또는 서비스를 전달할 때 함께 보존한다.
