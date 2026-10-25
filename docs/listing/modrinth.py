"""Modrinth project page from the repository (docs/listing).

The token is read from `modrinth.token` in local.properties (never commit
it). Commands:

    python docs/listing/modrinth.py show                  # the page as Modrinth has it now
    python docs/listing/modrinth.py push-body             # description.md as the page's body
    python docs/listing/modrinth.py push-icon <png>       # the project icon
    python docs/listing/modrinth.py add-image <png> <title> [--featured]  # a gallery image; prints its URL
"""
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API = 'https://api.modrinth.com/v2'
PROJECT = '3ToF1nXi'  # MODRINTH_ID of the release workflow
USER_AGENT = 'JordanOlivet/farfield listing script'


def token():
    for line in (ROOT / 'local.properties').read_text(encoding='utf-8').splitlines():
        key, _, value = line.partition('=')
        if key.strip() == 'modrinth.token' and value.strip():
            return value.strip()
    sys.exit('No modrinth.token in local.properties')


def call(method, path, body=None, content_type='application/json'):
    data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode('utf-8')
    request = urllib.request.Request(API + path, data=data, method=method, headers={
        'Authorization': token(), 'User-Agent': USER_AGENT, 'Content-Type': content_type})
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read().decode('utf-8')
            return response.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        sys.exit(f'{method} {path}: HTTP {e.code} {e.read().decode("utf-8", "replace")}')


def show():
    _, p = call('GET', f'/project/{PROJECT}')
    for key in ('slug', 'title', 'description', 'status', 'categories', 'client_side', 'server_side',
                'source_url', 'issues_url', 'wiki_url', 'discord_url', 'license', 'icon_url'):
        print(f'{key}: {p.get(key)}')
    for image in p.get('gallery', []):
        print(f"gallery: {image.get('title')} featured={image.get('featured')} {image.get('url')}")
    print('--- body ---')
    print(p.get('body'))


def push_body():
    body = (ROOT / 'docs/listing/description.md').read_text(encoding='utf-8')
    status, _ = call('PATCH', f'/project/{PROJECT}', {'body': body})
    print(f'body pushed: HTTP {status}, {len(body)} characters')


def push_icon(png):
    status, _ = call('PATCH', f'/project/{PROJECT}/icon?ext=png', Path(png).read_bytes(), 'image/png')
    print(f'icon pushed: HTTP {status}')


def add_image(png, title, featured):
    query = f'ext=png&featured={"true" if featured else "false"}&title={urllib.request.quote(title)}'
    status, _ = call('POST', f'/project/{PROJECT}/gallery?{query}', Path(png).read_bytes(), 'image/png')
    _, p = call('GET', f'/project/{PROJECT}')
    urls = [g['url'] for g in p.get('gallery', []) if g.get('title') == title]
    print(f'gallery image added: HTTP {status}, {urls[-1] if urls else "URL not found"}')


if __name__ == '__main__':
    command = sys.argv[1] if len(sys.argv) > 1 else 'show'
    if command == 'show':
        show()
    elif command == 'push-body':
        push_body()
    elif command == 'push-icon':
        push_icon(sys.argv[2])
    elif command == 'add-image':
        add_image(sys.argv[2], sys.argv[3], '--featured' in sys.argv[4:])
    else:
        sys.exit(__doc__)
