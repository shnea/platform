# SHNEA 로고

민트색 바탕과 짙은 녹색의 리본형 `S`, 둥근 소문자 워드마크를 사용한다. 대각선으로 자른 두 끝과 곡선의 대비가 심볼의 특징이다.

- `shnea-mark.svg`: 64 × 64 심볼. 관리자 헤더·파비콘에 사용한다.
- `shnea-wordmark.svg`: 소문자 `shnea` 윤곽선. 관리자 화면에서 CSS mask로 적용해 밝은·어두운 테마의 글자색을 따른다.
- `shnea-logo.svg`: 218 × 64 가로 조합. 어두운 배경용이며 Keycloak 로그인 헤더에 사용한다.

심볼은 `#B0E1C7` 바탕, `#123B2C` 리본을 유지한다. 최소 권장 크기는 심볼 24px, 조합형 136px이며 파비콘은 16px까지 축소한다. 가로세로 비율을 유지하고 효과·테두리를 덧붙이지 않는다.

워드마크는 [Manrope](https://github.com/google/fonts/tree/main/ofl/manrope)의 800 굵기를 윤곽선으로 변환하고 자간을 조정했다. 글꼴 설치나 외부 요청 없이 표시된다. 라이선스는 `Manrope-OFL.txt`에 보관한다. 심볼은 직접 작성한 SVG 경로다.

관리자와 Keycloak은 Docker 빌드 경로가 분리되어 있다. 공용 심볼·조합형을 변경하면 `infra/keycloak/themes/platform/login/resources/img/`의 같은 이름 파일에도 반영한다.
