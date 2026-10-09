"""Platform server AI client. Python 3.11+, no login dependency or automatic retry."""
import argparse
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self):
        self.base = os.environ.get('PLATFORM_URL', '').rstrip('/')
        self.key = os.environ.get('PLATFORM_API_KEY', '')
        url = urllib.parse.urlsplit(self.base)
        if (not url.hostname or url.username or url.password or url.query or url.fragment or url.path
                or url.scheme != 'https' and not (url.scheme == 'http' and url.hostname in {'localhost', '127.0.0.1', '::1'})):
            raise ValueError('PLATFORM_URL에 HTTPS 원점 또는 로컬 개발 주소를 설정하세요.')
        if not self.key:
            raise ValueError('서버 환경변수 PLATFORM_API_KEY를 설정하세요.')
        self.http = urllib.request.build_opener(NoRedirect())

    def call(self, path, body=None):
        data = None if body is None else json.dumps(body, ensure_ascii=False, allow_nan=False).encode('utf-8')
        limit = 2 * 1024 * 1024 if path == '/api/v1/ai/jobs' else 64 * 1024 if path.endswith('/route') else 1024 * 1024
        if data is not None and len(data) > limit:
            raise ValueError('AI 입력 본문 한도를 초과했습니다.')
        request = urllib.request.Request(self.base + path, data=data, method='GET' if body is None else 'POST',
            headers={'X-Platform-Key': self.key, 'Content-Type': 'application/json', 'Accept': 'application/json'})
        try:
            with self.http.open(request, timeout=120) as response:
                payload = response.read(16 * 1024 * 1024 + 1)
                if len(payload) > 16 * 1024 * 1024:
                    raise RuntimeError('AI 응답 크기 한도를 초과했습니다.')
                return json.loads(payload)
        except urllib.error.HTTPError as error:
            # Do not echo provider/problem bodies, headers, URL or submitted text.
            raise RuntimeError(f'플랫폼 AI 요청 실패: HTTP {error.code}. 권한·입력·서버 상태를 확인하세요. 자동 재시도하지 않습니다.') from None
        except (urllib.error.URLError, TimeoutError):
            raise RuntimeError('플랫폼 AI 연결을 확인하지 못했습니다. 자동 재시도하지 않습니다.') from None

    def services(self):
        return self.call('/api/v1/ai/services')

    def embeddings(self, text, dimensions=768):
        return self.call('/api/v1/ai/embeddings', {'input': text, 'dimensions': dimensions})

    def route(self, task_type, prompt, instruction='', has_images=False):
        return self.call('/api/v1/ai/raya/route', {'task_type': task_type, 'prompt': prompt,
            'instruction': instruction, 'has_images': has_images})

    def submit(self, request):
        # Persist request_id with the host operation. Never generate a new ID to retry uncertain execution.
        return self.call('/api/v1/ai/jobs', request)

    def jobs(self, status=None, limit=50):
        query = {'limit': limit}
        if status is not None:
            query['status'] = status
        return self.call('/api/v1/ai/jobs?' + urllib.parse.urlencode(query))

    def job(self, job_id):
        return self.call('/api/v1/ai/jobs/' + str(uuid.UUID(job_id)))

    def cancel(self, job_id):
        return self.call('/api/v1/ai/jobs/' + str(uuid.UUID(job_id)) + '/cancel', {})

    def translate(self, request_id, text, target_language, source_language='auto', notify=False):
        return self.call('/api/v1/translations', {'request_id': request_id, 'text': text,
            'source_language': source_language, 'target_language': target_language, 'notify': notify})

    def events(self, limit=50):
        return self.call('/api/v1/ai/events?' + urllib.parse.urlencode({'limit': limit}))

    def receipt(self, job_id, event_id):
        return self.call('/api/v1/ai/jobs/' + str(uuid.UUID(job_id)) + '/receipt', {'event_id': str(uuid.UUID(event_id))})

    def usage(self, task_type=None, limit=50):
        query = {'limit': limit}
        if task_type is not None:
            query['task_type'] = task_type
        return self.call('/api/v1/ai/usage?' + urllib.parse.urlencode(query))


def read(path, limit):
    with Path(path).open('rb') as file:
        value = file.read(limit + 1)
    if len(value) > limit:
        raise ValueError('입력 파일 크기 한도를 초과했습니다.')
    return value.decode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('services')
    embed = commands.add_parser('embed')
    embed.add_argument('--input', required=True, help='JSON 문자열 또는 배열 파일')
    embed.add_argument('--dimensions', type=int, default=768)
    route = commands.add_parser('route')
    route.add_argument('--task-type', required=True)
    route.add_argument('--prompt', required=True, help='UTF-8 텍스트 파일')
    route.add_argument('--instruction', help='UTF-8 작업 지침 파일')
    route.add_argument('--has-images', action='store_true')
    submit = commands.add_parser('submit')
    submit.add_argument('--input', required=True, help='고정 request_id·task_type·prompt·input·sync를 포함한 JSON 파일')
    listing = commands.add_parser('jobs')
    listing.add_argument('--status', choices=['pending', 'running', 'succeeded', 'failed', 'cancelled'])
    listing.add_argument('--limit', type=int, default=50)
    for name in ['job', 'cancel']:
        commands.add_parser(name).add_argument('--id', required=True)
    translate = commands.add_parser('translate')
    translate.add_argument('--request-id', required=True)
    translate.add_argument('--text', required=True, help='UTF-8 텍스트 파일, 최대 4000자')
    translate.add_argument('--source-language', default='auto', choices=['auto', 'ko', 'en', 'ja', 'zh', 'es', 'fr', 'de'])
    translate.add_argument('--target-language', required=True, choices=['ko', 'en', 'ja', 'zh', 'es', 'fr', 'de'])
    translate.add_argument('--notify', action='store_true')
    events = commands.add_parser('events')
    events.add_argument('--limit', type=int, default=50)
    receipt = commands.add_parser('receipt', help='호스트의 영속 저장·업무 반영 완료 후에만 실행')
    receipt.add_argument('--id', required=True)
    receipt.add_argument('--event-id', required=True)
    usage = commands.add_parser('usage')
    usage.add_argument('--task-type')
    usage.add_argument('--limit', type=int, default=50)
    args = parser.parse_args()
    try:
        client = Client()
        if args.command == 'services':
            result = client.services()
        elif args.command == 'embed':
            response = client.embeddings(json.loads(read(args.input, 1024 * 1024)), args.dimensions)
            result = {name: response.get(name) for name in ('model', 'dimensions', 'usage')}
            result['count'] = len(response['data'])
            # Import Client.embeddings() in the host server to consume actual vectors.
        elif args.command == 'route':
            result = client.route(args.task_type, read(args.prompt, 64 * 1024),
                read(args.instruction, 64 * 1024) if args.instruction else '', args.has_images)
        elif args.command == 'submit':
            result = client.submit(json.loads(read(args.input, 2 * 1024 * 1024)))
        elif args.command == 'jobs':
            result = client.jobs(args.status, args.limit)
        elif args.command == 'job':
            result = client.job(args.id)
        elif args.command == 'cancel':
            result = client.cancel(args.id)
        elif args.command == 'translate':
            result = client.translate(args.request_id, read(args.text, 64 * 1024), args.target_language, args.source_language, args.notify)
        elif args.command == 'events':
            result = client.events(args.limit)
        elif args.command == 'receipt':
            result = client.receipt(args.id, args.event_id)
        else:
            result = client.usage(args.task_type, args.limit)
        print(json.dumps(result, ensure_ascii=False))
    except (ValueError, OSError, RuntimeError):
        # Local parse/file errors can contain original text, paths or configuration.
        parser.exit(1, 'AI 예제 실행 실패. 서버 환경변수·권한·입력 형식/한도·연결 상태를 확인하세요. 자동 재시도하지 않습니다.\n')


if __name__ == '__main__':
    main()
