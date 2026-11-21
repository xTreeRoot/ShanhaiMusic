"""Regression tests for atomic website switching and archive restrictions."""

from io import BytesIO
from pathlib import Path
import tarfile
import tempfile
import unittest

from receive import PAGES, publish


def archive(files, extra=None):
    stream = BytesIO()
    with tarfile.open(fileobj=stream, mode='w:gz') as target:
        for path, content in files.items():
            entry = tarfile.TarInfo(path)
            entry.size = len(content)
            target.addfile(entry, BytesIO(content))
        if extra:
            target.addfile(extra)
    stream.seek(0)
    return stream


class PublishTest(unittest.TestCase):
    def test_switches_only_after_validating_website(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'versions').mkdir()
            files = {page: b'<html>Shanhai</html>' for page in PAGES}
            files['build-revision.txt'] = b'a' * 40
            self.assertEqual(publish(archive(files), root), 'a' * 40)
            active = (root / 'current').resolve()
            self.assertEqual((active / 'zh/index.html').read_bytes(), files['zh/index.html'])
            link = tarfile.TarInfo('outside')
            link.type = tarfile.SYMTYPE
            link.linkname = '/etc/passwd'
            for invalid in (archive({'../outside': b'invalid'}), archive(files, link), archive({})):
                with self.assertRaises(ValueError):
                    publish(invalid, root)
                self.assertEqual((root / 'current').resolve(), active)
            files['build-revision.txt'] = b'b' * 40
            self.assertEqual(publish(archive(files), root), 'b' * 40)
            self.assertTrue(active.is_dir())
            self.assertNotEqual((root / 'current').resolve(), active)
