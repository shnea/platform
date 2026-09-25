"""Explicit operator-only NCP connectivity check; not the notification service API.

Only --send sends one fixed test message. No automatic send retries.
Use --status REQUEST_ID to inspect delivery without sending again.
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def required(name):
    value = os.environ.get(name, '').strip()
    if not value:
        raise SystemExit('Missing configuration: ' + name)
    return value


def signature(method, path, timestamp, access, secret):
    message = f'{method} {path}\n{timestamp}\n{access}'.encode()
    return base64.b64encode(hmac.new(secret.encode(), message, hashlib.sha256).digest()).decode()


def call(host, method, path, body=None):
    access, secret = required('NCP_ACCESS_KEY'), required('NCP_SECRET_KEY')
    timestamp = str(time.time_ns() // 1_000_000)
    headers = {'Content-Type': 'application/json', 'x-ncp-iam-access-key': access,
               'x-ncp-apigw-timestamp': timestamp,
               'x-ncp-apigw-signature-v2': signature(method, path, timestamp, access, secret)}
    data = None if body is None else json.dumps(body).encode()
    try:
        with build_opener(NoRedirect()).open(Request(host + path, data=data, method=method, headers=headers), timeout=30) as response:
            return json.load(response)
    except HTTPError as error:
        # Never print provider bodies: they can echo recipients, credentials or message content.
        try:
            info = json.load(error)
            code = info.get('error', {}).get('errorCode') or info.get('statusCode')
        except (ValueError, AttributeError):
            code = None
        raise SystemExit(f'NCP HTTP {error.code}; provider code={code}. No automatic resend.') from None
    except (URLError, TimeoutError):
        raise SystemExit('NCP connection failed; delivery may be unknown. Check console before resending.') from None


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('channel', choices=['email', 'sms'])
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument('--send', action='store_true', help='Actually send one fixed test message')
    actions.add_argument('--status', help='Read delivery status for the given request ID')
    args = parser.parse_args()
    if args.channel == 'email':
        host, path = 'https://mail.apigw.ntruss.com', '/api/v1/mails'
        if args.status:
            result = call(host, 'GET', path + '/requests/' + quote(args.status, safe='') + '/status')
            keys = ['requestId', 'readyCompleted', 'allSentSuccess', 'requestCount', 'sentCount', 'finishCount', 'countsByStatus']
        else:
            recipient = required('NCP_TEST_EMAIL')
            if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+', recipient):
                raise SystemExit('Invalid test email')
            result = call(host, 'POST', path, {'senderAddress': required('NCP_MAIL_SENDER_ADDRESS'),
                'senderName': os.environ.get('NCP_MAIL_SENDER_NAME', 'SHNEA'),
                'title': '[SHNEA Platform] 이메일 연동 테스트',
                'body': 'SHNEA Platform 이메일 발송 테스트입니다. 요청하신 수신 확인용 메일이며 별도 조치는 필요하지 않습니다.',
                'recipients': [{'address': recipient, 'type': 'R'}], 'individual': True, 'advertising': False})
            keys = ['requestId', 'count']
    else:
        host = 'https://sens.apigw.ntruss.com'
        path = '/sms/v2/services/' + quote(required('NCP_SMS_SERVICE_ID'), safe=':') + '/messages'
        if args.status:
            result = call(host, 'GET', path + '?' + urlencode({'requestId': args.status}))
            result = {key: result.get(key) for key in ['statusCode', 'statusName']} | {
                'messages': [{key: message.get(key) for key in ['messageId', 'status', 'statusCode', 'statusName', 'statusMessage']}
                             for message in result.get('messages', [])]}
            keys = list(result)
        else:
            sender, recipient = required('NCP_SMS_SENDER_NUMBER'), required('NCP_TEST_PHONE')
            if not all(re.fullmatch(r'[0-9]{8,15}', value) for value in [sender, recipient]):
                raise SystemExit('Invalid sender or recipient phone number')
            result = call(host, 'POST', path, {'type': 'SMS', 'contentType': 'COMM', 'countryCode': '82',
                'from': sender, 'content': '[SHNEA] 플랫폼 SMS 발송 테스트입니다.', 'messages': [{'to': recipient}]})
            keys = ['requestId', 'statusCode', 'statusName']
    print(json.dumps({key: result.get(key) for key in keys}, ensure_ascii=False))


if __name__ == '__main__':
    run()
