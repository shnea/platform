"""Python 3.10+, no dependencies. Server-side only. See /integrations/jobs.md."""
import argparse
import json
import os
import threading
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class ApiError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code
        super().__init__(f"HTTP {status}: {code}")


class Jobs:
    def __init__(self, base, key):
        parsed = urlsplit(base)
        if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('localhost', '127.0.0.1', 'nginx')):
            raise ValueError('Use HTTPS (HTTP is allowed only for local checks)')
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
            raise ValueError('base must be an origin')
        self.base, self.key = base.rstrip('/'), key
        self.http = build_opener(NoRedirect)

    def request(self, method, path, body=None, lease=None):
        headers = {'X-Platform-Key': self.key, 'Content-Type': 'application/json'}
        if lease:
            headers['X-Job-Lease'] = lease
        try:
            with self.http.open(Request(self.base + '/api/v1/jobs' + path,
                    data=None if body is None else json.dumps(body).encode(), headers=headers, method=method), timeout=8) as response:
                return None if response.status == 204 else json.load(response)
        except HTTPError as error:
            try:
                code = json.load(error).get('code', 'HTTP_ERROR')
            except (ValueError, AttributeError):
                code = 'HTTP_ERROR'
            raise ApiError(error.code, code) from None

    def submit(self, queue, payload, request_id, max_attempts=3):
        # Save request_id before calling; reuse the SAME ID + input after a timeout.
        return self.request('POST', '', {'requestId': str(request_id), 'queue': queue,
                            'payload': payload, 'maxAttempts': max_attempts})

    def run_once(self, queue, worker_id, handler):
        claim = self.request('POST', '/claim', {'queue': queue, 'workerId': worker_id})
        if claim is None:
            return False
        job, lease = claim['job'], claim['leaseToken']
        stop, lost = threading.Event(), threading.Event()

        def heartbeat():
            while not stop.wait(20):
                try:
                    self.request('POST', '/' + job['id'] + '/heartbeat', {}, lease)
                except Exception:
                    # Stop side effects conservatively when ownership cannot be confirmed.
                    lost.set()
                    return

        thread = threading.Thread(target=heartbeat, daemon=True)
        thread.start()
        try:
            # handler MUST deduplicate business effects by job['id'] in its own DB.
            # Check lost before each side effect. A crashed worker may execute again.
            try:
                result = handler(job, lost)
                action, body = 'complete', {'result': result or {}}
            except Exception:
                action, body = 'fail', {'errorCode': 'HOST_JOB_FAILED', 'retryable': False}
            if lost.is_set():
                raise ApiError(409, 'EXTERNAL_JOB_LEASE_LOST')
            # Reporting the same outcome/token is idempotent. Keep heartbeat alive here.
            for attempt in range(3):
                try:
                    self.request('POST', '/' + job['id'] + '/' + action, body, lease)
                    return True
                except ApiError as error:
                    if error.status < 500 or attempt == 2:
                        raise
                except (OSError, TimeoutError):
                    if attempt == 2:
                        raise
                time.sleep(1 + attempt)
        finally:
            stop.set()
            thread.join(timeout=9)


def main():
    parser = argparse.ArgumentParser(description='Platform pull worker example (server-side)')
    parser.add_argument('action', choices=['submit', 'work-once', 'get'])
    parser.add_argument('--queue', default='example')
    parser.add_argument('--request-id', help='Persisted UUID. Required when submitting.')
    parser.add_argument('--job-id')
    args = parser.parse_args()
    client = Jobs(os.environ['PLATFORM_BASE_URL'], os.environ['PLATFORM_API_KEY'])
    if args.action == 'submit':
        if not args.request_id:
            parser.error('--request-id is required; generate and save a UUID before sending')
        result = client.submit(args.queue, {'example': True}, uuid.UUID(args.request_id))
        print(json.dumps({'id': result['id'], 'state': result['state']}))
    elif args.action == 'get':
        if not args.job_id:
            parser.error('--job-id is required')
        result = client.request('GET', '/' + str(uuid.UUID(args.job_id)))
        print(json.dumps({'id': result['job']['id'], 'state': result['job']['state']}))
    else:
        # Harmless example only. Replace with your own idempotent handler.
        print('processed' if client.run_once(args.queue, 'example-worker',
                lambda job, lost: {'example': 'done'}) else 'empty')


if __name__ == '__main__':
    main()
