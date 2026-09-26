---
version: 1
slug: "apps-admin-web-src-developercenter-tsx"
primary_target: "apps/admin-web/src/features/developer/DeveloperCenter.tsx"
related_targets: ["apps/admin-web/src/features/developer/developer.css", "apps/admin-web/src/features/projects/ProjectFilesSettings.tsx", "apps/admin-web/src/features/files/FilePublicShare.tsx"]
---

# 파일 연동·개발자 센터

Mode: Read. 관리자 화면의 기존 색상·탭·입력·오류 안내를 그대로 사용한다. 프로젝트별 파일 사용 설정과 공개 공유 설정은 Operate 기능이다.

THESIS: 다른 프로젝트의 개발자가 파일 서비스 사용을 켜고 서버 키를 발급한 뒤 실행 예제로 업로드·재개·변환 상태·보기·삭제를 확인할 수 있다.
FIRST VIEWPORT: 독립 개발자 센터 메뉴, 빠른 시작/서버 연동 예제/API 명세 세 탭, 호스트 서버를 통한 업로드 흐름을 보여 준다. 프로젝트 상세 아래에 문서 전체를 쌓지 않는다.
SIGNATURE: 실제 서비스의 OpenAPI를 검색해 요청·응답 계약을 펼치고 내려받는다. 다운로드 가능한 Python 예제는 서버 환경변수로만 키를 사용한다.
STATES: 명세 로딩·실패·재조회·이전 결과·검색 0건, 파일 사용 중지 확인과 설정 복귀, 공유 metadata 저장·충돌·조회 재시도.
QUALITY BAR: 기존 SectionTabs/Dialog와 FileDetails를 유지하고 1440px/390px·양 테마에서 페이지 가로 넘침 없이 동작한다. 코드는 필요한 블록 내부에서만 스크롤한다. 공개 공유 페이지는 no-autoplay 파일 보기/다운로드 링크와 서버가 만든 OG를 제공한다.
SCOPE: 사용자별 업로드 권한과 위임 업로드는 사용자 결정으로 제외. 공개 OG 메타데이터만 파일 설정에서 관리하며 외부 메신저 캐시 회수는 보장하지 않는다. 에디터 구현은 다음 단계다.
