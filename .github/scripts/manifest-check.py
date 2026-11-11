"""Checks a repo's packageManifest.json against its code: valid JSON, required fields, unique UUIDs,
each location maps to a tracked file in this repo, and each name/namespace matches that file's definition."""
import json
import re
import subprocess
import sys
import uuid

import os

# Usage: python .github/scripts/manifest-check.py [repo-dir] [repo-name]
# In GitHub Actions the repository name comes from GITHUB_REPOSITORY.
repo = sys.argv[1] if len(sys.argv) > 1 else '.'
slug = sys.argv[2] if len(sys.argv) > 2 else os.environ.get('GITHUB_REPOSITORY', '').split('/')[-1]
prefix = f'https://raw.githubusercontent.com/9r5n76yjmzs-design/{slug}/main/'
m = json.load(open(f'{repo}/packageManifest.json', encoding='utf-8'))
tracked = set(subprocess.run(['git', '-C', repo, 'ls-files', '--others', '--cached', '--exclude-standard'],
                             capture_output=True, text=True).stdout.split())
problems = []
for key in ['packageName', 'version', 'minimumHEVersion', 'author', 'dateReleased', 'licenseFile', 'documentationLink']:
    if not m.get(key):
        problems.append(f'missing {key}')
if not m['licenseFile'].startswith(prefix) or m['licenseFile'][len(prefix):] not in tracked:
    problems.append('licenseFile does not point to a tracked file')
ids = []
for kind in ['apps', 'drivers']:
    for item in m.get(kind, []):
        ids.append(item['id'])
        uuid.UUID(item['id'])
        loc = item['location']
        if not loc.startswith(prefix):
            problems.append(f"{item['name']}: location not in this repo: {loc}")
            continue
        path = loc[len(prefix):]
        if path not in tracked:
            problems.append(f"{item['name']}: {path} is not a file in the repo")
            continue
        src = open(f'{repo}/{path}', encoding='utf-8').read()
        name_const = re.search(r"(DRIVER_NAME|APP_NAME) = '([^']+)'", src)
        ns = re.search(r"namespace:\s*'([^']+)'", src)
        if not name_const or name_const.group(2) != item['name']:
            problems.append(f"{item['name']}: name differs from {path} ({name_const and name_const.group(2)})")
        if not ns or ns.group(1) != item['namespace']:
            problems.append(f"{item['name']}: namespace differs from {path}")
        if item.get('oauth'):
            problems.append(f"{item['name']}: OAuth enabled (adds an endpoint)")
if len(ids) != len(set(ids)):
    problems.append('duplicate ids')
print(f"{slug}: {m['packageName']} {m['version']}, {len(ids)} item(s): " + ('PASS' if not problems else 'FAIL'))
for p in problems:
    print('  ', p)
sys.exit(1 if problems else 0)
