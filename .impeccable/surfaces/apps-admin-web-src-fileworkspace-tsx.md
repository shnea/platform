---
version: 1
slug: "apps-admin-web-src-fileworkspace-tsx"
primary_target: "apps/admin-web/src/FileWorkspace.tsx"
related_targets: ["apps/admin-web/src/files.css","apps/admin-web/src/file-api.ts"]
---

# 관리자 파일 작업 공간

Mode: Operate. 기존 관리자 화면의 파일 기능 확장. 파일 목록과 업로드·재개를 독립 메뉴 안에서 구분한다. 사용자는 기능을 프로젝트 상세 아래에 계속 쌓지 않고 접근하기 쉬운 메뉴를 요구했다.

## Direction contract

THESIS: 선택한 프로젝트·환경의 파일 관리와 중단 업로드 복귀를 한 메뉴에서 수행한다. 프로젝트 상세의 긴 기능 나열을 늘리지 않는다.

OWN-WORLD: 기존 어두운 녹색·민트와 밝은 테마, 시스템 글꼴, 가는 구분선, 기본 버튼·선택·대화상자를 그대로 사용한다.

STORY: 범위를 확인하고 파일 목록 또는 업로드 탭을 고른다. 파일별 진행 상태와 실패 이유를 읽고 같은 원본을 선택해 이어 올린다. 공개 범위·삭제는 대상과 영향을 확인한다.

FIRST VIEWPORT: 기존 메뉴·페이지 제목·프로젝트/환경 선택 아래에 두 탭을 둔다. 목록은 파일명 중심 행, 업로드는 파일 선택 영역과 파일별 진행률이다. 모바일에서 행의 조작부가 아래로 이동한다.

FORM: 기존 운영 화면 확장, code-led. 순위·seed·새 시각 방향은 해당 없음. 별도 래스터 생성 없음.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## QUALITY BAR

- 기존 관리자 탐색·색상·테마를 유지하고 기능별 이동과 현재 범위를 분명히 표시한다.
- 업로드 진행·일시정지·원본 필요·오류·완료를 텍스트와 실제 수치로 구분한다.
- 긴 한글 파일명, 빈 목록, 실패/재조회, 삭제 확인, 키보드 조작과 390px 모바일을 검증한다.
- 파일 전체를 브라우저 메모리에 올리지 않으며 관리자 권한과 환경 격리를 유지한다.
- 미구현 썸네일·미리보기·공유·보존 관리를 완료로 표시하지 않는다.
