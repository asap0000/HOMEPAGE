#!/usr/bin/env python3
"""BusCourse の追記型 PC 保管庫。Python 標準ライブラリのみ。"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.parse
import zipfile

# この道具は版数を持たないので、作った日時を焼き込んで画面と記録に出す（新しい・古いを見分けるため。
# オーナー裁定 2026-10-10）。直したら必ずこの日時も書き換える。
BUILT_AT = '2026-10-29 20:35'
SCHEMA = 'buscourse-archive-run/1'
RECEIPT_SCHEMA = 'buscourse-archive-receipt/1'
CUTOFF = int(dt.datetime(2026, 8, 1, tzinfo=dt.timezone(dt.timedelta(hours=9))).timestamp() * 1000)
APP_ID = 'com.istech.buscourse'
DEVICE_PATH = '/sdcard/Android/data/' + APP_ID + '/files'
# microSD は /storage/<ボリューム名>/ に見える（本体の内部ストレージは emulated、self はその別名）。
SKIPPED_VOLUMES = ('emulated', 'self')
TABLES = {'session': 'recording_session', 'gps': 'gps_point', 'frames': 'timelapse_frame',
          'stopVisits': 'stop_visit_event', 'shocks': 'shock_event'}
NAVI_SCHEMA = 'buscourse-archive-receipt/2'
# 新形の書き出し（2026-10-29）: 端末は写真を写さず、元の場所（sessions）を source.json で指す。PC がそこから持っていく。
SOURCE_SCHEMA = 'buscourse-archive-source/1'
SOURCE_EXTRAS = ('meta.json', 'gps_raw.jsonl', 'sensors.jsonl.gz')
# 新形が合わなかった走行を端末へ知らせる（端末は新形の書き出しと指紋の作り置きを消して、書き出し待ちに戻す）。
RETRY_SCHEMA = 'buscourse-archive-retry/1'


def safe_navi(value):
    value = re.sub(r'[^A-Za-z0-9._-]', '_', value)
    return '_' if value in ('.', '..') else value


def navi_base(root):
    return Path(root) / 'ナビの走った跡'


def navi_source(source):
    return {'app': 'アプリ版', 'browser': 'ブラウザ版'}[source]


def _navi_files(folder):
    for path in folder.rglob('*'):
        if path.is_symlink():
            raise ValueError('symbolic link')
        if path.is_file():
            yield path


def _navi_validate(folder):
    folder = Path(folder)
    if folder.is_symlink() or not folder.is_dir():
        raise ValueError('package folder')
    done = folder / 'DONE'
    if not done.is_file() or done.is_symlink() or done.stat().st_size:
        raise ValueError('DONE missing or nonempty')
    manifest = folder / 'manifest.sha256'
    if not manifest.is_file() or manifest.is_symlink():
        raise ValueError('manifest missing')
    data = manifest.read_bytes()
    if not data or not data.endswith(b'\n') or b'\r' in data:
        raise ValueError('manifest LF format')
    try:
        lines = data.decode('utf-8').splitlines()
    except UnicodeDecodeError as exc:
        raise ValueError('manifest UTF-8') from exc
    expected = {}
    for line in lines:
        if not re.fullmatch(r'[0-9a-f]{64}  [\x21-\x7e]+', line):
            raise ValueError('manifest line format')
        digest, name = line[:64], line[66:]
        if ('\\' in name or name.startswith('/') or any(part in ('', '.', '..') for part in name.split('/'))
                or name in ('DONE', 'manifest.sha256', '_vault') or name.startswith('_vault/') or name in expected):
            raise ValueError('unsafe manifest path')
        expected[name] = digest
    if list(expected) != sorted(expected):
        raise ValueError('manifest order')
    actual = {p.relative_to(folder).as_posix(): sha(p) for p in _navi_files(folder)
              if p.relative_to(folder).as_posix() not in ('DONE', 'manifest.sha256')}
    if actual != expected or 'candidate-meta.json' not in expected:
        raise ValueError('manifest files or SHA mismatch')
    try:
        meta = load(folder / 'candidate-meta.json')
        source, run_id, raw = meta['source'], meta['sourceRunId'], meta['rawFile']
        if source not in ('app', 'browser') or not isinstance(run_id, str) or not run_id:
            raise ValueError('source or sourceRunId')
        if not isinstance(raw, str) or raw not in expected:
            raise ValueError('rawFile')
        raw_path = folder / raw
        if meta['rawSha256'] != sha(raw_path) or type(meta['rawBytes']) is not int or meta['rawBytes'] != raw_path.stat().st_size:
            raise ValueError('raw SHA or bytes')
    except (KeyError, TypeError, json.JSONDecodeError) as exc:
        raise ValueError('candidate metadata') from exc
    return meta, sha(manifest)


def _navi_versions(root, source, run_id):
    base = navi_base(root) / navi_source(source) / safe_navi(run_id)
    return base, sorted((p for p in base.glob('v[0-9]*') if p.is_dir() and re.fullmatch(r'v[1-9][0-9]*', p.name)),
                        key=lambda p: int(p.name[1:]))


def _navi_receipt(root, source, run_id, version):
    return navi_base(root) / '受領票' / navi_source(source) / f'{safe_navi(run_id)}_v{version}.json'


def ingest_navi_package(root, folder, origin):
    """送り手は読み取り専用。失敗した包みも原形のまま保留する。"""
    root, folder = Path(root), Path(folder)
    try:
        meta, manifest_sha = _navi_validate(folder)
    except (ValueError, OSError) as exc:
        hold = navi_base(root) / '受け入れ保留'
        hold.mkdir(parents=True, exist_ok=True)
        stamp = dt.datetime.now().strftime('%Y%m%d-%H%M%S')
        name = f'{stamp}_{safe_navi(folder.name)}'
        target = hold / name
        index = 2
        while target.exists():
            target = hold / f'{name}_{index}'; index += 1
        shutil.copytree(folder, target, symlinks=True)
        target.with_name(target.name + '.reason.txt').write_text(str(exc).replace('\n', ' ') + '\n', encoding='utf-8', newline='\n')
        return 'held'
    base, versions = _navi_versions(root, meta['source'], meta['sourceRunId'])
    for existing in versions:
        if sha(existing / 'manifest.sha256') == manifest_sha:
            receipt_path = _navi_receipt(root, meta['source'], meta['sourceRunId'], int(existing.name[1:]))
            if not receipt_path.is_file():
                prov = load(existing / '_vault/provenance.json')
                _write_navi_receipt(receipt_path, meta, manifest_sha, prov['archivedAt'], int(existing.name[1:]))
            return 'duplicate'
    version = max((int(v.name[1:]) for v in versions), default=0) + 1
    base.mkdir(parents=True, exist_ok=True)
    target = base / f'v{version}'
    stage = Path(tempfile.mkdtemp(prefix='.navirun-', dir=base))
    try:
        for src in _navi_files(folder):
            dest = stage / src.relative_to(folder)
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dest)
            if sha(dest) != sha(src):
                raise ValueError('copy SHA mismatch')
        if _navi_validate(stage)[1] != manifest_sha:
            raise ValueError('staged package mismatch')
        vault = stage / '_vault'; vault.mkdir()
        archived_at = int(time.time() * 1000)
        dump(vault / 'provenance.json', {'archivedAt': archived_at, 'origin': str(origin),
                                        'manifestSha256': manifest_sha, 'rawSha256': meta['rawSha256'],
                                        'endedEarly': meta.get('endedEarly'), 'builtAt': BUILT_AT})
        files = sorted(_navi_files(stage), key=lambda p: p.relative_to(stage).as_posix())
        (vault / 'vault.sha256').write_text(''.join(f'{sha(p)}  {p.relative_to(stage).as_posix()}\n' for p in files), encoding='utf-8', newline='\n')
        if any(sha(stage / line[66:]) != line[:64] for line in (vault / 'vault.sha256').read_text(encoding='utf-8').splitlines()):
            raise ValueError('vault SHA mismatch')
        stage.rename(target)
        _write_navi_receipt(_navi_receipt(root, meta['source'], meta['sourceRunId'], version),
                            meta, manifest_sha, archived_at, version)
        return 'archived'
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def _write_navi_receipt(path, meta, manifest_sha, archived_at, version):
    receipt = {'schema': NAVI_SCHEMA, 'kind': 'navirun', 'source': meta['source'],
               'sourceRunId': meta['sourceRunId'], 'rawSha256': meta['rawSha256'],
               'rawBytes': meta['rawBytes'], 'manifestSha256': manifest_sha,
               'archivedAt': archived_at, 'version': version, 'stage': 'VAULT_ARCHIVED',
               'endedEarly': meta.get('endedEarly'), 'formalReceipt': True,
               'phoneDeletionAllowed': False, 'issuer': 'buscourse_archive ' + BUILT_AT}
    path.parent.mkdir(parents=True, exist_ok=True)
    staging = path.with_suffix('.tmp')
    dump(staging, receipt)
    staging.replace(path)


def navi_counts():
    return {'navi_archived': 0, 'navi_duplicate': 0, 'navi_held': 0,
            'navi_app_archived': 0, 'navi_browser_archived': 0}


def ingest_folder(root, path):
    path = Path(path)
    if not path.is_dir():
        raise ValueError('folder missing')
    markers = ('manifest.sha256', 'DONE', 'candidate-meta.json')
    folders = [path] if any((path / name).exists() for name in markers) else [p for p in sorted(path.iterdir()) if p.is_dir() and any((p / name).exists() for name in markers)]
    counts = navi_counts()
    for folder in folders:
        source = None
        try:
            source = load(folder / 'candidate-meta.json').get('source')
        except (OSError, ValueError, AttributeError):
            pass
        outcome = ingest_navi_package(root, folder, str(folder.resolve()))
        counts['navi_' + outcome] += 1
        if outcome == 'archived':
            counts['navi_' + source + '_archived'] += 1
    build_index(root, counts)
    log_navi(root, path, counts)
    return counts


def log_navi(root, origin, counts):
    with (Path(root) / '取り込みの記録.txt').open('a', encoding='utf-8') as out:
        out.write(f"{dt.datetime.now().astimezone():%Y-%m-%d %H:%M:%S}\tナビの走った跡\t{origin}\t"
                  f"入れた {counts['navi_archived']}\t同じ中身 {counts['navi_duplicate']}\t保留 {counts['navi_held']}\n")


def make_navi_receipt(root, model, out):
    prefix = safe_navi(model) + '_'
    rows = []
    base = navi_base(root) / '受領票' / 'アプリ版'
    for path in sorted(base.glob('*.json')) if base.is_dir() else []:
        row = load(path)
        version_dir = navi_base(root) / 'アプリ版' / safe_navi(row['sourceRunId']) / f"v{row['version']}"
        prov = load(version_dir / '_vault/provenance.json')
        if row['sourceRunId'].startswith(prefix) or prov.get('origin') == model:
            rows.append({k: v for k, v in row.items() if k != 'issuer'})
    dump(Path(out), {'schema': NAVI_SCHEMA, 'rows': rows})
    return rows


def dump(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def safe_member(name):
    p = Path(name)
    if p.is_absolute() or '..' in p.parts or not p.parts:
        raise ValueError('unsafe archive member')
    return p


def work_dir(root):
    """大きな一時ファイルは保管庫と同じドライブに置く（C: は狭い）。中身は呼び出しの後で消える。"""
    path = Path(root) / '.work'
    path.mkdir(parents=True, exist_ok=True)
    return path


def pull_stage_dir(root):
    """adb で引き取る先。Windows の adb には日本語のパスを渡すと名前が途中で切れて失敗するものがある
    （実測: AirDroid Cast 同梱の版40）ので、保管庫の場所が英字でなければ同じドライブの英字のフォルダを使う。"""
    root = Path(root)
    if str(root.resolve()).isascii():
        return work_dir(root)
    path = Path(root.resolve().anchor) / 'buscourse_archive_work'
    path.mkdir(parents=True, exist_ok=True)
    return path


def run_key(started):
    stamp = dt.datetime.fromtimestamp(started / 1000).strftime('%Y%m%d-%H%M%S')
    return f'{stamp}_{started}'


def safe_device(name):
    return urllib.parse.quote(name or 'unknown', safe='-_.')


def bucket(started):
    return '安定前' if started < CUTOFF else '安定後'


def archive_dir(root, run, preferred_key=None):
    for folder, existing in archive_runs(root):
        if (existing.get('startedAt'), existing.get('deviceModel')) == (run.get('startedAt'), run.get('deviceModel')):
            return folder
    key = preferred_key or run_key(int(run['startedAt']))
    return root / bucket(int(run['startedAt'])) / (key + '_' + safe_device(run.get('deviceModel')))


def rows(conn, table, session_id):
    available = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if table not in available:
        return []
    columns = [r[1] for r in conn.execute(f'PRAGMA table_info({table})')]
    if table == 'recording_session':
        query, args = f'SELECT * FROM {table} WHERE id=?', (session_id,)
    elif 'session_id' in columns:
        query, args = f'SELECT * FROM {table} WHERE session_id=? ORDER BY rowid', (session_id,)
    else:
        return []
    return [dict(zip(columns, row)) for row in conn.execute(query, args)]


def read_db(db_path, source_root):
    # sqlite 接続は必ず一時コピーだけに対して行う。WAL も同じ場所へ複写する。
    with tempfile.TemporaryDirectory() as temp:
        copied = Path(temp) / 'buscourse.db'
        shutil.copy2(db_path, copied)
        for suffix in ('-wal', '-shm'):
            side = Path(str(db_path) + suffix)
            if side.is_file():
                shutil.copy2(side, Path(str(copied) + suffix))
        conn = sqlite3.connect(copied)
        try:
            version = conn.execute('PRAGMA user_version').fetchone()[0]
            sessions = rows_all_sessions(conn)
            result = []
            for session in sessions:
                sid = session['id']
                run = {'schema': SCHEMA, 'startedAt': session['started_at'],
                       'endedAt': session.get('ended_at'), 'deviceModel': session.get('device_model') or '',
                       'runUid': session.get('run_uid'), 'type': session.get('type'),
                       'status': session.get('status'), 'totalDistanceM': session.get('total_distance_m'),
                       'appVersion': None, 'dbVersion': version, 'session': session,
                       'gps': rows(conn, 'gps_point', sid), 'frames': rows(conn, 'timelapse_frame', sid),
                       'stopVisits': rows(conn, 'stop_visit_event', sid), 'shocks': rows(conn, 'shock_event', sid)}
                result.append((run, source_root, sid))
            return result
        finally:
            conn.close()


def rows_all_sessions(conn):
    tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if 'recording_session' not in tables:
        return []
    cur = conn.execute('SELECT * FROM recording_session ORDER BY started_at, id')
    return [dict(zip([d[0] for d in cur.description], row)) for row in cur]


def locate_file(base, sid, rel):
    name = Path(rel).name
    candidates = [base / 'buscourse' / 'sessions' / str(sid) / 'frames' / name,
                  base / 'files' / 'buscourse' / 'sessions' / str(sid) / 'frames' / name,
                  base / 'sessions' / str(sid) / 'frames' / name,
                  base / str(sid) / 'frames' / name]
    return next((p for p in candidates if p.is_file()), None)


def locate_extra(base, sid, name):
    for prefix in ('buscourse/sessions', 'files/buscourse/sessions', 'sessions'):
        p = base / prefix / str(sid) / name
        if p.is_file():
            return p
    return None


def files_from_legacy(run, base, sid):
    files = {}
    missing = []
    for frame in run['frames']:
        name = Path(frame.get('file_rel_path') or '').name
        if not name or name in ('.', '..'):
            continue
        src = locate_file(base, sid, frame.get('file_rel_path') or '')
        if src:
            files['frames/' + name] = src
        else:
            missing.append(name)
    for name in ('meta.json', 'gps_raw.jsonl', 'sensors.jsonl.gz'):
        src = locate_extra(base, sid, name)
        if src:
            files[name] = src
    run['missingFiles'] = sorted(set(missing))
    return files


def read_manifest(folder):
    path = folder / 'manifest.sha256'
    if not path.is_file():
        return {}
    result = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not re.fullmatch(r'[0-9a-f]{64}  .+', line):
            return {}
        name = line[66:]
        try:
            safe_member(name)
        except ValueError:
            return {}
        if name in result:
            return {}
        result[name] = line[:64]
    return result


def write_manifest(folder, known_hashes=None, prior_hashes=None):
    known_hashes = known_hashes or {}
    prior_hashes = prior_hashes or {}
    files = sorted(p for p in folder.rglob('*') if p.is_file() and p.name not in ('manifest.sha256', 'provenance.json'))
    text = ''.join(f'{known_hashes.get(p.relative_to(folder).as_posix()) or prior_hashes.get(p.relative_to(folder).as_posix()) or sha(p)}  {p.relative_to(folder).as_posix()}\n' for p in files)
    manifest = folder / 'manifest.sha256'
    if not manifest.exists() or manifest.read_text(encoding='utf-8') != text:
        manifest.write_text(text, encoding='utf-8')


def verified_hashes(folder):
    manifest = folder / 'manifest.sha256'
    if not (folder / 'DONE').is_file() or (folder / 'DONE').stat().st_size != 0 or not manifest.is_file():
        return None
    expected = read_manifest(folder)
    actual = {p.relative_to(folder).as_posix(): sha(p) for p in folder.rglob('*')
              if p.is_file() and p.name not in ('manifest.sha256', 'DONE')}
    return actual if actual == expected else None


def verify_manifest(folder):
    return verified_hashes(folder) is not None


def ingest_run(root, run, files, source, preferred_key=None, verified=None):
    folder = archive_dir(root, run, preferred_key)
    folder.mkdir(parents=True, exist_ok=True)
    run_file = folder / 'run.json'
    old = load(run_file) if run_file.exists() else None
    prior_hashes = read_manifest(folder)
    known_hashes = {}
    if old is not None and (old.get('startedAt'), old.get('deviceModel')) != (run.get('startedAt'), run.get('deviceModel')):
        raise ValueError('run identity mismatch')
    merged = dict(old) if old is not None else dict(run)
    if old is not None:
        for field in ('gps', 'frames', 'stopVisits', 'shocks'):
            prior = list(merged.get(field, []))
            seen = {(r.get('id'), r.get('seq')) for r in prior}
            for row in run.get(field, []):
                identity = (row.get('id'), row.get('seq'))
                if identity not in seen:
                    prior.append(row)
                    seen.add(identity)
            merged[field] = prior
        # 目録の欄は足すだけ。ただし空欄（None）と「撮影中」は、後から来た確かな値で埋める
        # （撮影中に取られたバックアップでは距離も終了時刻も空のまま。前の run.json は版付きで残る）。
        def fill(target, key, value):
            if key not in target or (target[key] is None and value is not None) or \
                    (key == 'status' and target[key] == 'RECORDING' and value not in (None, 'RECORDING')):
                target[key] = value
        session = merged.setdefault('session', {})
        for key, value in run.get('session', {}).items():
            fill(session, key, value)
        for key, value in run.items():
            if key not in ('gps', 'frames', 'stopVisits', 'shocks', 'session', 'missingFiles'):
                fill(merged, key, value)
    conflicts = []
    added = 0
    for rel, src in files.items():
        relpath = safe_member(rel)
        target = folder / relpath
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            shutil.copy2(src, target)
            if verified and rel in verified:
                known_hashes[rel] = verified[rel]
            added += 1
        else:
            source_hash = verified.get(rel) if verified else sha(src)
            target_hash = (prior_hashes.get(rel) if target.stat().st_size == src.stat().st_size else None) or sha(target)
            if target_hash == source_hash:
                known_hashes[rel] = target_hash
                continue
            alt = target.with_name(target.name + '.alt-' + source_hash[:8])
            if not alt.exists():
                shutil.copy2(src, alt)
                known_hashes[alt.relative_to(folder).as_posix()] = source_hash
                added += 1
            conflicts.append({'file': rel, 'alternate': alt.relative_to(folder).as_posix(), 'sha256': source_hash})
    merged['missingFiles'] = sorted({Path(frame.get('file_rel_path') or '').name for frame in merged.get('frames', [])
                                     if frame.get('file_rel_path') and
                                     not (folder / 'frames' / Path(frame['file_rel_path']).name).exists()})
    if old is not None and merged != old:
        revision = folder / ('run.json.rev-' + hashlib.sha256(run_file.read_bytes()).hexdigest()[:8])
        if not revision.exists():
            shutil.copy2(run_file, revision)
    if old is None or merged != old:
        dump(run_file, merged)
        known_hashes['run.json'] = sha(run_file)
    provenance_file = folder / 'provenance.json'
    provenance = load(provenance_file) if provenance_file.exists() else {'sources': [], 'firstSeenDbVersion': run.get('dbVersion'), 'conflicts': []}
    old_provenance = json.loads(json.dumps(provenance))
    if not any(all(previous.get(k) == source.get(k) for k in ('file', 'kind', 'dbVersion')) for previous in provenance['sources']):
        provenance['sources'].append(source)
    versions = [v for v in (provenance.get('firstSeenDbVersion'), run.get('dbVersion')) if isinstance(v, int)]
    provenance['firstSeenDbVersion'] = min(versions) if versions else None
    for conflict in conflicts:
        if conflict not in provenance['conflicts']:
            provenance['conflicts'].append(conflict)
    if provenance != old_provenance or not provenance_file.exists():
        dump(provenance_file, provenance)
    write_manifest(folder, known_hashes, prior_hashes)
    return added + (1 if old is not None and merged != old else 0)


def extract_container(path, target):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                rel = safe_member(info.filename)
                if info.is_dir():
                    continue
                dest = target / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                with z.open(info) as src, dest.open('wb') as dst:
                    shutil.copyfileobj(src, dst)
    elif tarfile.is_tarfile(path):
        with tarfile.open(path) as t:
            for member in t:
                if not member.isfile():
                    continue
                rel = safe_member(member.name)
                dest = target / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                with t.extractfile(member) as src, dest.open('wb') as dst:
                    shutil.copyfileobj(src, dst)
    else:
        raise ValueError('unsupported input')


def ingest_legacy(root, inputs):
    counts = {'ingested': 0, 'skipped': 0, 'mismatch': 0}
    with tempfile.TemporaryDirectory(dir=work_dir(root)) as shared_temp:
        supplemental = Path(shared_temp) / 'supplemental'
        supplemental.mkdir()
        prepared = []
        supplement_sources = []
        # DB を含まない tar は、同じ呼出しで渡された DB のコマ補助源として使う。
        for path in map(Path, inputs):
            if path.suffix.lower() in ('.tar', '.tgz') or path.name.endswith('.tar.gz'):
                copied = path  # tar は読むだけ
                extracted = Path(shared_temp) / ('tar-' + str(len(prepared)))
                extracted.mkdir()
                extract_container(copied, extracted)
                if not list(extracted.rglob('buscourse.db')):
                    supplement_sources.append(path.name)
                    for src in extracted.rglob('*'):
                        if src.is_file():
                            target = supplemental / src.relative_to(extracted)
                            target.parent.mkdir(parents=True, exist_ok=True)
                            if not target.exists():
                                shutil.copy2(src, target)
                    continue
            prepared.append(path)
        for path in prepared:
            with tempfile.TemporaryDirectory(dir=work_dir(root)) as temp:
                stage = Path(temp)
                # sqlite は開くだけで -wal/-shm を書き換えるので DB は必ず写してから読む。zip・tar は読むだけなので写さない。
                staged_input = stage / path.name if path.suffix.lower() == '.db' else path
                if path.suffix.lower() == '.db':
                    shutil.copy2(path, staged_input)
                if path.suffix.lower() == '.db':
                    for suffix in ('-wal', '-shm'):
                        sidecar = Path(str(path) + suffix)
                        if sidecar.is_file():
                            shutil.copy2(sidecar, Path(str(staged_input) + suffix))
                    for prefix in ('buscourse/sessions', 'files/buscourse/sessions', 'sessions'):
                        side = path.parent / prefix
                        if side.is_dir():
                            shutil.copytree(side, stage / prefix)
                    db = staged_input
                    base = stage
                    kind = 'db'
                else:
                    payload = stage / 'payload'
                    payload.mkdir()
                    extract_container(staged_input, payload)
                    dbs = list(payload.rglob('buscourse.db'))
                    if not dbs:
                        counts['mismatch'] += 1
                        continue
                    db = dbs[0]
                    base = payload
                    kind = 'backup-zip' if zipfile.is_zipfile(staged_input) else 'db'
                for src in supplemental.rglob('*'):
                    if src.is_file():
                        target = base / src.relative_to(supplemental)
                        target.parent.mkdir(parents=True, exist_ok=True)
                        if not target.exists():
                            shutil.copy2(src, target)
                for run, _, sid in read_db(db, base):
                    files = files_from_legacy(run, base, sid)
                    source = {'file': path.name, 'kind': kind, 'dbVersion': run['dbVersion'], 'ingestedAt': int(time.time() * 1000)}
                    folder = archive_dir(root, run)
                    existed = folder.exists()
                    added = ingest_run(root, run, files, source)
                    if supplement_sources and any(locate_file(supplemental, sid, f.get('file_rel_path') or '')
                                                  for f in run['frames']):
                        for tar_name in supplement_sources:
                            ingest_run(root, run, {}, {'file': tar_name, 'kind': 'db',
                                'dbVersion': run['dbVersion'], 'ingestedAt': int(time.time() * 1000)})
                    counts['ingested' if not existed or added else 'skipped'] += 1
    build_index(root)
    return counts


def archive_runs(root):
    for category in ('安定前', '安定後'):
        base = root / category
        if base.is_dir():
            for folder in sorted(base.iterdir()):
                if (folder / 'run.json').is_file():
                    yield folder, load(folder / 'run.json')


def build_index(root, navi_import=None):
    result = []
    for folder, run in archive_runs(root):
        prov = load(folder / 'provenance.json')
        result.append({'runKey': re.match(r'^(\d{8}-\d{6}_\d+)_', folder.name).group(1), 'deviceModel': run.get('deviceModel'),
                       'era': folder.parent.name, 'distanceM': run.get('totalDistanceM'),
                       'gpsPoints': len(run.get('gps', [])), 'frames': len(run.get('frames', [])),
                       'sizeBytes': sum(p.stat().st_size for p in folder.rglob('*') if p.is_file()),
                       'firstSeenDbVersion': prov.get('firstSeenDbVersion')})
    # 既存の配列と各走行行を維持し、ナビの集計だけ独立した行にする。
    receipt_base = navi_base(root) / '受領票'
    receipts = list(receipt_base.rglob('*.json')) if receipt_base.is_dir() else []
    old = load(root / 'index.json') if (root / 'index.json').is_file() else []
    previous = next((row['navirun'] for row in old if isinstance(row, dict) and 'navirun' in row), {})
    if receipts or navi_import or previous:
        result.append({'navirun': {'archived': len(receipts),
                                   'app': sum(p.parent.name == 'アプリ版' for p in receipts),
                                   'browser': sum(p.parent.name == 'ブラウザ版' for p in receipts),
                                   'lastImport': {k: (navi_import or previous.get('lastImport', {})).get(k, 0)
                                                  for k in ('navi_archived', 'navi_duplicate', 'navi_held')}}})
    dump(root / 'index.json', result)
    return sum(row.get('sizeBytes', 0) for row in result)


def path_length_m(run):
    """GPS の軌跡の長さ（m）。記録の距離が空欄でも、走ったかどうかを確かめるため。"""
    import math
    pts = [(g.get('lat'), g.get('lon')) for g in sorted(run.get('gps', []), key=lambda g: (g.get('seq') or 0, g.get('id') or 0))
           if g.get('lat') is not None and g.get('lon') is not None]
    total = 0.0
    for (a1, o1), (a2, o2) in zip(pts, pts[1:]):
        x = math.radians(o2 - o1) * math.cos(math.radians((a1 + a2) / 2)) * 6371000
        y = math.radians(a2 - a1) * 6371000
        total += math.hypot(x, y)
    return total


def is_startup_test(run):
    """走っていない回＝記録の距離も GPS の軌跡も 300m 未満。"""
    return (run.get('totalDistanceM') or 0) < 300 and path_length_m(run) < 300


def make_receipt(root, device, out):
    runs = [(folder, run) for folder, run in archive_runs(root) if run.get('deviceModel') == device]
    # 「最新の版」＝その機種の走行が初めて記録された版のうち一番新しいもの（アプリの今の版ではない）。
    # 書き出しは今の版を名乗るので、過去のバックアップを先に取り込んでおくこと。
    max_version = max((load(folder / 'provenance.json').get('firstSeenDbVersion') or 0 for folder, _ in runs), default=0)
    now = int(time.time() * 1000)
    receipt = {'schema': RECEIPT_SCHEMA, 'issuedAt': now, 'runs': []}
    for folder, run in runs:
        prov = load(folder / 'provenance.json')
        version = prov.get('firstSeenDbVersion')
        # コマの実物が欠けている走行は、端末から書き出されたもの（端末も同じだけ欠けている）でなければ載せない。
        # 古いバックアップにしか無い欠けを「保管済み」と言うと、端末がまだ持っている映像を消させてしまう。
        if run.get('missingFiles') and not any(s.get('kind') == 'export' for s in prov.get('sources', [])):
            continue
        receipt['runs'].append({'startedAt': run['startedAt'], 'deviceModel': device,
                                'frames': len(run.get('frames', [])), 'archivedAt': now,
                                'startupTestDeletable': (is_startup_test(run) and
                                    version is not None and version < max_version)})
    dump(out, receipt)
    return receipt


# 窓（pythonw）から adb を呼ぶたびに黒い画面が出ないよう、Windows では子の画面を隠す。
HIDE = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
# adb の出力は端末の UTF-8。Windows の既定（cp932）で読むと日本語を含む目録で落ちる（2026-10-10 実測）。
TEXT = {'text': True, 'encoding': 'utf-8', 'errors': 'replace'}


def adb_call(adb, serial, *args):
    optional = args[:2] in (('shell', 'test'), ('shell', 'ls'), ('shell', 'du'),
                            ('shell', 'cat'), ('shell', 'getprop'), ('shell', 'pm'))
    return subprocess.run([adb, '-s', serial, *args], check=not optional, capture_output=True, **TEXT, **HIDE)


def device_bases(adb, serial):
    """archive_out を探す端末側の files ディレクトリ。本体（先頭）と、`ls /storage` で見つかる microSD など。
    microSD にある走行は、アプリが同じカードの Android/data/<pkg>/files/archive_out に書き出す（2026-10-20）。"""
    bases = [DEVICE_PATH]
    result = adb_call(adb, serial, 'shell', 'ls', '/storage')
    if result.returncode == 0:
        for name in result.stdout.split():
            if name not in SKIPPED_VOLUMES and re.fullmatch(r'[A-Za-z0-9_.-]+', name):
                bases.append('/storage/' + name + '/Android/data/' + APP_ID + '/files')
    return bases


def export_entries(adb, serial):
    """書き出された走行を (名前, その走行がある files ディレクトリ, 取り込めるか=DONE あり) で返す。
    同じ名前が複数の場所に見えても（端末によっては /storage の別名が本体を指す）、最初の1つだけ数える。"""
    entries, seen = [], set()
    for base in device_bases(adb, serial):
        result = adb_call(adb, serial, 'shell', 'ls', '-1', base + '/archive_out')
        if result.returncode:
            continue
        for name in result.stdout.splitlines():
            if name in seen or not re.fullmatch(r'\d{8}-\d{6}_\d+', name):
                continue
            seen.add(name)
            done = adb_call(adb, serial, 'shell', 'test', '-f', base + '/archive_out/' + name + '/DONE').returncode == 0
            entries.append((name, base, done))
    return entries


def export_list(adb, serial):
    entries = export_entries(adb, serial)
    return sorted(name for name, _, done in entries if done), sorted(name for name, _, done in entries if not done)


def navi_export_list(adb, serial):
    result = adb_call(adb, serial, 'shell', 'ls', '-1', DEVICE_PATH + '/navirun_out')
    if result.returncode:
        return []
    return sorted(name for name in result.stdout.splitlines() if re.fullmatch(r'[A-Za-z0-9._-]+', name)
                  and adb_call(adb, serial, 'shell', 'test', '-f', DEVICE_PATH + '/navirun_out/' + name + '/DONE').returncode == 0)


def choose_adb(explicit=None):
    # AirDroid Cast は使わない運用（2026-10-14 オーナー裁定）。常駐の adb（版40）と SDK の adb（版41）が
    # サーバーを取り合い、エミュレータの登録が失敗し OPPO の接続も切れたため。SDK の adb を正とする。
    sdk_adb = Path(os.environ.get('LOCALAPPDATA', '')) / 'Android' / 'Sdk' / 'platform-tools' / 'adb.exe'
    candidates = [explicit, os.environ.get('BUSCOURSE_ADB'), str(sdk_adb), 'adb']
    for candidate in candidates:
        if not candidate or (candidate.endswith('.exe') and not Path(candidate).is_file()):
            continue
        try:
            result = subprocess.run([candidate, 'devices'], capture_output=True, timeout=15, **TEXT, **HIDE)
        except (OSError, subprocess.TimeoutExpired):
            continue
        output = result.stdout + result.stderr
        if result.returncode == 0 and "server version doesn't match" not in output.lower() and 'List of devices attached' in output:
            return candidate, [line.split()[0] for line in result.stdout.splitlines()[1:]
                               if len(line.split()) >= 2 and line.split()[1] == 'device']
    raise RuntimeError('使える adb が見つかりません')


def discover(adb, serials, chooser=None):
    options = []
    for serial in serials:
        ready, pending = export_list(adb, serial)
        navi_ready = navi_export_list(adb, serial)
        installed = False
        if not ready and not pending and not navi_ready:
            result = adb_call(adb, serial, 'shell', 'pm', 'path', 'com.istech.buscourse')
            installed = result.returncode == 0 and 'package:' in result.stdout
        if ready or pending or navi_ready or installed:
            model = adb_call(adb, serial, 'shell', 'getprop', 'ro.product.model').stdout.strip() or '機種不明'
            options.append({'serial': serial, 'model': model, 'ready': ready, 'pending': pending, 'navi_ready': navi_ready})
    with_ready = [item for item in options if item['ready'] or item['navi_ready']]
    choices = with_ready or options
    if not choices:
        return None
    if len(choices) == 1:
        return choices[0]
    if chooser is None:
        raise ValueError('端末が複数あります')
    return chooser(choices)


def remote_size(adb, serial, name, directory='archive_out', base=None):
    # base を渡さないときは、本体と microSD の順に探す（ナビの走った跡は本体だけ）。
    bases = [base] if base else (device_bases(adb, serial) if directory == 'archive_out' else [DEVICE_PATH])
    for candidate in bases:
        if directory == 'archive_out':
            # 新形は archive_out の中身が小さな目録だけなので、du では移す大きさが出ない。source.json の合計を使う。
            total = remote_source_total(adb, serial, candidate + '/archive_out/' + name)
            if total is not None:
                return total
        result = adb_call(adb, serial, 'shell', 'du', '-sk', candidate + '/' + directory + '/' + name)
        try:
            if result.returncode == 0:
                return int(result.stdout.split()[0]) * 1024
        except (IndexError, ValueError):
            pass
    return 0


def parse_source(text):
    """新形の source.json を検めて返す。形が違えば ValueError。"""
    try:
        info = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError('source.json is not JSON') from exc
    if not isinstance(info, dict) or info.get('schema') != SOURCE_SCHEMA:
        raise ValueError('source.json schema')
    session, files, total = info.get('sessionDir'), info.get('files'), info.get('totalBytes')
    if (not isinstance(session, str) or not session.startswith('/') or any(c in session for c in '\r\n\0')
            or '..' in session.split('/')):
        raise ValueError('source.json sessionDir')
    if not isinstance(files, list) or len(set(files)) != len(files):
        raise ValueError('source.json files')
    for rel in files:
        if not isinstance(rel, str):
            raise ValueError('source.json files')
        if rel in SOURCE_EXTRAS:
            continue
        head, _, leaf = rel.partition('/')
        if head != 'frames' or leaf in ('', '.', '..') or '/' in leaf or '\\' in leaf:
            raise ValueError('source.json unsafe file ' + rel)
    if type(total) is not int or total < 0:
        raise ValueError('source.json totalBytes')
    if type(info.get('sessionId')) is not int:
        raise ValueError('source.json sessionId')
    return info


def check_binding(folder, info):
    """source.json が、この書き出しの走行を指していることを確かめる（別の走行の写真を取り込まない）。
    run.json の session.id と sessionId が一致し、sessionDir の末尾が /sessions/<sessionId> であること。違えば ValueError。"""
    session = load(folder / 'run.json').get('session')
    session_id = session.get('id') if isinstance(session, dict) else None
    if type(session_id) is not int or session_id != info['sessionId']:
        raise ValueError('source.json sessionId does not match run.json')
    if not info['sessionDir'].rstrip('/').endswith('/sessions/' + str(session_id)):
        raise ValueError('source.json sessionDir does not match the run')


def retry_identity(folder):
    """端末へ知らせるときの走行の見分け（startedAt と機種）。run.json が読めなければ None。"""
    try:
        run = load(folder / 'run.json')
        started = run['startedAt']
        if type(started) is not int:
            return None
        return {'startedAt': started, 'deviceModel': run.get('deviceModel') or ''}
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return None


def push_retry(adb, serial, stage, identities):
    """本体側 archive_retry.json に足して送る（既存の中身は残し、同じ走行は重ねない）。送れなくても取り込みは続ける。"""
    remote = DEVICE_PATH + '/archive_retry.json'
    rows = []
    result = adb_call(adb, serial, 'shell', 'cat', remote)
    if result.returncode == 0:
        try:
            old = json.loads(result.stdout)
            if old.get('schema') == RETRY_SCHEMA:
                rows = [row for row in old.get('runs', []) if isinstance(row, dict)]
        except (ValueError, AttributeError):
            pass
    for ident in identities:
        if not any(row.get('startedAt') == ident['startedAt'] and row.get('deviceModel') == ident['deviceModel'] for row in rows):
            rows.append(ident)
    path = stage / 'archive_retry.json'
    dump(path, {'schema': RETRY_SCHEMA, 'runs': rows})
    try:
        adb_call(adb, serial, 'push', str(path), remote)
    except subprocess.CalledProcessError:
        pass


def read_source(folder):
    """引き取った書き出しフォルダの source.json（新形）。旧形（無い）なら None。"""
    path = folder / 'source.json'
    if not path.is_file():
        return None
    return parse_source(path.read_text(encoding='utf-8'))


def remote_source_total(adb, serial, remote):
    """端末の書き出しフォルダが新形なら、移す元の合計バイト（source.json の totalBytes）。旧形・読めないときは None。"""
    result = adb_call(adb, serial, 'shell', 'cat', remote + '/source.json')
    if result.returncode != 0:
        return None
    try:
        return parse_source(result.stdout)['totalBytes']
    except ValueError:
        return None


def remote_run(adb, serial, name, base=DEVICE_PATH):
    result = adb_call(adb, serial, 'shell', 'cat', base + '/archive_out/' + name + '/run.json')
    if result.returncode == 0:
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError:
            pass
    return None


def tree_size(folder):
    total = 0
    if folder.is_file():
        try:
            return folder.stat().st_size
        except OSError:
            return 0
    if folder.exists():
        for path in folder.rglob('*'):
            try:
                if path.is_file():
                    total += path.stat().st_size
            except FileNotFoundError:
                pass
    return total


def discard(path):
    if path.is_dir() and not path.is_symlink():
        shutil.rmtree(path, ignore_errors=True)
    else:
        try:
            path.unlink()
        except OSError:
            pass


def pull_one(adb, serial, remote, stage, name, cancel, stall_seconds, dest=None):
    """remote を dest（既定は stage）の下へ name という名前で引き取る（adb pull は既存のフォルダの下に基本名で置く）。
    止まり見張り・取り消し・3回のやり直しはどの行き先でも同じ。"""
    parent = dest or stage
    folder = parent / name
    for _ in range(3):
        if cancel.is_set():
            return 'cancelled'
        discard(folder)
        process = subprocess.Popen([adb, '-s', serial, 'pull', remote, str(parent)], **HIDE,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        last_size, last_change, seen_activity = 0, time.monotonic(), False
        interval = max(0.01, stall_seconds / 4)
        pause = min(0.05, interval)
        while process.poll() is None:
            size = tree_size(folder)
            if size > last_size:
                last_size, last_change, seen_activity = size, time.monotonic(), True
            if cancel.is_set() or time.monotonic() - last_change >= (stall_seconds if seen_activity else max(1.0, stall_seconds)):
                if os.name == 'nt':
                    # .cmd で包んだ偽 adb も含め、この引き取りの子プロセスだけを止める。
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], **HIDE,
                                   capture_output=True, check=False)
                else:
                    process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait()
                break
            # 7,000 ほどのファイルを数え直すので、見張りは 2秒ごとで足りる（止まりの判定は 60秒）。
            # 小さな引き取り（記録の1ファイルなど）をすぐ終えられるよう、最初は短く見て、だんだん 2秒に伸ばす。
            time.sleep(pause)
            pause = min(pause * 2, 2.0, interval)
        if cancel.is_set():
            discard(folder)
            return 'cancelled'
        if process.returncode == 0 and folder.exists():
            return 'ok'
        discard(folder)
    return 'failed'


def pull_source(adb, serial, folder, info, cancel, stall_seconds):
    """新形の書き出し（folder は引き取り済みの archive_out/<名前>）の写真と記録を、source.json が指す元の場所から持ってくる。
    写真は sessions/<id>/frames をフォルダごと1回の adb pull（1枚ずつにしない）。元は読むだけで、端末のものは何も消さない。
    戻りは 'ok' / 'cancelled' / 'failed'。成功したら source.json を消し、files に無い写真は混ぜない。"""
    session = info['sessionDir'].rstrip('/')
    wanted = set(info['files'])
    stage = folder.parent
    if any(rel.startswith('frames/') for rel in wanted):
        outcome = pull_one(adb, serial, session + '/frames', stage, 'frames', cancel, stall_seconds, dest=folder)
        if outcome != 'ok':
            return outcome
    for rel in info['files']:
        if rel in SOURCE_EXTRAS:
            outcome = pull_one(adb, serial, session + '/' + rel, stage, rel, cancel, stall_seconds, dest=folder)
            if outcome != 'ok':
                return outcome
    frames = folder / 'frames'
    if frames.is_dir():
        # 元の frames の中の、目録（files）に無いファイル（記録の別の写真など）は取り込みに混ぜない。
        for path in sorted(frames.rglob('*'), key=lambda p: len(p.parts), reverse=True):
            rel = path.relative_to(folder).as_posix()
            if path.is_dir() and not path.is_symlink():
                try:
                    path.rmdir()
                except OSError:
                    pass
            elif rel not in wanted:
                path.unlink()
        if frames.is_dir() and not any(frames.iterdir()):
            frames.rmdir()
    (folder / 'source.json').unlink()
    return 'ok'


def log_pull(root, model, counts, elapsed):
    stamp = dt.datetime.now().astimezone().strftime('%Y-%m-%d %H:%M:%S')
    line = (f"{stamp}\t{model}\t道具 {BUILT_AT} 作成\t取り込んだ本数 {counts['ingested']}\t容量 {counts['bytes']}B"
            f"\t合わなかった本数 {counts['mismatch']}\t引き取れなかった本数 {counts['not_pulled']}"
            f"\t時間 {elapsed:.1f}秒\tナビの走った跡 入れた {counts['navi_archived']}"
            f"\t同じ中身 {counts['navi_duplicate']}\t保留 {counts['navi_held']}\n")
    with (root / '取り込みの記録.txt').open('a', encoding='utf-8') as out:
        out.write(line)


def pull(root, serial, adb, cancel=None, progress=None, stall_seconds=60, model='機種不明'):
    cancel = cancel or threading.Event()
    counts = {'ingested': 0, 'skipped': 0, 'mismatch': 0, 'not_pulled': 0, 'bytes': 0, 'cancelled': False}
    counts.update(navi_counts())
    started = time.monotonic()
    entries = export_entries(adb, serial)
    ready = sorted(name for name, _, done in entries if done)
    pending = sorted(name for name, _, done in entries if not done)
    bases = {name: base for name, base, _ in entries}
    navi_ready = navi_export_list(adb, serial)
    counts['skipped'] = len(pending)
    sizes = {name: remote_size(adb, serial, name, base=bases[name]) for name in ready}
    navi_sizes = {name: remote_size(adb, serial, name, 'navirun_out') for name in navi_ready}
    total_bytes = sum(sizes.values()) + sum(navi_sizes.values())
    def announce(number, name='', run=None):
        elapsed = time.monotonic() - started
        remaining = (elapsed * (total_bytes - counts['bytes']) / counts['bytes']) if counts['bytes'] else None
        if progress:
            progress({'total': len(ready) + len(navi_ready), 'total_bytes': total_bytes, 'number': number,
                      'ingested': counts['ingested'] + counts['navi_archived'] + counts['navi_duplicate'],
                      'ingested_bytes': counts['bytes'], 'current': name,
                      'startedAt': run.get('startedAt') if run else None,
                      'distanceM': run.get('totalDistanceM') if run else None,
                      'remaining_seconds': max(0, remaining) if remaining is not None else None})
    announce(0)
    try:
        with tempfile.TemporaryDirectory(dir=pull_stage_dir(root)) as temp:
            stage = Path(temp)
            retry = []
            for number, name in enumerate(ready, 1):
                if cancel.is_set():
                    counts['cancelled'] = True
                    break
                remote = bases[name] + '/archive_out/' + name
                announce(number, name, remote_run(adb, serial, name, bases[name]))
                outcome = pull_one(adb, serial, remote, stage, name, cancel, stall_seconds)
                if outcome == 'cancelled':
                    counts['cancelled'] = True
                    break
                if outcome == 'failed':
                    counts['not_pulled'] += 1
                    continue
                folder = stage / name
                new_form, ident = False, None
                try:
                    if cancel.is_set():
                        counts['cancelled'] = True
                        break
                    # 新形（source.json あり）は、端末が写真を写していない。元の場所から持ってきて、source.json を消す。
                    # 合わなかった新形は、端末へ知らせる（archive_retry.json）。端末は新形の書き出しと指紋の作り置きを消し、
                    # 書き出し待ちに戻す（microSD の Android/data は adb から消せない端末があるため、消すのはアプリに任せる）。
                    new_form = (folder / 'source.json').is_file()
                    ident = retry_identity(folder) if new_form else None
                    info = read_source(folder)
                    if info is not None:
                        check_binding(folder, info)
                        sourced = pull_source(adb, serial, folder, info, cancel, stall_seconds)
                        if sourced == 'cancelled':
                            counts['cancelled'] = True
                            break
                        if sourced == 'failed':
                            # 元が無い・引き取れない。何も取り込まず、端末のものは何も消さない（次の取り込みでやり直し）。
                            counts['not_pulled'] += 1
                            if ident:
                                retry.append(ident)
                            continue
                    hashes = verified_hashes(folder)
                    if hashes is None:
                        counts['mismatch'] += 1
                        if new_form and ident:
                            retry.append(ident)
                        continue
                    if cancel.is_set():
                        counts['cancelled'] = True
                        break
                    run = load(folder / 'run.json')
                    if run.get('schema') != SCHEMA:
                        counts['mismatch'] += 1
                        if new_form and ident:
                            retry.append(ident)
                        continue
                    announce(number, name, run)
                    files = {p.relative_to(folder).as_posix(): p for p in folder.rglob('*')
                             if p.is_file() and p.name not in ('run.json', 'manifest.sha256', 'DONE')}
                    source = {'file': name, 'kind': 'export', 'dbVersion': run.get('dbVersion'), 'ingestedAt': int(time.time() * 1000)}
                    existed = archive_dir(root, run, name).exists()
                    added = ingest_run(root, run, files, source, name, hashes)
                    receipt_path = stage / 'archive_receipt.json'
                    make_receipt(root, run.get('deviceModel', ''), receipt_path)
                    adb_call(adb, serial, 'push', str(receipt_path), DEVICE_PATH + '/archive_receipt.json')
                    # 写しの片付けは任せる側がある: microSD の Android/data は adb（shell）から消せない端末がある
                    #   （OPPO で実測 2026-10-21）。消せなくても、受領票を見たアプリが受領済みの写しを消す。
                    try:
                        adb_call(adb, serial, 'shell', 'rm', '-r', remote)
                    except subprocess.CalledProcessError:
                        pass
                    counts['ingested'] += 1
                    counts['bytes'] += sizes[name] or tree_size(folder)
                    announce(number, name, run)
                except (ValueError, KeyError, OSError, json.JSONDecodeError):
                    counts['mismatch'] += 1
                    if new_form and ident:
                        retry.append(ident)
                finally:
                    shutil.rmtree(folder, ignore_errors=True)
            if retry:
                push_retry(adb, serial, stage, retry)
            if not cancel.is_set():
                for navi_number, name in enumerate(navi_ready, len(ready) + 1):
                    remote = DEVICE_PATH + '/navirun_out/' + name
                    announce(navi_number, name)
                    outcome = pull_one(adb, serial, remote, stage, name, cancel, stall_seconds)
                    if outcome == 'cancelled':
                        counts['cancelled'] = True; break
                    if outcome == 'failed':
                        counts['not_pulled'] += 1; continue
                    folder = stage / name
                    try:
                        source = None
                        try:
                            source = load(folder / 'candidate-meta.json').get('source')
                        except (OSError, ValueError, AttributeError):
                            pass
                        result = ingest_navi_package(root, folder, model)
                        counts['navi_' + result] += 1
                        if result == 'archived':
                            counts['navi_' + source + '_archived'] += 1
                        if result != 'held':
                            adb_call(adb, serial, 'shell', 'rm', '-r', remote)
                            counts['bytes'] += navi_sizes[name] or tree_size(folder)
                        announce(navi_number, name)
                    finally:
                        shutil.rmtree(folder, ignore_errors=True)
                receipt_path = stage / 'navirun_receipt.json'
                make_navi_receipt(root, model, receipt_path)
                adb_call(adb, serial, 'push', str(receipt_path), DEVICE_PATH + '/navirun_receipt.json')
    finally:
        build_index(root, counts)
        log_pull(root, model, counts, time.monotonic() - started)
    return counts


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path)
    parser.add_argument('--selftest', action='store_true')
    sub = parser.add_subparsers(dest='command')
    sub.add_parser('ingest-legacy').add_argument('files', nargs='+')
    p = sub.add_parser('pull'); p.add_argument('--serial'); p.add_argument('--adb')
    p = sub.add_parser('receipt'); p.add_argument('--device', required=True); p.add_argument('--out', type=Path, required=True)
    sub.add_parser('index')
    sub.add_parser('ingest-folder').add_argument('path', type=Path)
    args = parser.parse_args(argv)
    if args.selftest:
        import unittest
        suite = unittest.defaultTestLoader.discover(str(Path(__file__).parent), pattern='test_buscourse_archive.py')
        result = unittest.TextTestRunner(verbosity=1).run(suite)
        print(f'Python selftest: {result.testsRun}件・失敗 {len(result.failures) + len(result.errors)}件')
        return 0 if result.wasSuccessful() else 1
    parser.error('--root とコマンドが必要です') if args.root is None or args.command is None else None
    args.root.mkdir(parents=True, exist_ok=True)
    for category in ('安定前', '安定後'):
        (args.root / category).mkdir(exist_ok=True)
    if args.command == 'ingest-legacy':
        counts = ingest_legacy(args.root, args.files)
    elif args.command == 'ingest-folder':
        counts = {'ingested': 0, 'skipped': 0, 'mismatch': 0}
        counts.update(ingest_folder(args.root, args.path))
    elif args.command == 'pull':
        adb, serials = choose_adb(args.adb)
        if args.serial:
            serial = args.serial
            model = adb_call(adb, serial, 'shell', 'getprop', 'ro.product.model').stdout.strip() or '機種不明'
        else:
            selected = discover(adb, serials)
            if selected is None:
                raise RuntimeError('書き出しのある端末が見つかりません')
            serial, model = selected['serial'], selected['model']
        counts = pull(args.root, serial, adb, model=model)
    elif args.command == 'receipt':
        make_receipt(args.root, args.device, args.out); counts = {'ingested': 0, 'skipped': 0, 'mismatch': 0}
    else:
        build_index(args.root); counts = {'ingested': 0, 'skipped': 0, 'mismatch': 0}
    total = build_index(args.root)
    print(f"取り込んだ数 {counts['ingested']}・飛ばした数 {counts['skipped']}・合わなかった数 {counts['mismatch']}・"
          f"ナビの走った跡 入れた {counts.get('navi_archived', 0)}・同じ中身 {counts.get('navi_duplicate', 0)}・"
          f"保留 {counts.get('navi_held', 0)}・保管庫の合計 {total}B")
    return 0


if __name__ == '__main__':
    sys.exit(main())
