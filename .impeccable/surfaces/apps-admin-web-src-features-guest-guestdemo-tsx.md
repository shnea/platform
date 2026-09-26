---
version: 1
slug: "apps-admin-web-src-features-guest-guestdemo-tsx"
primary_target: "apps/admin-web/src/features/guest/GuestDemo.tsx"
related_targets: ["apps/admin-web/src/features/guest/guest.css", "apps/admin-web/src/features/guest/samples.ts"]
---

# 공개 체험

Mode: Operate. 기존 SHNEA Platform 관리자 디자인의 테마·서체·에디터를 재사용하는 공개 체험 화면이다.
THESIS: 방문자가 로그인 없이 직접 편집하고 샘플 이미지·영상·오디오·파일의 보기 기능을 확인한다.
OWN-WORLD: 기존 녹회색·민트, 어두운 기본 화면과 밝은 테마 전환. 운영 메뉴와 데이터는 포함하지 않는다. 독립 정적 진입점이며 관리자 인증·API 모듈을 가져오지 않는다.
FIRST VIEWPORT: 브랜드·관리자 진입·테마, 짧은 체험 안내와 메모리 유지 안내, 편집/읽기·샘플 버튼과 실제 문서.
FORM: 한 열의 체험 문서. 제목과 소개만 데스크톱 2열, 모바일 1열. 별도 새 시각 세계·마케팅 시안이 아닌 기존 제품 기능의 확장이다.
STORY: 예제 직접 편집 → 샘플 추가 → 이미지 확대/영상 수동 재생·화질·구간 이동 → 읽기 확인 → JSON 내려받기 또는 초기화.
STATES: 업로드 미지원 한국어 안내, 확인 후 초기화, 미디어 오류, 키보드 포커스, 모바일 전체 너비. 본문·인증 정보 영속 저장 없음.
QUALITY BAR: 양 테마 1440/390px, 샘플 실재·정적 출처, 자동 재생 없음, 운영 API/인증 요청 0, 익명 관리자 API 거부 유지.
