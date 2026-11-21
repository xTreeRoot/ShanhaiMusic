#!/usr/bin/python3
"""Receive a static website archive using the dedicated SSH deployment key."""

import fcntl
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tarfile
import tempfile


DEPLOY_DIR = Path('/var/www/shanhai/publish')
PAGES = ('index.html', 'download/index.html', 'plugins/index.html', 'zh/index.html',
         'zh/download/index.html', 'zh/plugins/index.html', '404.html')


def extract_site(stream, destination):
    size = 0
    with tarfile.open(fileobj=stream, mode='r|gz') as archive:
        for count, member in enumerate(archive, 1):
            path = PurePosixPath(member.name)
            size += member.size
            if (count > 1024 or not 0 <= size <= 64 * 1024 * 1024 or
                    path.is_absolute() or '..' in path.parts or
                    not (member.isdir() or member.isfile())):
                raise ValueError('invalid website archive')
            output = destination.joinpath(*path.parts)
            if member.isdir():
                output.mkdir(parents=True, exist_ok=True)
                continue
            output.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, output.open('xb') as target:
                shutil.copyfileobj(source, target)
    if any(not (destination / page).is_file() or not (destination / page).stat().st_size for page in PAGES):
        raise ValueError('incomplete prerendered website')
    revision = (destination / 'build-revision.txt').read_text(encoding='ascii').strip()
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('invalid build revision')
    return revision


def publish(stream, root):
    root = root.resolve()
    versions = root / 'versions'
    current = root / 'current'
    with (root / '.deploy.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        previous = current.resolve()
        with tempfile.TemporaryDirectory(prefix='deploy-', dir=root) as temporary:
            staging = Path(temporary)
            content = staging / 'site'
            content.mkdir()
            revision = extract_site(stream, content)
            version = versions / f'{revision[:12]}-{staging.name}'
            content.rename(version)
            pointer = staging / 'next'
            pointer.symlink_to(version)
            pointer.replace(current)
        for old in versions.iterdir():
            if old not in (version, previous):
                shutil.rmtree(old)
        return revision


if __name__ == '__main__':
    if os.environ.get('SSH_ORIGINAL_COMMAND') != 'publish':
        sys.exit('This key is restricted to website deployment.')
    os.umask(0o022)
    print(f'Published {publish(sys.stdin.buffer, DEPLOY_DIR)}')
