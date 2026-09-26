package kr.shnea.platform.file;

final class FileFailure extends RuntimeException {
    final String code;
    final int status;
    FileFailure(String code, int status, String detail) { super(detail); this.code = code; this.status = status; }
    static FileFailure missing() { return new FileFailure("FILE_NOT_FOUND", 404, "파일 또는 업로드 세션을 찾을 수 없습니다."); }
    static FileFailure invalid() { return new FileFailure("INVALID_REQUEST", 400, "파일 이름·크기·해시와 입력 조건을 확인해 주세요."); }
    static FileFailure unavailable() { return new FileFailure("FILE_SERVICE_UNAVAILABLE", 503, "파일 처리를 확인하지 못했습니다. 상태를 조회한 뒤 다시 시도해 주세요."); }
}
