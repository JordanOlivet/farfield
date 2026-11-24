"""Downloads mods for a test run from Modrinth, each checked against its
sha512, into a folder of runs/ (gitignored): the mods a production check
needs beside Farfield (dev/rehearsal/prod-client.sh).

    python -I dev/rehearsal/fetch-mods.py <game version> <out dir> <slug>=<version number>=<loader> ...

    python -I dev/rehearsal/fetch-mods.py 1.20.1 runs/test-mods-1.20.1/forge \
        embeddium=0.3.31+mc1.20.1=forge oculus=1.20.1-1.8.0=forge

A version number that does not exist prints the ones Modrinth has.
"""
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request

HEADERS = {'User-Agent': 'mod-dev-test-fetch/1.0'}


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=60).read()


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    game, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    failed = False
    for spec in sys.argv[3:]:
        slug, number, loader = spec.split('=')
        query = urllib.parse.urlencode({'loaders': json.dumps([loader]), 'game_versions': json.dumps([game])})
        versions = json.loads(get(f'https://api.modrinth.com/v2/project/{slug}/version?{query}'))
        match = [v for v in versions if v['version_number'] == number]
        if not match:
            print(f'{slug}: no version {number} for {loader}; Modrinth has',
                  [v['version_number'] for v in versions][:12])
            failed = True
            continue
        files = match[0]['files']
        file = next((f for f in files if f.get('primary')), files[0])
        data = get(file['url'])
        if hashlib.sha512(data).hexdigest() != file['hashes']['sha512']:
            print(f'{slug}: sha512 mismatch, not written')
            failed = True
            continue
        with open(os.path.join(out, file['filename']), 'wb') as f:
            f.write(data)
        print(f"{slug}: {file['filename']} ({len(data) // 1024} KB), sha512 checked")
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
