"""Bounded async Python logging handler. Python 3.10+, stdlib only; server-side."""
import json
import logging
import queue
import re
import threading
import time
from datetime import datetime, timezone
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler

SENSITIVE = re.compile(r'password|passwd|secret|token|authorization|cookie|api.?key|credential|otp|verification.?code|email|phone|address|request.?body|response.?body', re.I)
VALUES = re.compile(r'bearer\s+[^\s\"\x27,;]+|pk_[a-z0-9-]+_[^\s\"\x27,;]+|sk-[a-z0-9_-]+|eyJ[a-z0-9_-]+\.[a-z0-9_-]+\.[a-z0-9_-]+|[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}', re.I)
PAIRS = re.compile(r'((?:password|passwd|secret|token|authorization|cookie|api[_-]?key|otp|verification[_-]?code)\s*[\"\x27]?\s*[:=]\s*)(?:"[^"]*"|\x27[^\x27]*\x27|[^\s,;}]+)', re.I)


def sanitize(value, depth=0):
    if depth > 5:
        return '[DEPTH_LIMIT]'
    if isinstance(value, dict):
        return {str(k)[:64]: '[REDACTED]' if SENSITIVE.search(str(k)) else sanitize(v, depth + 1)
                for k, v in list(value.items())[:32]}
    if isinstance(value, (list, tuple)):
        return [sanitize(v, depth + 1) for v in value[:32]]
    if isinstance(value, str):
        return VALUES.sub('[REDACTED]', PAIRS.sub(r'\1[REDACTED]', value))[:8192]
    return value if value is None or isinstance(value, (bool, int, float)) else '[UNSUPPORTED]'


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class PlatformLogs(logging.Handler):
    def __init__(self, base, key, service, capacity=1000):
        parsed = urlsplit(base)
        if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('localhost', '127.0.0.1', 'nginx')):
            raise ValueError('Use HTTPS (HTTP is allowed only for local checks)')
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
            raise ValueError('base must be an origin')
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}', service) or not 1 <= capacity <= 10000:
            raise ValueError('Invalid service or capacity')
        super().__init__()
        self.url, self.key, self.service = base.rstrip('/') + '/api/v1/logs', key, service
        self.pending, self.stop = queue.Queue(capacity), threading.Event()
        self.http, self.guard = build_opener(NoRedirect), threading.Lock()
        self.counters = {'sent': 0, 'dropped': 0, 'failed_batches': 0}
        self.worker = threading.Thread(target=self._run, name='platform-logs', daemon=True)
        self.worker.start()

    def _count(self, name, count=1):
        with self.guard:
            self.counters[name] += count

    def stats(self):
        with self.guard:
            return dict(self.counters, queued=self.pending.qsize())

    def emit(self, record):
        # Never call logging from here or the transport: that would recurse.
        if self.stop.is_set():
            self._count('dropped')
            return
        try:
            entry = dict(timestamp=datetime.fromtimestamp(record.created, timezone.utc).isoformat(),
                service=self.service, level={logging.WARNING: 'WARN', logging.CRITICAL: 'FATAL'}.get(record.levelno, record.levelname),
                message=sanitize(record.getMessage()), attributes=sanitize(getattr(record, 'attributes', {})))
            for field in ('requestId', 'traceId', 'errorCode'):
                value = getattr(record, field, None)
                if isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', value):
                    entry[field] = value
            if record.exc_info:
                entry['exception'] = sanitize(logging.Formatter().formatException(record.exc_info))
            # Bound queue memory by serialized byte size, not unbounded LogRecord objects.
            data = json.dumps(entry, ensure_ascii=False, allow_nan=False).encode()
            if len(data) > 12000:
                raise ValueError('entry too large')
            self.pending.put_nowait(data)
        except Exception:
            self._count('dropped')

    def _run(self):
        while not self.stop.is_set() or not self.pending.empty():
            batch, size = [], 0
            try:
                batch.append(self.pending.get(timeout=.5))
                size = len(batch[0])
                while len(batch) < 10 and size < 108000:
                    try:
                        item = self.pending.get_nowait()
                    except queue.Empty:
                        break
                    batch.append(item)
                    size += len(item)
            except queue.Empty:
                continue
            body = b'{"entries":[' + b','.join(batch) + b']}'
            delivered = False
            for attempt in range(3):
                try:
                    with self.http.open(Request(self.url, data=body, method='POST',
                            headers={'X-Platform-Key': self.key, 'Content-Type': 'application/json'}), timeout=5) as response:
                        if response.status != 202:
                            break
                    delivered = True
                    break
                except HTTPError as error:
                    status = error.code
                    error.close()
                    if status < 500 and status != 429:
                        break
                except (OSError, TimeoutError):
                    pass
                if self.stop.wait(min(4, 2 ** attempt)):
                    break
            self._count('sent' if delivered else 'dropped', len(batch))
            if not delivered:
                self._count('failed_batches')
            for _ in batch:
                self.pending.task_done()
            if self.stop.is_set():
                while True:
                    try:
                        self.pending.get_nowait()
                        self.pending.task_done()
                        self._count('dropped')
                    except queue.Empty:
                        return

    def close(self):
        self.stop.set()
        self.worker.join(timeout=6)
        # Shutdown is bounded; remaining diagnostics can be lost. Inspect stats before exit.
        super().close()


if __name__ == '__main__':
    import os
    handler = PlatformLogs(os.environ['PLATFORM_BASE_URL'], os.environ['PLATFORM_API_KEY'], 'example')
    logger = logging.getLogger('platform-example')
    logger.setLevel(logging.INFO)
    logger.addHandler(handler)
    logger.info('Platform log connection check', extra={'requestId': 'example-request'})
    handler.close()
    print(json.dumps(handler.stats()))
