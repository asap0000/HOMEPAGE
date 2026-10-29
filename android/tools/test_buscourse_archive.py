import sys
import os
import importlib.util
import json
from pathlib import Path
import sqlite3
import tarfile
import tempfile
import threading
import unittest
from unittest import mock
import zipfile
import hashlib
import shutil

spec = importlib.util.spec_from_file_location('buscourse_archive', Path(__file__).with_name('buscourse_archive.py'))
archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(archive)


def make_db(path, version, started, model='SyntheticDevice', distance=500, frame=True):
    con = sqlite3.connect(path)
    con.execute('CREATE TABLE recording_session (id INTEGER PRIMARY KEY, started_at INTEGER, device_model TEXT, type TEXT, status TEXT, total_distance_m REAL' +
                (', run_uid TEXT, memo TEXT' if version == 24 else '') + ')')
    values = [1, started, model, 'FULL_RUN', 'COMPLETED', distance]
    if version == 24:
        values += ['synthetic-uid', 'synthetic memo']
    con.execute('INSERT INTO recording_session VALUES (' + ','.join('?' for _ in values) + ')', values)
    con.execute('CREATE TABLE gps_point (id INTEGER, session_id INTEGER, seq INTEGER, lat REAL, lon REAL)')
    con.execute('INSERT INTO gps_point VALUES (1,1,0,1.25,2.5)')
    con.execute('CREATE TABLE timelapse_frame (id INTEGER, session_id INTEGER, seq INTEGER, file_rel_path TEXT)')
    if frame:
        con.execute("INSERT INTO timelapse_frame VALUES (1,1,0,'sessions/1/frames/synthetic.jpg')")
    con.execute(f'PRAGMA user_version={version}')
    con.commit(); con.close()


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / 'vault'
        self.root.mkdir()
        self.old = archive.CUTOFF - 1000
        self.new = archive.CUTOFF + 1000

    def folder(self):
        return next(folder for folder, _ in archive.archive_runs(self.root))

    def export(self, device, started):
        base = device / 'archive_out'
        base.mkdir(parents=True, exist_ok=True)
        key = archive.run_key(started)
        folder = base / key
        folder.mkdir()
        run = {'schema': archive.SCHEMA, 'startedAt': started, 'endedAt': started + 1000,
               'deviceModel': 'SyntheticDevice', 'runUid': None, 'type': 'FULL_RUN',
               'status': 'COMPLETED', 'totalDistanceM': 400, 'appVersion': 'test',
               'dbVersion': 24, 'session': {'id': 1}, 'gps': [], 'frames': [],
               'stopVisits': [], 'shocks': [], 'missingFiles': []}
        archive.dump(folder / 'run.json', run)
        (folder / 'frames').mkdir()
        (folder / 'frames' / 'synthetic.bin').write_bytes(b'synthetic bytes')
        archive.write_manifest(folder)
        (folder / 'DONE').touch()
        return folder

    def fake_adb(self, devices, stalled=()):
        script = self.base / ('fake_' + str(len(list(self.base.glob('fake_*.py')))) + '.py')
        script.write_text('''#!/usr/bin/env python3
import pathlib, shutil, sys, time
devices = {devices!r}
stalled = {stalled!r}
counter = pathlib.Path({counter!r})
log = pathlib.Path({log!r})
argv = sys.argv[1:]
if argv == ['devices']:
    print('List of devices attached')
    for serial in devices: print(serial + '\\tdevice')
    sys.exit(0)
serial = argv[1]
args = argv[2:]
base = pathlib.Path(devices[serial])
def local(remote):
    return base / pathlib.Path(remote).relative_to('/sdcard/Android/data/com.istech.buscourse/files')
if args[:2] == ['shell', 'ls']:
    folder = local(args[-1])
    if not folder.is_dir(): sys.exit(1)
    print('\\n'.join(p.name for p in folder.iterdir()))
elif args[:2] == ['shell', 'test']:
    sys.exit(0 if local(args[3]).is_file() else 1)
elif args[:2] == ['shell', 'getprop']:
    print('SyntheticDevice')
elif args[:2] == ['shell', 'pm']:
    if base.name == 'empty': print('package:synthetic')
elif args[:2] == ['shell', 'du']:
    size = sum(p.stat().st_size for p in local(args[-1]).rglob('*') if p.is_file())
    print(str((size + 1023) // 1024) + '\\t' + args[-1])
elif args[:2] == ['shell', 'cat']:
    print(local(args[-1]).read_text())
elif args[0] == 'pull':
    source = local(args[1])
    target = pathlib.Path(args[2]) / source.name
    with log.open('a') as out: out.write(args[1] + '\\n')
    if source.name in stalled:
        counter.write_text(str(int(counter.read_text()) + 1 if counter.exists() else 1))
        target.mkdir()
        (target / 'partial').write_bytes(b'x')
        time.sleep(3)
    elif source.is_dir():
        shutil.copytree(source, target)
    else:
        shutil.copy2(source, target)
elif args[0] == 'push':
    shutil.copy2(args[1], local(args[2]))
elif args[:2] == ['shell', 'rm']:
    shutil.rmtree(local(args[3]))
'''.format(devices={k: str(v) for k, v in devices.items()}, stalled=list(stalled),
           counter=str(self.base / 'pull_count.txt'), log=str(self.base / 'pull_log.txt')))
        script.chmod(0o755)
        if os.name == 'nt':
            wrapper = script.with_suffix('.cmd')
            wrapper.write_text(f'@"{sys.executable}" "{script}" %*\n')
            return str(wrapper)
        return str(script)

    def fake_adb_with_volumes(self, mounts, volumes):
        """本体（/sdcard）に加えて /storage/<ボリューム> も見える偽 adb。mounts は {端末側の files ディレクトリ: 手元のフォルダ}。"""
        script = self.base / ('fake_vol_' + str(len(list(self.base.glob('fake_vol_*.py')))) + '.py')
        script.write_text('''#!/usr/bin/env python3
import pathlib, shutil, sys
mounts = {mounts!r}
volumes = {volumes!r}
argv = sys.argv[1:]
if argv == ['devices']:
    print('List of devices attached')
    print('SER_VOL\\tdevice')
    sys.exit(0)
args = argv[2:]
def local(remote):
    for prefix, folder in mounts.items():
        if remote == prefix or remote.startswith(prefix + '/'):
            return pathlib.Path(folder) / pathlib.Path(remote).relative_to(prefix)
    raise SystemExit(1)
if args[:3] == ['shell', 'ls', '/storage']:
    print(' '.join(volumes))
elif args[:2] == ['shell', 'ls']:
    folder = local(args[-1])
    if not folder.is_dir(): sys.exit(1)
    print('\\n'.join(p.name for p in folder.iterdir()))
elif args[:2] == ['shell', 'test']:
    sys.exit(0 if local(args[3]).is_file() else 1)
elif args[:2] == ['shell', 'getprop']:
    print('SyntheticDevice')
elif args[:2] == ['shell', 'pm']:
    sys.exit(1)
elif args[:2] == ['shell', 'du']:
    target = local(args[-1])
    if not target.exists(): sys.exit(1)
    size = sum(p.stat().st_size for p in target.rglob('*') if p.is_file())
    print(str((size + 1023) // 1024) + '\\t' + args[-1])
elif args[:2] == ['shell', 'cat']:
    print(local(args[-1]).read_text())
elif args[0] == 'pull':
    source = local(args[1])
    shutil.copytree(source, pathlib.Path(args[2]) / source.name)
elif args[0] == 'push':
    shutil.copy2(args[1], local(args[2]))
elif args[:2] == ['shell', 'rm']:
    shutil.rmtree(local(args[3]))
'''.format(mounts={k: str(v) for k, v in mounts.items()}, volumes=list(volumes)))
        script.chmod(0o755)
        if os.name == 'nt':
            wrapper = script.with_suffix('.cmd')
            wrapper.write_text(f'@"{sys.executable}" "{script}" %*\n')
            return str(wrapper)
        return str(script)

    def navi_package(self, parent, source='app', run_id='SyntheticDevice_1000', payload=b'{"kind":"header"}\n'):
        folder = parent / (source + '-package')
        folder.mkdir(parents=True, exist_ok=True)
        raw = folder / '1000.jsonl'
        raw.write_bytes(payload)
        archive.dump(folder / 'candidate-meta.json', {'source': source, 'sourceRunId': run_id,
                     'rawFile': raw.name, 'rawBytes': len(payload), 'rawSha256': hashlib.sha256(payload).hexdigest(),
                     'endedEarly': True})
        manifest = ''.join(f'{archive.sha(p)}  {p.name}\n' for p in sorted(folder.iterdir()))
        (folder / 'manifest.sha256').write_text(manifest, encoding='utf-8', newline='\n')
        (folder / 'DONE').touch()
        return folder

    def test_navi_samples_versions_receipts_and_origin_unchanged(self):
        source = self.base / 'source'; source.mkdir()
        # 契約の合成見本（web-lab の r3）がある環境では、その包みをそのまま受け入れられることも確かめる。
        r3 = Path(os.environ.get('BUSCOURSE_NAVI_SAMPLES', '') or '/nonexistent')
        if (r3 / 'sample-app').is_dir() and (r3 / 'sample-browser').is_dir():
            for name in ('sample-app', 'sample-browser'):
                shutil.copytree(r3 / name, source / name)
        else:
            self.navi_package(source, 'app', 'SyntheticDevice_1000').rename(source / 'sample-app')
            self.navi_package(source, 'browser', 'synthetic-browser').rename(source / 'sample-browser')
        before = {p.relative_to(source).as_posix(): archive.sha(p) for p in source.rglob('*') if p.is_file()}
        counts = archive.ingest_folder(self.root, source)
        self.assertEqual(counts['navi_archived'], 2)
        self.assertEqual((counts['navi_app_archived'], counts['navi_browser_archived']), (1, 1))
        for folder in source.iterdir():
            meta = archive.load(folder / 'candidate-meta.json')
            version = self.root / 'ナビの走った跡' / archive.navi_source(meta['source']) / archive.safe_navi(meta['sourceRunId']) / 'v1'
            for original in folder.rglob('*'):
                if original.is_file():
                    self.assertEqual((version / original.relative_to(folder)).read_bytes(), original.read_bytes())
            self.assertTrue((version / '_vault/provenance.json').is_file())
            self.assertTrue((version / '_vault/vault.sha256').is_file())
            receipt = archive.load(archive._navi_receipt(self.root, meta['source'], meta['sourceRunId'], 1))
            self.assertEqual((receipt['schema'], receipt['stage'], receipt['phoneDeletionAllowed']),
                             (archive.NAVI_SCHEMA, 'VAULT_ARCHIVED', False))
        self.assertEqual(before, {p.relative_to(source).as_posix(): archive.sha(p) for p in source.rglob('*') if p.is_file()})
        self.assertEqual(archive.ingest_folder(self.root, source)['navi_duplicate'], 2)
        self.assertEqual(before, {p.relative_to(source).as_posix(): archive.sha(p) for p in source.rglob('*') if p.is_file()})

    def test_navi_changed_same_id_creates_v2_without_touching_v1(self):
        source = self.base / 'source'; source.mkdir()
        one = self.navi_package(source)
        self.assertEqual(archive.ingest_navi_package(self.root, one, 'synthetic'), 'archived')
        base, versions = archive._navi_versions(self.root, 'app', 'SyntheticDevice_1000')
        first = {p.relative_to(versions[0]).as_posix(): archive.sha(p) for p in versions[0].rglob('*') if p.is_file()}
        two_parent = self.base / 'other'; two_parent.mkdir()
        two = self.navi_package(two_parent, payload=b'{"kind":"header"}\n{"kind":"fix"}\n')
        self.assertEqual(archive.ingest_navi_package(self.root, two, 'synthetic'), 'archived')
        self.assertTrue((base / 'v2' / 'DONE').is_file())
        self.assertEqual(first, {p.relative_to(versions[0]).as_posix(): archive.sha(p) for p in versions[0].rglob('*') if p.is_file()})

    def test_navi_invalid_packages_are_held_without_receipts(self):
        for case in ('no_done', 'bad_sha', 'extra', 'traversal', 'bad_source'):
            with self.subTest(case=case):
                parent = self.base / case; parent.mkdir()
                folder = self.navi_package(parent, run_id=case)
                if case == 'no_done': (folder / 'DONE').unlink()
                if case == 'bad_sha': (folder / '1000.jsonl').write_bytes(b'changed')
                if case == 'extra': (folder / 'extra.txt').write_text('extra')
                if case == 'traversal':
                    manifest = folder / 'manifest.sha256'
                    manifest.write_text(manifest.read_text() + '0' * 64 + '  ../outside\n', newline='\n')
                if case == 'bad_source':
                    meta = archive.load(folder / 'candidate-meta.json'); meta['source'] = 'invalid'
                    archive.dump(folder / 'candidate-meta.json', meta)
                    (folder / 'manifest.sha256').write_text(''.join(f'{archive.sha(p)}  {p.name}\n' for p in sorted(folder.iterdir())
                        if p.name not in ('manifest.sha256', 'DONE')), newline='\n')
                before = {p.name: archive.sha(p) for p in folder.iterdir() if p.is_file()}
                self.assertEqual(archive.ingest_navi_package(self.root, folder, 'synthetic'), 'held')
                self.assertEqual(before, {p.name: archive.sha(p) for p in folder.iterdir() if p.is_file()})
                self.assertFalse(archive._navi_receipt(self.root, 'app', case, 1).exists())
        self.assertEqual(len(list((self.root / 'ナビの走った跡/受け入れ保留').glob('*.reason.txt'))), 5)

    def test_navi_only_fake_adb_pull_and_receipt(self):
        device = self.base / 'device'; out = device / 'navirun_out'; out.mkdir(parents=True)
        good = self.navi_package(out, run_id='SyntheticDevice_1000')
        bad_parent = self.base / 'bad'; bad_parent.mkdir()
        bad = self.navi_package(bad_parent, run_id='SyntheticDevice_2000')
        bad.rename(out / 'bad-package')
        (out / 'bad-package' / 'DONE').unlink()
        # 保留の試験では DONE が必要なので、SHA を壊す。
        (out / 'bad-package' / 'DONE').touch()
        (out / 'bad-package' / '1000.jsonl').write_bytes(b'bad')
        fake = self.fake_adb({'SYNTHETIC_SERIAL': device})
        selected = archive.discover(fake, ['SYNTHETIC_SERIAL'])
        self.assertEqual(len(selected['navi_ready']), 2)
        counts = archive.pull(self.root, 'SYNTHETIC_SERIAL', fake, model='SyntheticDevice')
        self.assertEqual((counts['navi_archived'], counts['navi_held']), (1, 1))
        self.assertFalse(good.exists())
        self.assertTrue((out / 'bad-package').exists())
        rows = archive.load(device / 'navirun_receipt.json')['rows']
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['sourceRunId'], 'SyntheticDevice_1000')

    def test_versions_eras_and_source_unchanged(self):
        a = self.base / 'old.db'; b = self.base / 'new.db'
        make_db(a, 1, self.old); make_db(b, 24, self.new)
        before = a.read_bytes()
        result = archive.ingest_legacy(self.root, [a, b])
        self.assertEqual(result['ingested'], 2)
        self.assertEqual(a.read_bytes(), before)
        runs = list(archive.archive_runs(self.root))
        self.assertEqual({x.parent.name for x, _ in runs}, {'安定前', '安定後'})
        self.assertIn('memo', next(r for _, r in runs if r['dbVersion'] == 24)['session'])
        self.assertNotIn('memo', next(r for _, r in runs if r['dbVersion'] == 1)['session'])
        self.assertEqual(len(next(r for _, r in runs)['gps']), 1)

    def test_legacy_sensor_file_is_copied(self):
        path = self.base / 'source.db'
        make_db(path, 24, self.new, frame=False)
        sensor = self.base / 'sessions' / '1' / 'sensors.jsonl.gz'
        sensor.parent.mkdir(parents=True)
        sensor.write_bytes(b'synthetic gzip bytes')
        archive.ingest_legacy(self.root, [path])
        self.assertEqual((self.folder() / sensor.name).read_bytes(), sensor.read_bytes())

    def test_db_frames_idempotent_increment_and_conflict(self):
        path = self.base / 'run.db'; make_db(path, 24, self.new)
        frames = self.base / 'sessions' / '1' / 'frames'; frames.mkdir(parents=True)
        image = frames / 'synthetic.jpg'; image.write_bytes(b'first')
        archive.ingest_legacy(self.root, [path])
        folder = self.folder()
        self.assertEqual((folder / 'frames/synthetic.jpg').read_bytes(), b'first')
        self.assertEqual(archive.ingest_legacy(self.root, [path])['skipped'], 1)
        self.assertEqual(len(archive.load(folder / 'provenance.json')['sources']), 1)
        image.write_bytes(b'second')
        archive.ingest_legacy(self.root, [path])
        self.assertEqual((folder / 'frames/synthetic.jpg').read_bytes(), b'first')
        self.assertEqual(len(list((folder / 'frames').glob('synthetic.jpg.alt-*'))), 1)
        self.assertEqual(len(archive.load(folder / 'provenance.json')['conflicts']), 1)
        con = sqlite3.connect(path)
        con.execute("INSERT INTO timelapse_frame VALUES (2,1,1,'sessions/1/frames/next.jpg')")
        con.commit(); con.close()
        (frames / 'next.jpg').write_bytes(b'next')
        archive.ingest_legacy(self.root, [path])
        self.assertTrue((folder / 'frames/next.jpg').is_file())
        self.assertEqual(len(archive.load(folder / 'run.json')['frames']), 2)

    def test_zip_and_tar(self):
        path = self.base / 'source.db'; make_db(path, 1, self.old)
        image = self.base / 'synthetic.jpg'; image.write_bytes(b'image')
        z = self.base / 'backup.zip'; t = self.base / 'backup.tar'
        with zipfile.ZipFile(z, 'w') as out:
            out.write(path, 'db/buscourse.db')
            out.write(image, 'files/buscourse/sessions/1/frames/synthetic.jpg')
        with tarfile.open(t, 'w') as out:
            out.add(path, 'db/buscourse.db')
            out.add(image, 'buscourse/sessions/1/frames/synthetic.jpg')
        self.assertEqual(archive.ingest_legacy(self.root, [z])['ingested'], 1)
        self.assertEqual(archive.ingest_legacy(self.root, [t])['skipped'], 1)
        self.assertEqual((self.folder() / 'frames/synthetic.jpg').read_bytes(), b'image')

    def test_frames_only_tar_joins_db(self):
        path = self.base / 'source.db'; make_db(path, 1, self.old)
        image = self.base / 'synthetic.jpg'; image.write_bytes(b'from-tar')
        tar = self.base / 'frames.tar'
        with tarfile.open(tar, 'w') as out:
            out.add(image, 'buscourse/sessions/1/frames/synthetic.jpg')
        self.assertEqual(archive.ingest_legacy(self.root, [path, tar])['ingested'], 1)
        self.assertEqual((self.folder() / 'frames/synthetic.jpg').read_bytes(), b'from-tar')

    def test_receipt_startup(self):
        a = self.base / 'a.db'; b = self.base / 'b.db'
        make_db(a, 1, self.old, distance=299, frame=False)
        make_db(b, 24, self.new, distance=300, frame=False)
        archive.ingest_legacy(self.root, [a, b])
        output = self.base / 'receipt.json'
        receipt = archive.make_receipt(self.root, 'SyntheticDevice', output)
        self.assertEqual([r['startupTestDeletable'] for r in receipt['runs']], [True, False])
        self.assertEqual(receipt['schema'], archive.RECEIPT_SCHEMA)

    def test_startup_test_on_newest_recorded_version_is_kept(self):
        # 「最新の版」は走行が記録された版のうち一番新しいもの。その版の起動テストは消してよい印を付けない。
        a = self.base / 'a.db'; c = self.base / 'c.db'
        make_db(a, 19, self.new, distance=10, frame=False)
        make_db(c, 23, self.new + 5000, distance=10, frame=False)
        archive.ingest_legacy(self.root, [a, c])
        receipt = archive.make_receipt(self.root, 'SyntheticDevice', self.base / 'r.json')
        self.assertEqual([r['startupTestDeletable'] for r in receipt['runs']], [True, False])

    def test_blank_fields_and_recording_status_are_filled_by_later_source(self):
        # 撮影中に取られたバックアップでは距離が空欄。後のバックアップの値で埋め、前の目録は版付きで残す。
        a = self.base / 'a.db'; b = self.base / 'b.db'
        make_db(a, 23, self.new, frame=False)
        con = sqlite3.connect(a); con.execute("UPDATE recording_session SET total_distance_m=NULL, status='RECORDING'"); con.commit(); con.close()
        make_db(b, 23, self.new, distance=18000, frame=False)
        archive.ingest_legacy(self.root, [a])
        archive.ingest_legacy(self.root, [b])
        run = archive.load(self.folder() / 'run.json')
        self.assertEqual(run['totalDistanceM'], 18000)
        self.assertEqual(run['session']['status'], 'COMPLETED')
        self.assertTrue(list(self.folder().glob('run.json.rev-*')))

    def test_long_gps_track_is_not_a_startup_test_even_without_distance(self):
        run = {'totalDistanceM': None, 'gps': [{'seq': i, 'lat': 0.0, 'lon': i * 0.001} for i in range(5)]}
        self.assertFalse(archive.is_startup_test(run))  # 約445m
        self.assertTrue(archive.is_startup_test({'totalDistanceM': None, 'gps': []}))

    def test_legacy_run_with_missing_frames_is_not_receipted(self):
        # 古いバックアップに映像の実物が無い走行を「保管済み」と言うと、端末の映像を消させてしまう。
        a = self.base / 'a.db'
        make_db(a, 23, self.new, frame=True)
        archive.ingest_legacy(self.root, [a])
        receipt = archive.make_receipt(self.root, 'SyntheticDevice', self.base / 'r.json')
        self.assertEqual(receipt['runs'], [])

    def test_pull_with_fake_adb(self):
        device = self.base / 'device'; exports = device / 'archive_out'; exports.mkdir(parents=True)
        run = {'schema': archive.SCHEMA, 'startedAt': self.new, 'endedAt': self.new + 1000,
               'deviceModel': 'SyntheticDevice', 'runUid': None, 'type': 'FULL_RUN',
               'status': 'COMPLETED', 'totalDistanceM': 400, 'appVersion': 'test',
               'dbVersion': 24, 'session': {'id': 1}, 'gps': [], 'frames': [],
               'stopVisits': [], 'shocks': [], 'missingFiles': []}
        key = archive.run_key(self.new)
        good = exports / key; good.mkdir()
        archive.dump(good / 'run.json', run)
        archive.write_manifest(good); (good / 'DONE').touch()
        bad = exports / archive.run_key(self.new + 2000); bad.mkdir()
        archive.dump(bad / 'run.json', dict(run, startedAt=self.new + 2000))
        archive.write_manifest(bad); (bad / 'DONE').touch()
        (bad / 'run.json').write_text('broken')
        pending = exports / archive.run_key(self.new + 3000); pending.mkdir()
        archive.dump(pending / 'run.json', run)
        fake = self.base / 'fake_adb.py'
        fake.write_text('''#!/usr/bin/env python3
import pathlib, shutil, sys
base = pathlib.Path({device!r})
args = sys.argv[3:]
def local(remote):
    return base / pathlib.Path(remote).relative_to('/sdcard/Android/data/com.istech.buscourse/files')
if args[:2] == ['shell', 'ls']:
    print('\\n'.join(p.name for p in local(args[-1]).iterdir()))
elif args[:2] == ['shell', 'test']:
    sys.exit(0 if local(args[3]).is_file() else 1)
elif args[0] == 'pull':
    shutil.copytree(local(args[1]), pathlib.Path(args[2]) / pathlib.Path(args[1]).name)
elif args[0] == 'push':
    shutil.copy2(args[1], local(args[2]))
elif args[:2] == ['shell', 'rm']:
    shutil.rmtree(local(args[3]))
'''.format(device=str(device)))
        fake.chmod(0o755)
        if os.name == 'nt':  # Windows は .py を直接起動できないので .cmd でくるむ
            wrapper = self.base / 'fake_adb.cmd'
            wrapper.write_text(f'@"{sys.executable}" "{fake}" %*\n')
            fake = wrapper
        result = archive.pull(self.root, 'SYNTHETIC_SERIAL', str(fake))
        self.assertEqual(result['ingested'], 1)
        self.assertEqual(result['mismatch'], 1)
        self.assertFalse(good.exists())
        self.assertTrue(bad.exists())
        self.assertTrue(pending.exists())
        receipt = archive.load(device / 'archive_receipt.json')
        self.assertEqual(receipt['runs'][0]['frames'], 0)
        self.assertEqual(archive.load(self.root / 'index.json')[0]['runKey'], key)

    def test_manifest_validation(self):
        folder = self.base / 'export'; folder.mkdir()
        (folder / 'run.json').write_text('{}')
        archive.write_manifest(folder)
        (folder / 'DONE').touch()
        self.assertTrue(archive.verify_manifest(folder))
        (folder / 'run.json').write_text('bad')
        self.assertFalse(archive.verify_manifest(folder))

    def test_receipt_per_run_and_cancel_before_second(self):
        device = self.base / 'device'
        folders = [self.export(device, self.new + i * 2000) for i in range(3)]
        adb = self.fake_adb({'SER_A': device})
        stop = threading.Event()
        def progress(info):
            if info['number'] == 2:
                stop.set()
        result = archive.pull(self.root, 'SER_A', adb, cancel=stop, progress=progress)
        self.assertEqual(result['ingested'], 1)
        self.assertTrue(result['cancelled'])
        self.assertFalse(folders[0].exists())
        self.assertTrue(all(folder.exists() for folder in folders[1:]))
        self.assertEqual(len(archive.load(device / 'archive_receipt.json')['runs']), 1)

    def test_stall_retries_three_times_then_next_run(self):
        device = self.base / 'device'
        stuck = self.export(device, self.new)
        next_run = self.export(device, self.new + 2000)
        adb = self.fake_adb({'SER_A': device}, stalled=[stuck.name])
        result = archive.pull(self.root, 'SER_A', adb, stall_seconds=0.15)
        self.assertEqual(result['not_pulled'], 1)
        self.assertEqual(result['ingested'], 1)
        self.assertEqual((self.base / 'pull_count.txt').read_text(), '3')
        self.assertTrue(stuck.exists())
        self.assertFalse(next_run.exists())

    def test_bad_run_kept_while_next_advances(self):
        device = self.base / 'device'
        bad = self.export(device, self.new)
        (bad / 'frames' / 'synthetic.bin').write_bytes(b'wrong')
        good = self.export(device, self.new + 2000)
        result = archive.pull(self.root, 'SER_A', self.fake_adb({'SER_A': device}))
        self.assertEqual((result['mismatch'], result['ingested']), (1, 1))
        self.assertTrue(bad.exists())
        self.assertFalse(good.exists())

    def test_adb_choice_and_device_filter_and_selection(self):
        first = self.base / 'first'; second = self.base / 'second'; empty = self.base / 'empty'
        self.export(first, self.new)
        self.export(second, self.new + 2000)
        empty.mkdir()
        good_adb = self.fake_adb({'SER_A': first, 'SER_B': second, 'SER_EMPTY': empty})
        mismatch = self.base / 'mismatch.py'
        mismatch.write_text("#!/usr/bin/env python3\nprint('server version doesn\\'t match')\nprint('List of devices attached')\n")
        mismatch.chmod(0o755)
        if os.name == 'nt':
            wrapper = mismatch.with_suffix('.cmd')
            wrapper.write_text(f'@"{sys.executable}" "{mismatch}" %*\n')
            mismatch = wrapper
        with mock.patch.dict(os.environ, {'BUSCOURSE_ADB': good_adb}):
            adb, serials = archive.choose_adb(str(mismatch))
        self.assertEqual(adb, good_adb)
        self.assertEqual(len(serials), 3)
        selected = archive.discover(adb, serials, lambda choices: choices[1])
        self.assertEqual(selected['serial'], 'SER_B')
        self.assertEqual(len(archive.discover(adb, serials, lambda choices: choices)), 2)
        self.assertEqual(archive.discover(adb, ['SER_EMPTY'])['ready'], [])

    def test_storage_volume_archive_out_is_also_pulled(self):
        """microSD にある走行は /storage/<ボリューム>/Android/data/<pkg>/files/archive_out に書き出される（2026-10-20）。"""
        body_files = archive.DEVICE_PATH
        card_files = '/storage/ABCD-1234/Android/data/com.istech.buscourse/files'
        device = self.base / 'device'; card = self.base / 'card'
        on_body = self.export(device, self.new)
        on_card = self.export(card, self.new + 5000)
        adb = self.fake_adb_with_volumes({body_files: device, card_files: card}, ['emulated', 'self', 'ABCD-1234'])
        self.assertEqual(archive.device_bases(adb, 'SER_VOL'), [body_files, card_files])
        ready, pending = archive.export_list(adb, 'SER_VOL')
        self.assertEqual(ready, sorted([on_body.name, on_card.name]))
        self.assertEqual(pending, [])
        # 置き場所を知らなくても大きさが取れる（GUI は名前だけで呼ぶ）。
        self.assertGreater(archive.remote_size(adb, 'SER_VOL', on_card.name), 0)
        result = archive.pull(self.root, 'SER_VOL', adb)
        self.assertEqual(result['ingested'], 2)
        self.assertEqual(result['mismatch'], 0)
        self.assertEqual(result['not_pulled'], 0)
        # 取り込んだ書き出しは、それぞれ元のボリュームから消える。受領票は本体側にだけ置く。
        self.assertFalse(on_body.exists())
        self.assertFalse(on_card.exists())
        self.assertTrue((device / 'archive_receipt.json').is_file())
        self.assertFalse((card / 'archive_receipt.json').exists())
        self.assertEqual(len(archive.load(device / 'archive_receipt.json')['runs']), 2)

    def test_storage_volume_emulated_and_self_are_not_searched(self):
        adb = self.fake_adb_with_volumes({archive.DEVICE_PATH: self.base / 'device'}, ['emulated', 'self'])
        self.assertEqual(archive.device_bases(adb, 'SER_VOL'), [archive.DEVICE_PATH])
        self.assertEqual(archive.export_list(adb, 'SER_VOL'), ([], []))

    # ---- 新形の書き出し（2026-10-29 委託指示書 M）: 端末は写真を写さず、source.json で元の場所を指す ----
    SESSIONS_REMOTE = '/sdcard/Android/data/com.istech.buscourse/files/buscourse/sessions/'

    def export_new(self, device, started, frames=None, extras=None, listed=None, session_id=1, total=None,
                   source_id=None, source_dir_id=None):
        """端末（偽）に、元の場所（sessions/<id>）と、新形の archive_out（run.json・manifest.sha256・source.json・DONE だけ）を作る。"""
        frames = {'a.jpg': b'photo one', 'b.jpg': b'photo two'} if frames is None else frames
        extras = {'meta.json': b'{"id":1}', 'gps_raw.jsonl': b'{"seq":1}\n'} if extras is None else extras
        session = device / 'buscourse' / 'sessions' / str(session_id)
        (session / 'frames').mkdir(parents=True, exist_ok=True)
        for name, data in frames.items():
            (session / 'frames' / name).write_bytes(data)
        for name, data in extras.items():
            (session / name).write_bytes(data)
        files = sorted(['frames/' + name for name in frames] + list(extras)) if listed is None else listed
        folder = device / 'archive_out' / archive.run_key(started)
        folder.mkdir(parents=True)
        run = {'schema': archive.SCHEMA, 'startedAt': started, 'endedAt': started + 1000,
               'deviceModel': 'SyntheticDevice', 'runUid': None, 'type': 'FULL_RUN',
               'status': 'COMPLETED', 'totalDistanceM': 400, 'appVersion': 'test',
               'dbVersion': 24, 'session': {'id': session_id}, 'gps': [],
               'frames': [{'id': i + 1, 'session_id': session_id, 'seq': i,
                           'file_rel_path': f'sessions/{session_id}/frames/{name}'} for i, name in enumerate(frames)],
               'stopVisits': [], 'shocks': [], 'missingFiles': []}
        archive.dump(folder / 'run.json', run)
        entries = {rel: archive.sha(session / rel) for rel in files}
        entries['run.json'] = archive.sha(folder / 'run.json')
        (folder / 'manifest.sha256').write_text(''.join(f'{sha}  {rel}\n' for rel, sha in sorted(entries.items())),
                                               encoding='utf-8', newline='\n')
        size = sum((session / rel).stat().st_size for rel in files) if total is None else total
        archive.dump(folder / 'source.json', {'schema': 'buscourse-archive-source/1',
                                              'sessionId': session_id if source_id is None else source_id,
                                              'sessionDir': self.SESSIONS_REMOTE + str(session_id if source_dir_id is None else source_dir_id),
                                              'files': files, 'totalBytes': size})
        (folder / 'DONE').touch()
        return folder, session

    def export_old_equivalent(self, device, started, frames, extras):
        """export_new と同じ中身を、旧形（写真も記録も archive_out に写した形）で作る。"""
        folder, session = self.export_new(device, started, frames, extras)
        (folder / 'source.json').unlink()
        for name in frames:
            (folder / 'frames').mkdir(exist_ok=True)
            shutil.copy2(session / 'frames' / name, folder / 'frames' / name)
        for name in extras:
            shutil.copy2(session / name, folder / name)
        (folder / 'DONE').unlink()
        archive.write_manifest(folder)
        (folder / 'DONE').touch()
        return folder

    def vault_hashes(self, root, folder):
        return {p.relative_to(folder).as_posix(): archive.sha(p) for p in folder.rglob('*')
                if p.is_file() and p.name != 'provenance.json'}

    def pull_log(self):
        log = self.base / 'pull_log.txt'
        return log.read_text().splitlines() if log.exists() else []

    def test_新形は元の場所から写真を引き取り_旧形と同じフォルダで取り込む(self):
        frames = {'a.jpg': b'photo one', 'b.jpg': b'photo two'}
        extras = {'meta.json': b'{"id":1}', 'gps_raw.jsonl': b'{"seq":1}\n'}
        device = self.base / 'device'
        folder, session = self.export_new(device, self.new, frames, extras)
        legacy = self.export(device, self.new + 2000)
        # 旧形の写しの中身が同じ走行を別の端末で作り、取り込んだ結果のフォルダを比べる。
        reference_root = self.base / 'reference_vault'
        reference_root.mkdir()
        self.export_old_equivalent(self.base / 'reference_device', self.new, frames, extras)
        reference = archive.pull(reference_root, 'SER_REF', self.fake_adb({'SER_REF': self.base / 'reference_device'}))
        self.assertEqual((reference['ingested'], reference['mismatch'], reference['not_pulled']), (1, 0, 0))

        (self.base / 'pull_log.txt').write_text('')
        adb = self.fake_adb({'SER_A': device})
        result = archive.pull(self.root, 'SER_A', adb)
        self.assertEqual((result['ingested'], result['mismatch'], result['not_pulled']), (2, 0, 0))
        # 写真は sessions の frames をフォルダごと1回で引き取る（1枚ずつ引かない）。旧形の走行は今までどおり archive_out を引く。
        pulls = self.pull_log()
        self.assertEqual([p for p in pulls if p.endswith('/frames')], [self.SESSIONS_REMOTE + '1/frames'])
        self.assertFalse([p for p in pulls if p.endswith('.jpg')])
        self.assertIn(self.SESSIONS_REMOTE + '1/meta.json', pulls)
        # 取り込んだフォルダは、旧形で取り込んだものと同じ（provenance 以外）。source.json は混ざらない。
        mine = next(f for f, run in archive.archive_runs(self.root) if run['startedAt'] == self.new)
        theirs = next(f for f, run in archive.archive_runs(reference_root) if run['startedAt'] == self.new)
        self.assertEqual(self.vault_hashes(self.root, mine), self.vault_hashes(reference_root, theirs))
        self.assertEqual(sorted(self.vault_hashes(self.root, mine)),
                         ['frames/a.jpg', 'frames/b.jpg', 'gps_raw.jsonl', 'manifest.sha256', 'meta.json', 'run.json'])
        self.assertEqual((mine / 'frames' / 'a.jpg').read_bytes(), b'photo one')
        # 端末: 書き出し（archive_out）だけが片付き、元の sessions は何も消えていない。受領票は届いている。
        self.assertFalse(folder.exists())
        self.assertFalse(legacy.exists())
        self.assertEqual(sorted(p.name for p in (session / 'frames').iterdir()), ['a.jpg', 'b.jpg'])
        self.assertTrue((session / 'meta.json').is_file())
        self.assertEqual(len(archive.load(device / 'archive_receipt.json')['runs']), 2)

    def test_新形で元が消えていたら取り込まず端末に何も消さない(self):
        # (1) 元のフォルダごと無い
        device = self.base / 'gone'
        folder, session = self.export_new(device, self.new)
        shutil.rmtree(session)
        result = archive.pull(self.root, 'SER_G', self.fake_adb({'SER_G': device}))
        self.assertEqual(result['ingested'], 0)
        self.assertEqual(result['mismatch'] + result['not_pulled'], 1)
        self.assertEqual(sorted(p.name for p in folder.iterdir()), ['DONE', 'manifest.sha256', 'run.json', 'source.json'])
        self.assertFalse((device / 'archive_receipt.json').exists())
        self.assertEqual(list(archive.archive_runs(self.root)), [])
        # (2) 元のフォルダはあるが、書き出した後で写真が1枚消えた（指紋に合わない）
        device = self.base / 'lost_photo'
        folder, session = self.export_new(device, self.new + 2000)
        (session / 'frames' / 'b.jpg').unlink()
        result = archive.pull(self.root, 'SER_L', self.fake_adb({'SER_L': device}))
        self.assertEqual((result['ingested'], result['mismatch']), (0, 1))
        self.assertTrue((folder / 'source.json').is_file() and (folder / 'DONE').is_file())
        self.assertEqual(sorted(p.name for p in (session / 'frames').iterdir()), ['a.jpg'])
        self.assertFalse((device / 'archive_receipt.json').exists())
        # (3) 写真が書き出した後で書き換わった（指紋に合わない）
        device = self.base / 'changed_photo'
        folder, session = self.export_new(device, self.new + 4000)
        (session / 'frames' / 'a.jpg').write_bytes(b'changed')
        result = archive.pull(self.root, 'SER_C', self.fake_adb({'SER_C': device}))
        self.assertEqual((result['ingested'], result['mismatch']), (0, 1))
        self.assertTrue(folder.exists())
        self.assertEqual((session / 'frames' / 'a.jpg').read_bytes(), b'changed')
        self.assertEqual(list(archive.archive_runs(self.root)), [])

    def test_新形の余分な写真は取り込みに混ぜない(self):
        device = self.base / 'device'
        folder, session = self.export_new(device, self.new)
        # 記録の別の写真（目録に無い）が元の frames にある。
        (session / 'frames' / 'unlisted.jpg').write_bytes(b'not exported')
        result = archive.pull(self.root, 'SER_A', self.fake_adb({'SER_A': device}))
        self.assertEqual((result['ingested'], result['mismatch'], result['not_pulled']), (1, 0, 0))
        vault = self.folder()
        self.assertEqual(sorted(p.name for p in (vault / 'frames').iterdir()), ['a.jpg', 'b.jpg'])
        self.assertFalse(any('unlisted' in str(p) for p in vault.rglob('*')))
        # 端末の元の写真は残っている。
        self.assertTrue((session / 'frames' / 'unlisted.jpg').is_file())

    def test_旧形の写しは今まで通り取り込める(self):
        device = self.base / 'device'
        legacy = self.export(device, self.new)
        (self.base / 'pull_log.txt').write_text('')
        result = archive.pull(self.root, 'SER_A', self.fake_adb({'SER_A': device}))
        self.assertEqual((result['ingested'], result['mismatch'], result['not_pulled']), (1, 0, 0))
        self.assertEqual((self.folder() / 'frames' / 'synthetic.bin').read_bytes(), b'synthetic bytes')
        self.assertFalse(legacy.exists())
        # 旧形は archive_out の1か所を引くだけ（sessions へは行かない）。
        self.assertEqual(self.pull_log(), [archive.DEVICE_PATH + '/archive_out/' + legacy.name])

    def test_新形の大きさはsource_jsonの合計を使う(self):
        device = self.base / 'device'
        new_folder, _ = self.export_new(device, self.new, total=123456789)
        old_folder = self.export(device, self.new + 2000)
        adb = self.fake_adb({'SER_A': device})
        # du だと小さな目録の大きさになる。新形は source.json の totalBytes を使う。
        self.assertEqual(archive.remote_size(adb, 'SER_A', new_folder.name), 123456789)
        self.assertEqual(archive.remote_size(adb, 'SER_A', old_folder.name),
                         (sum(p.stat().st_size for p in old_folder.rglob('*') if p.is_file()) + 1023) // 1024 * 1024)
        expected = 123456789 + archive.remote_size(adb, 'SER_A', old_folder.name)
        totals = []
        archive.pull(self.root, 'SER_A', adb, progress=lambda info: totals.append(info['total_bytes']))
        self.assertEqual(totals[0], expected)

    def retry_rows(self, device):
        path = device / 'archive_retry.json'
        if not path.exists():
            return None
        data = archive.load(path)
        self.assertEqual(data['schema'], 'buscourse-archive-retry/1')
        return data['runs']

    def test_別の走行を指すsource_jsonは取り込まない(self):
        # (1) sessionId が run.json の session.id と違う  (2) sessionDir の末尾が別の走行  (3) sessionId の型が違う
        for index, (kind, kwargs) in enumerate([('id', {'source_id': 2}), ('dir', {'source_dir_id': 2}), ('type', {'source_id': '1'})]):
            device = self.base / ('other_' + kind)
            started = self.new + index * 2000
            folder, session = self.export_new(device, started, **kwargs)
            other = device / 'buscourse' / 'sessions' / '2' / 'frames'
            other.mkdir(parents=True)
            (other / 'a.jpg').write_bytes(b'photo of another run')
            (self.base / 'pull_log.txt').write_text('')
            result = archive.pull(self.root, 'SER_' + kind, self.fake_adb({'SER_' + kind: device}))
            self.assertEqual((result['ingested'], result['mismatch']), (0, 1), kind)
            # 取り込む前に止める: 元の場所からは何も引かない。端末は何も消さない。
            self.assertFalse([p for p in self.pull_log() if '/sessions/' in p], kind)
            self.assertTrue((folder / 'source.json').is_file() and (folder / 'DONE').is_file(), kind)
            self.assertTrue((session / 'frames' / 'a.jpg').is_file(), kind)
            self.assertFalse((device / 'archive_receipt.json').exists(), kind)
            self.assertEqual(self.retry_rows(device), [{'startedAt': started, 'deviceModel': 'SyntheticDevice'}], kind)
        self.assertEqual(list(archive.archive_runs(self.root)), [])

    def test_新形が合わなければarchive_retry_jsonを送る(self):
        device = self.base / 'device'
        gone, gone_session = self.export_new(device, self.new, session_id=1)
        shutil.rmtree(gone_session)                                   # 元が無い
        bad, bad_session = self.export_new(device, self.new + 2000, session_id=2)
        (bad_session / 'frames' / 'a.jpg').write_bytes(b'changed')    # 指紋に合わない
        good, _ = self.export_new(device, self.new + 4000, session_id=3)
        legacy = self.export(device, self.new + 6000)                 # 旧形が合わなくても送らない
        (legacy / 'frames' / 'synthetic.bin').write_bytes(b'wrong')
        # 端末には前から別の行がある。足されて、重ならない。
        archive.dump(device / 'archive_retry.json', {'schema': 'buscourse-archive-retry/1',
                     'runs': [{'startedAt': 1, 'deviceModel': 'Other'}, {'startedAt': self.new, 'deviceModel': 'SyntheticDevice'}]})
        adb = self.fake_adb({'SER_A': device})
        result = archive.pull(self.root, 'SER_A', adb)
        self.assertEqual((result['ingested'], result['mismatch'], result['not_pulled']), (1, 2, 1))
        self.assertEqual(self.retry_rows(device), [{'startedAt': 1, 'deviceModel': 'Other'},
                                                   {'startedAt': self.new, 'deviceModel': 'SyntheticDevice'},
                                                   {'startedAt': self.new + 2000, 'deviceModel': 'SyntheticDevice'}])
        # 端末側の書き出しはそのまま（消すのはアプリ）。
        self.assertTrue(gone.exists() and bad.exists() and legacy.exists())
        # もう一度取り込んでも重ならない。
        archive.pull(self.root, 'SER_A', adb)
        self.assertEqual(len(self.retry_rows(device)), 3)

    def test_新形が全部合えばarchive_retry_jsonは作らない(self):
        device = self.base / 'device'
        self.export_new(device, self.new)
        result = archive.pull(self.root, 'SER_A', self.fake_adb({'SER_A': device}))
        self.assertEqual(result['ingested'], 1)
        self.assertIsNone(self.retry_rows(device))

    def test_second_pull_does_not_hash_existing_vault_file(self):
        device = self.base / 'device'
        self.export(device, self.new)
        adb = self.fake_adb({'SER_A': device})
        archive.pull(self.root, 'SER_A', adb)
        self.export(device, self.new)
        real_sha = archive.sha
        vault_reads = []
        def counted(path):
            if self.root in path.parents and '.work' not in path.parts and path.name == 'synthetic.bin':
                vault_reads.append(path)
            return real_sha(path)
        with mock.patch.object(archive, 'sha', side_effect=counted):
            archive.pull(self.root, 'SER_A', adb)
        self.assertEqual(vault_reads, [])


if __name__ == '__main__':
    unittest.main()
