# 프런트엔드 소스 구조

기능을 찾기 쉽도록 관리자와 에디터 소스를 역할별로 나눴다. 새 화면은 관련 기능 폴더에 추가하고 여러 기능에서 실제로 공유하는 코드만 shared에 둔다.

| 경로 | 역할 |
| --- | --- |
| `apps/admin-web/src/main.tsx`, `app/main.tsx` | 관리자 시작점과 메뉴·페이지 연결 |
| `apps/admin-web/src/features/` | projects, accounts, testing, jobs, monitoring, alerts, files, editor, developer, guest 화면 |
| `apps/admin-web/src/shared/` | 인증, 오류, 공통 대화상자·탭 |
| `apps/admin-web/src/shared/Icon.tsx` | 공통 Lucide 노드의 React 표시 |
| `apps/admin-web/src/shared/media/` | 이미지·영상과 에디터의 공통 재생 연결 |
| `apps/admin-web/src/styles/` | 관리자 기본 테마·레이아웃 |
| `apps/admin-web/demo.html` | 인증 모듈과 분리된 공개 체험 시작점 |
| `apps/admin-web/public/assets/demo/` | 직접 제작한 공개 샘플과 제작 기록 |
| `packages/editor/src/index.ts` | 호스트가 사용하는 코어 API |
| `packages/editor/src/document/` | 문서 계약·검증·이전·Markdown·스키마 |
| `packages/editor/src/editing/` | 슬래시·붙여넣기 UI, 표 조작과 최상위 블록 이동 |
| `packages/editor/src/media/` | 첨부 업로드·조회·미디어 줄·크기 조절 |
| `packages/editor/src/viewer/` | 공통 이미지 확대 뷰어 |
| `packages/editor/src/icons/` | 선택한 Lucide SVG 노드·DOM 생성·조작 버튼 스타일 |
| `packages/editor/src/styles/` | 에디터·읽기 공통 CSS와 호스트 모양 설정 검증·적용 |

호스트는 내부 폴더를 직접 import하지 않고 `@shnea/editor`, `@shnea/editor/ui`, `@shnea/editor/style.css`, `@shnea/editor/image-viewer`를 사용한다. 내부 이동 후에도 이 진입점 이름은 유지한다.

파일 서비스 서버 등 백엔드 구조는 이번 프런트엔드 정리의 변경 대상이 아니다.
