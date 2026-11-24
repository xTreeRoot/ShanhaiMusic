#!/usr/bin/env python3
"""管理 MB 构建入口的临时副本、滚动产物和排障证据。"""

import argparse
from contextlib import contextmanager
import fcntl
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
JOBS = ('desktop', 'android', 'ios-core', 'ios-host', 'ios-package', 'ios-device',
        'ios-simulator-build', 'ios-simulator-test')
RUN_NAME = re.compile(r'[0-9]{19}-[a-z0-9_]{8}')
OWNER = '.mb-run'
GIB = 1024**3


def checked(path):
    if ROOT not in path.parents or '..' in path.relative_to(ROOT).parts or any(
            item.is_symlink() for item in (path, *path.parents) if ROOT in item.parents):
        raise ValueError(f'拒绝越界或符号链接：{path}')
    return path


def managed(*parts):
    return checked(ROOT.joinpath('.build', 'mb-artifacts', *parts))


@contextmanager
def lock(create):
    namespace = managed()
    if create:
        namespace.mkdir(parents=True, exist_ok=True)
    lockfile = managed('lock')
    if not create and not lockfile.exists():
        yield True, None
        return
    flags = os.O_NOFOLLOW | (os.O_CREAT | os.O_RDWR if create else os.O_RDONLY)
    with os.fdopen(os.open(lockfile, flags, 0o600), 'rb') as handle:
        try:
            fcntl.flock(handle, (fcntl.LOCK_EX if create else fcntl.LOCK_SH) | fcntl.LOCK_NB)
        except BlockingIOError:
            yield False, None
        else:
            yield True, handle.fileno()


def owned(directory):
    try:
        return (checked(directory).is_dir() and RUN_NAME.fullmatch(directory.name)
                and checked(directory / OWNER).read_text() == directory.name + '\n')
    except (OSError, ValueError):
        return False


def runs(*parts):
    directory = managed(*parts)
    return sorted((path for path in directory.iterdir() if owned(path)), reverse=True) if directory.is_dir() else []


def candidates(recover, jobs):
    for job in jobs:
        try:
            successes = runs('success', job)
            failed_runs = runs('failure', job) if recover and successes else []
        except (OSError, ValueError) as error:
            print(f'跳过 {job}：{error}', file=sys.stderr)
            continue
        if successes:
            print(f'保留 {successes[0]}：最近成功产物')
        for old in successes[1:]:
            if os.path.lexists(old / '.keep'):
                print(f'保留 {old}：已标记交付、回退或验收')
            else:
                yield old, '被较新成功产物替代（未标记留存）'
        for failure in failed_runs:
            resolution = failure / '.resolved'
            result = failure / 'result.xcresult'
            try:
                if (failure.name < successes[0].name and not os.path.lexists(failure / '.keep')
                        and not resolution.is_symlink() and resolution.is_file()
                        and resolution.read_text().strip() and os.path.lexists(result)):
                    yield result, '已标记解决且有较新回归通过；保留结论和日志'
            except (OSError, UnicodeError) as error:
                print(f'跳过 {failure}：{error}', file=sys.stderr)
    if recover:
        try:
            for orphan in runs('work'):
                if os.path.lexists(orphan / 'tmp'):
                    yield orphan / 'tmp', '中断遗留的临时副本；同级 evidence 保留'
        except (OSError, ValueError) as error:
            print(f'跳过遗留任务：{error}', file=sys.stderr)


def check_removal(path):
    checked(path)
    if not path.is_dir() or path.is_symlink() or subprocess.check_output(
            ['git', 'ls-files', '-z', '--', str(path)], cwd=ROOT):
        raise ValueError(f'拒绝非受管目录或含 Git 跟踪文件：{path}')
    device = path.parent.stat().st_dev
    if device != path.stat().st_dev or path.is_mount():
        raise ValueError(f'拒绝挂载点：{path}')
    for folder, dirs, files in os.walk(path, onerror=abort):
        for name in dirs + files:
            item = Path(folder) / name
            if item.lstat().st_dev != device:
                raise ValueError(f'拒绝跨挂载点：{item}')


def abort(error):
    raise error


def open_paths():
    output = subprocess.check_output(['lsof', '-nP', '-F', 'n'], text=True, errors='replace')
    return [line[1:] for line in output.splitlines() if line.startswith('n/')]


def busy(path, opened):
    return any(name == str(path) or name.startswith(str(path) + '/') for name in opened)


def prune(apply, recover, jobs=JOBS):
    planned = list(candidates(recover, jobs))
    if not planned:
        print('没有可轮转的受管产物。')
        return 0
    opened = open_paths()
    total = deleted = failures = 0
    for path, reason in planned:
        try:
            check_removal(path)
            if busy(path, opened):
                print(f'跳过（被进程占用）：{path}')
                continue
            kib = int(subprocess.check_output(['du', '-sk', str(path)]).split()[0])
            print(f'{"待删除" if apply else "预览"} {kib / 1024:.1f} MiB {path}：{reason}', flush=True)
            total += kib
            if apply:
                shutil.rmtree(path)
                deleted += kib
                print(f'已删除：{path}')
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            failures += 1
            print(f'未清理 {path}：{error}', file=sys.stderr)
    print(f'计划占用 {total / 1024**2:.2f} GiB，已删目录占用 {deleted / 1024**2:.2f} GiB；失败 {failures} 项。')
    return bool(failures)


def disk_report():
    free = shutil.disk_usage(ROOT).free
    print(f'磁盘可用 {free / GIB:.2f} GiB。', flush=True)
    if free < 15 * GIB:
        print('低于 15 GiB：只运行有预算的固定目录构建；不要新增大型下载或多套构建。', flush=True)
    elif free < 30 * GIB:
        print('低于 30 GiB：先预览并清理已确认闲置的项目产物。', flush=True)


def project_usage():
    paths = []
    for name in ('ios/.build', '.build', 'build', 'android/app/build'):
        try:
            path = checked(ROOT / name)
            if path.is_dir():
                paths.append(str(path))
        except ValueError as error:
            print(f'跳过占用统计：{error}', file=sys.stderr)
    if paths:
        sizes = subprocess.check_output(['du', '-sk', *paths], text=True).splitlines()
        total = 0
        for line in sizes:
            kib, path = line.split('\t', 1)
            if path != str(ROOT / 'android/app/build'):
                total += int(kib)
            print(f'占用 {int(kib) / 1024**2:.2f} GiB {path}')
        if total > 6 * 1024**2:
            print(f'约定的三个缓存根共 {total / 1024**2:.2f} GiB，超出 6 GiB 目标；不自动清理验收和固定缓存。')


@contextmanager
def capture_signals():
    interrupted = []

    def forward(signum, _frame):
        interrupted.append(signum)

    previous = {signum: signal.signal(signum, forward) for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}
    try:
        yield interrupted
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def invoke(command, env, log, lock_fd, interrupted):
    if interrupted:
        return 128 + interrupted[0]

    def send(signum):
        try:
            os.killpg(child.pid, signum)
        except ProcessLookupError:
            pass

    with log.open('wb') as output:
        child = subprocess.Popen(command, env=env, stdout=output, stderr=subprocess.STDOUT,
                                 start_new_session=True, pass_fds=(lock_fd,))
        deadline = None
        while True:
            if interrupted and deadline is None:
                send(interrupted[0])
                deadline = time.monotonic() + 5
            if deadline is not None and time.monotonic() >= deadline:
                send(signal.SIGKILL)
            try:
                status = child.wait(timeout=0.2)
                break
            except subprocess.TimeoutExpired:
                pass
        if interrupted:
            send(signal.SIGKILL)
            return 128 + interrupted[0]
        return status if status >= 0 else 128 - status


def run(job, command):
    with lock(create=True) as (acquired, lock_fd), capture_signals() as interrupted:
        if not acquired:
            print('MB 构建目录正在使用；本次不启动、不清理。', file=sys.stderr)
            return 1
        disk_report()
        project_usage()
        for orphan in runs('work'):
            print(f'未收尾任务：{orphan}（请先预览清理，排障证据不删除）', flush=True)
        for parts in [('work',), ('success', job), ('failure', job)]:
            managed(*parts).mkdir(parents=True, exist_ok=True)
        work = Path(tempfile.mkdtemp(prefix=f'{time.time_ns():019d}-', dir=managed('work')))
        (work / OWNER).write_text(work.name + '\n')
        scratch, evidence = work / 'tmp', work / 'evidence'
        scratch.mkdir()
        evidence.mkdir(mode=0o700)
        log = evidence / 'run.log'
        env = dict(os.environ, MB_TASK_TMP=str(scratch), MB_EVIDENCE=str(evidence), TMPDIR=str(scratch))
        print(f'{job} 临时目录：{scratch}；用途：构建暂存、打包或附件导出。日志：{log}', flush=True)
        status = 1
        try:
            with (evidence / 'source.txt').open('w') as source:
                subprocess.run(['git', 'rev-parse', 'HEAD'], stdout=source, cwd=ROOT, check=True)
                subprocess.run(['git', 'status', '--short'], stdout=source, cwd=ROOT, check=True)
            status = invoke(command, env, log, lock_fd, interrupted)
        finally:
            if interrupted:
                status = 128 + interrupted[0]
            target = managed('success' if status == 0 else 'failure', job, work.name)
            (evidence / OWNER).write_text(work.name + '\n')
            (evidence / 'status.txt').write_text(f'{status}\n')
            checked(evidence).rename(target)
            print(f'{job} {"成功产物" if status == 0 else "失败证据"}：{target}', flush=True)
            check_removal(work)
            if busy(work, open_paths()):
                print(f'跳过（仍被进程占用，下次可预览）：{work}', file=sys.stderr)
                status = status or 1
            else:
                shutil.rmtree(work)
            if (target / 'run.log').is_file():
                subprocess.run(['tail', '-n', '25', str(target / 'run.log')], check=True)
            if status == 0 and prune(apply=True, recover=False, jobs=(job,)):
                status = 1
            disk_report()
        return 128 + interrupted[0] if interrupted else status


def main():
    parser = argparse.ArgumentParser(description='MB 产物治理：默认预览，--apply 才删除；不碰历史、交付归档或失败证据。',
                                     epilog='构建包装：run 任务 -- 命令；查询成功产物：latest 任务。')
    parser.add_argument('--apply', action='store_true', help='删除已列出的闲置受管产物')
    if sys.argv[1:2] == ['run']:
        runner = argparse.ArgumentParser(prog=f'{parser.prog} run', usage='%(prog)s 任务 -- 命令')
        runner.add_argument('job', choices=JOBS)
        args = runner.parse_args(sys.argv[2:3])
        if len(sys.argv) < 5 or sys.argv[3] != '--':
            runner.error('需要 -- 命令')
        return run(args.job, sys.argv[4:])
    if sys.argv[1:2] == ['latest']:
        latest = argparse.ArgumentParser(prog=f'{parser.prog} latest')
        latest.add_argument('job', choices=JOBS)
        found = runs('success', latest.parse_args(sys.argv[2:]).job)
        if not found:
            latest.exit(1, '没有成功产物\n')
        print(found[0])
        return 0
    args = parser.parse_args()
    disk_report()
    with lock(create=args.apply) as (acquired, _lock_fd):
        if not acquired:
            print('跳过：MB 构建或清理正在使用受管目录。')
            return 1 if args.apply else 0
        project_usage()
        for relative, reason in (
                ('ios/.build', '固定构建目录、SwiftPM 与历史验收/IPA'),
                ('ios/Review', '交付截图与引用'), ('build', '历史桌面/安卓交付归档'),
                ('android/app/build', 'Gradle 固定增量输出'), ('.build/mb-artifacts/failure', '待排障证据')):
            if (ROOT / relative).exists():
                print(f'保留 {ROOT / relative}：{reason}')
        status = prune(apply=args.apply, recover=True)
        if args.apply:
            disk_report()
        return status


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        sys.exit(f'产物治理失败：{error}')
