"""Python 3.11+ 서버용 파일 API 예제. 표준 라이브러리만 사용합니다.

서버 환경변수 PLATFORM_URL / PLATFORM_API_KEY 설정 후:
  python file-client.py upload ./sample.mp4 --state ./upload-state.json --wait 120
  python file-client.py views FILE_ID
  python file-client.py delete FILE_ID

호스트 서버에서 사용자 권한을 확인한 다음 Client.upload()를 호출하세요.
서버 키는 브라우저로 보내지 않습니다. --state는 서버 내부에 보관하세요.
"""
import argparse
import hashlib
import json
import os
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # 키를 다른 주소로 전달하지 않습니다.


class Client:
    def __init__(self, base=None, key=None):
        self.base = (base or os.environ['PLATFORM_URL']).rstrip('/')
        self.key = key or os.environ['PLATFORM_API_KEY']
        url = urlsplit(self.base)
        if url.username or url.password or url.query or url.fragment or url.path not in ('', '/'):
            raise ValueError('PLATFORM_URL에는 경로·계정·쿼리 없는 플랫폼 주소를 지정하세요.')
        if url.scheme != 'https' and not (url.scheme == 'http' and url.hostname in ('localhost', '127.0.0.1', '::1')):
            raise ValueError('HTTPS 주소를 사용하세요. 로컬 개발 주소만 HTTP를 허용합니다.')
        self.http = build_opener(NoRedirect())

    def call(self, method, path, body=None, headers=None):
        hdr = {'X-Platform-Key': self.key, **(headers or {})}
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body).encode()
            hdr['Content-Type'] = 'application/json'
        try:
            with self.http.open(Request(self.base+'/api/v1/files'+path, data=body, headers=hdr, method=method), timeout=330) as response:
                data = response.read()
                return json.loads(data) if data else None
        except HTTPError as error:
            try:
                problem = json.loads(error.read())
            except (ValueError, UnicodeError):
                problem = {}
            raise RuntimeError(f"HTTP {error.code}: {problem.get('code', 'HTTP_ERROR')} · {problem.get('detail', '요청을 확인해 주세요.')} · 요청 ID {problem.get('requestId', error.headers.get('X-Request-Id', '-'))}") from None

    def upload(self, source, state_path, visibility='PUBLIC', retention='default', video_options=None):
        source, state_path = Path(source), Path(state_path)
        size = source.stat().st_size
        if size > 5_000_000_000:
            raise ValueError('파일은 최대 5,000,000,000바이트입니다.')
        with source.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        identity = dict(originalName=source.name, size=size, sha256=digest, visibility=visibility, retentionCode=retention)
        if video_options is not None:
            if source.suffix.lower() not in ('.mp4', '.m4v', '.mov', '.mkv', '.webm'):
                raise ValueError('영상 자막 옵션은 지원 영상 파일에만 지정하세요.')
            identity['videoOptions'] = video_options
        credential = hashlib.sha256(self.key.encode()).hexdigest()
        if state_path.exists():
            state = json.loads(state_path.read_text(encoding='utf-8'))
            if state['file'] != identity or state['base'] != self.base or state.get('credential') != credential:
                raise ValueError('재개 기록과 파일·플랫폼·공개 범위·서버 키가 다릅니다. 새 기록 경로를 지정하세요.')
        else:
            state = dict(base=self.base, file=identity, credential=credential, requestId=str(uuid.uuid4()))
            # 실패한 최초 요청도 동일 ID로 다시 조회합니다. 키는 기록하지 않습니다.
            with state_path.open('x', encoding='utf-8') as output:
                json.dump(state, output, ensure_ascii=False)
        upload = self.call('POST', '/uploads', dict(identity, requestId=state['requestId']))
        endpoint = '/uploads/'+upload['uploadId']
        upload = self.call('GET', endpoint)
        if upload['state'] not in ('UPLOADING', 'READY'):
            raise ValueError('만료·취소된 업로드입니다. 새 기록 경로로 시작하세요.')
        if upload['state'] == 'UPLOADING':
            with source.open('rb') as stream:
                offset = upload['receivedBytes']
                stream.seek(offset)
                while offset < size:
                    chunk = stream.read(min(upload['maxChunkBytes'], 8*1024*1024, size-offset))
                    if not chunk:
                        raise ValueError('전송 중 원본 파일이 변경되었습니다.')
                    result = self.call('PATCH', endpoint, chunk, {'Content-Type':'application/octet-stream', 'Upload-Offset':str(offset), 'X-Chunk-SHA256':hashlib.sha256(chunk).hexdigest()})
                    offset = result['receivedBytes']
            # 응답 유실 시 같은 --state로 재실행: 수신 위치를 조회한 뒤 완료 요청을 재시도합니다.
        return self.call('POST', endpoint+'/complete')

    def views(self, file_id, wait=0):
        file_id = str(uuid.UUID(file_id))
        end = time.monotonic()+wait
        while True:
            info = self.call('GET', '/'+file_id+'/views')
            pending = info['state'] in ('QUEUED', 'PROCESSING') or (info.get('video') or {}).get('state') in ('QUEUED', 'PROCESSING')
            if not pending or time.monotonic() >= end:
                break
            time.sleep(min(3, max(0, end-time.monotonic())))
        return self.call('POST', '/'+file_id+'/view-ticket')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    upload = commands.add_parser('upload'); upload.add_argument('source'); upload.add_argument('--state', required=True)
    upload.add_argument('--visibility', choices=['PUBLIC', 'PRIVATE'], default='PUBLIC'); upload.add_argument('--retention', default='default'); upload.add_argument('--wait', type=int, default=0)
    upload.add_argument('--subtitles', choices=['none', 'sidecar', 'burned'], default='none')
    upload.add_argument('--subtitle-language', choices=['auto', 'ko', 'en', 'ja', 'zh', 'yue'], default='auto')
    upload.add_argument('--no-itn', action='store_true')
    views = commands.add_parser('views'); views.add_argument('file_id'); views.add_argument('--wait', type=int, default=0)
    delete = commands.add_parser('delete'); delete.add_argument('file_id')
    args = parser.parse_args()
    if getattr(args, 'wait', 0) < 0 or getattr(args, 'wait', 0) > 3600:
        parser.error('--wait는 0~3600초로 지정하세요.')
    try:
        client = Client()
        if args.command == 'delete':
            client.call('DELETE', '/'+str(uuid.UUID(args.file_id))); print('삭제 완료'); return
        if args.command == 'upload':
            video_options = None if args.subtitles == 'none' else dict(seconds=0, subtitles=dict(mode=args.subtitles, language=args.subtitle_language, useItn=not args.no_itn))
            result = client.upload(args.source, args.state, args.visibility, args.retention, video_options)
            file_id = result['fileId']
            print('업로드 완료 · 파일 ID: '+file_id)
        else:
            file_id = args.file_id
        print(json.dumps(client.views(file_id, args.wait), ensure_ascii=False, indent=2))
    except (KeyError, ValueError, OSError, RuntimeError) as error:
        parser.exit(1, str(error)+'\n')


if __name__ == '__main__':
    main()
