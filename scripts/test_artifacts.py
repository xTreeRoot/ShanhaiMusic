"""构建收尾、产物留存和清理边界的本地回归。"""

import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest


SOURCE = Path(__file__).resolve().parents[1]


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve() / 'MB'
        (self.root / 'scripts').mkdir(parents=True)
        shutil.copy(SOURCE / 'scripts/artifacts.py', self.root / 'scripts')
        self.git('init', '-q')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                 'commit', '-qm', 'Fixture', '--allow-empty')
        self.namespace = self.root / '.build/mb-artifacts'
        self.command = [sys.executable, 'scripts/artifacts.py']

    def git(self, *args):
        subprocess.run(['git', *args], cwd=self.root, check=True)

    def run_cli(self, *args):
        return subprocess.run([*self.command, *args], cwd=self.root, capture_output=True, text=True)

    def item(self, folder, ordinal):
        run = self.namespace / folder / f'{ordinal:019d}-abcd1234'
        run.mkdir(parents=True)
        (run / '.mb-run').write_text(run.name + '\n')
        return run

    def test_preview_retention_and_unmanaged_files(self):
        old = self.item('success/ios-package', 1)
        (old / 'old.ipa').write_text('旧候选')
        rollback = self.item('success/ios-package', 2)
        (rollback / '.keep').touch()
        latest = self.item('success/ios-package', 3)
        (latest / 'latest.ipa').touch()
        failure = self.item('failure/ios-package', 4)
        (failure / 'run.log').touch()
        orphan = self.item('work', 5)
        (orphan / 'tmp/Payload').mkdir(parents=True)
        (orphan / 'evidence').mkdir()
        (orphan / 'evidence/failure.log').touch()
        for relative in ('ios/Sources/uncommitted.swift', 'ios/Review/image.png', 'ios/.build/artifacts/delivered.ipa'):
            protected = self.root / relative
            protected.parent.mkdir(parents=True, exist_ok=True)
            protected.touch()
        preview = self.run_cli()
        self.assertEqual(preview.returncode, 0, preview.stderr)
        self.assertIn(str(old), preview.stdout)
        self.assertTrue((orphan / 'tmp/Payload').exists())
        self.assertTrue(old.exists())
        applied = self.run_cli('--apply')
        self.assertEqual(applied.returncode, 0, applied.stderr)
        self.assertFalse(old.exists())
        self.assertFalse((orphan / 'tmp').exists())
        for protected in (rollback, latest / 'latest.ipa', failure / 'run.log', orphan / 'evidence/failure.log',
                          'ios/Sources/uncommitted.swift', 'ios/Review/image.png', 'ios/.build/artifacts/delivered.ipa'):
            self.assertTrue((self.root / protected).exists())

    def test_refuses_redirected_and_tracked_paths(self):
        outside = self.root.parent / 'outside'
        outside.mkdir()
        (outside / 'source').touch()
        orphan = self.item('work', 1)
        (orphan / 'tmp').symlink_to(outside, target_is_directory=True)
        old = self.item('success/desktop', 2)
        (old / 'source').touch()
        self.git('add', str(old / 'source'))
        self.item('success/desktop', 3)
        applied = self.run_cli('--apply')
        self.assertNotEqual(applied.returncode, 0)
        self.assertIn('符号链接', applied.stderr)
        self.assertIn('Git 跟踪文件', applied.stderr)
        self.assertTrue((outside / 'source').exists())
        self.assertTrue((old / 'source').exists())
        sibling = self.root.parent / 'sibling'
        sibling.mkdir()
        self.assertNotEqual(self.run_cli('--apply', str(sibling)).returncode, 0)
        self.assertTrue(sibling.exists())

    def test_process_occupation_and_resolved_failure(self):
        old = self.item('success/ios-simulator-test', 1)
        (old / 'run.log').touch()
        failed = self.item('failure/ios-simulator-test', 2)
        (failed / 'result.xcresult').mkdir()
        (failed / 'run.log').touch()
        (failed / '.resolved').write_text('修复原因与回归结论')
        self.item('success/ios-simulator-test', 3)
        with (old / 'run.log').open():
            applied = self.run_cli('--apply')
        self.assertEqual(applied.returncode, 0, applied.stderr)
        self.assertIn('被进程占用', applied.stdout)
        self.assertTrue(old.exists())
        self.assertTrue((failed / 'run.log').exists())
        self.assertTrue((failed / '.resolved').exists())
        self.assertFalse((failed / 'result.xcresult').exists())

    def test_signal_cleanup_and_concurrent_build(self):
        task = ['run', 'ios-core', '--', 'bash', '-c',
                'mkdir "$MB_TASK_TMP/unpacked"; touch "$MB_EVIDENCE/ready"; sleep 30']
        process = subprocess.Popen([*self.command, *task], cwd=self.root, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 5
            while not list(self.namespace.glob('work/*/evidence/ready')) and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertTrue(list(self.namespace.glob('work/*/evidence/ready')))
            blocked = self.run_cli('run', 'desktop', '--', 'true')
            self.assertIn('正在使用', blocked.stderr)
            self.assertNotEqual(self.run_cli('--apply').returncode, 0)
            process.send_signal(signal.SIGTERM)
            output, _ = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 143, output)
            self.assertFalse(list(self.namespace.glob('work/*')))
            self.assertTrue(list(self.namespace.glob('failure/ios-core/*/ready')))
        finally:
            if process.poll() is None:
                process.terminate()
                process.communicate(timeout=10)

    def test_desktop_wrapper_cleans_up_and_preserves_last_success(self):
        shutil.copy(SOURCE / 'scripts/build-desktop.sh', self.root / 'scripts')
        tools = self.root / 'tools'
        tools.mkdir()
        go = tools / 'go'
        go.write_text('#!/bin/bash\n'
                      '[[ "$1 $2 $3" == "tool mygo build" && "$4" == -o && "$5" == "$MB_TASK_TMP/desktop" ]] || exit 2\n'
                      'mkdir -p "$5/darwin-arm64"\n'
                      'echo verified > "$5/darwin-arm64/fixture"\n'
                      '[[ "$6" != -fixture-fail ]] || exit 12\n')
        go.chmod(0o755)
        env = dict(os.environ, PATH=f'{tools}:{os.environ["PATH"]}')
        command = ['bash', 'scripts/build-desktop.sh']
        result = subprocess.run(command, cwd=self.root, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        output, = self.namespace.glob('success/desktop/*/output/darwin-arm64/fixture')
        failed = subprocess.run([*command, '-fixture-fail'], cwd=self.root, env=env, capture_output=True, text=True)
        self.assertEqual(failed.returncode, 12, failed.stdout + failed.stderr)
        self.assertEqual(output.read_text(), 'verified\n')
        self.assertFalse(list(self.namespace.glob('work/*')))
        self.assertTrue(list(self.namespace.glob('failure/desktop/*/run.log')))
        self.assertFalse(list(self.namespace.glob('failure/desktop/*/output')))


if __name__ == '__main__':
    unittest.main()
