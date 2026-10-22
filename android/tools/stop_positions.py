#!/usr/bin/env python3
"""PlannerEX の停留所マスタから「位置だけ」を抜いて、BusCourse アプリ用の stop_positions.json を作る。Python 標準ライブラリのみ。

運行の記録の時刻から停留所を足す（委託指示書 L・2026-10-22）で、アプリは「停まったのにマークが無い所」の候補を
停留所の位置の近くに絞る。そのための位置だけを渡す道具。**名前・番号・人数は出力に入れない**
（オーナー裁定 2026-10-22「7月の停留所の位置だけをアプリに持たせてよい（名前は入れない）」）。

  python stop_positions.py <stop_master.json> [-o stop_positions.json] [--push <serial>] [--app-id com.istech.buscourse]

出力の形: {"schema":"stop-positions/1","generatedAt":<ms>,"points":[{"lat":..,"lon":..},…]}
アプリ側の置き場所: /sdcard/Android/data/<app-id>/files/stop_positions.json（getExternalFilesDir(null)）。
入力（マスタ）は読むだけで書き換えない。
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time

# この道具は版数を持たないので、作った日時を焼き込んで画面に出す（新しい・古いを見分けるため。
# オーナー裁定 2026-10-10）。直したら必ずこの日時も書き換える。
BUILT_AT = '2026-10-22 22:10'
SCHEMA = 'stop-positions/1'
# 位置の桁（小数第4位＝およそ 10m）。オーナー裁定 2026-10-22「桁を落とす」: 1軒の家の前を指す高精度の位置にしない。
#   アプリが候補を絞る距離は 30m なので、10m に丸めても絞り込みは変わらない。
DEFAULT_DECIMALS = 4
FILE_NAME = 'stop_positions.json'
# 記録用の実機に入る側（実データを持つ field ビルド）が既定。debug ビルドへ置くときは --app-id で指す。
DEFAULT_APP_ID = 'com.istech.buscourse'
HIDE = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
TEXT = {'text': True, 'encoding': 'utf-8', 'errors': 'replace'}


def device_dir(app_id):
    return '/sdcard/Android/data/' + app_id + '/files'


def _records(data):
    """マスタの形（記録の配列／配列を持つ辞書／id をキーにした辞書）を問わず、1停留所ぶんの辞書を取り出す。"""
    if isinstance(data, list):
        return [r for r in data if isinstance(r, dict)]
    if isinstance(data, dict):
        lists = [v for v in data.values() if isinstance(v, list)]
        if lists:
            return [r for v in lists for r in v if isinstance(r, dict)]
        return [v for v in data.values() if isinstance(v, dict)]
    raise ValueError('マスタの形が読めません（配列か辞書を想定）')


def _number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def extract_points(master, decimals=DEFAULT_DECIMALS):
    """(points, skipped)。points は [{'lat':..,'lon':..}]。`lat`/`lon` が数でない・範囲外の記録は数えて読み飛ばす。
    **名前など他のキーは見ない・持ち出さない。** 位置は小数第 [decimals] 位に丸め、丸めて同じになった点は1つにする。"""
    points, skipped, seen = [], 0, set()
    for record in _records(master):
        lat, lon = _number(record.get('lat')), _number(record.get('lon'))
        if lat is None or lon is None or not -90.0 <= lat <= 90.0 or not -180.0 <= lon <= 180.0:
            skipped += 1
            continue
        lat, lon = round(lat, decimals), round(lon, decimals)
        if (lat, lon) in seen:
            continue
        seen.add((lat, lon))
        points.append({'lat': lat, 'lon': lon})
    return points, skipped


def build_document(points, now_ms=None):
    return {'schema': SCHEMA, 'generatedAt': int(time.time() * 1000) if now_ms is None else now_ms, 'points': points}


def write_document(path, document):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(document, ensure_ascii=False, separators=(',', ':')) + '\n'
    path.write_text(data, encoding='utf-8', newline='\n')
    return path


def choose_adb(explicit=None):
    # SDK の adb を正とする（常駐の別版 adb とサーバーを取り合う事故があったため。buscourse_archive.py と同じ扱い）。
    sdk_adb = Path(os.environ.get('LOCALAPPDATA', '')) / 'Android' / 'Sdk' / 'platform-tools' / 'adb.exe'
    for candidate in (explicit, os.environ.get('BUSCOURSE_ADB'), str(sdk_adb), 'adb'):
        if not candidate or (candidate.endswith('.exe') and not Path(candidate).is_file()):
            continue
        try:
            result = subprocess.run([candidate, 'version'], capture_output=True, timeout=15, **TEXT, **HIDE)
        except (OSError, subprocess.TimeoutExpired):
            continue
        if result.returncode == 0:
            return candidate
    raise RuntimeError('使える adb が見つかりません')


def push(adb, serial, local_file, app_id):
    """端末の files ディレクトリへ置き、置けたことを SHA-256 で確かめる。失敗は RuntimeError。"""
    directory, target = device_dir(app_id), device_dir(app_id) + '/' + FILE_NAME

    def run(*args):
        return subprocess.run([adb, '-s', serial, *args], capture_output=True, **TEXT, **HIDE)

    made = run('shell', 'mkdir', '-p', directory)
    if made.returncode != 0:
        raise RuntimeError('端末のフォルダを用意できません: ' + (made.stderr or made.stdout).strip())
    pushed = run('push', str(local_file), target)
    if pushed.returncode != 0:
        raise RuntimeError('adb push に失敗しました: ' + (pushed.stderr or pushed.stdout).strip())
    expected = hashlib.sha256(Path(local_file).read_bytes()).hexdigest()
    check = run('shell', 'sha256sum', target)
    reported = check.stdout.split()[0] if check.returncode == 0 and check.stdout.split() else None
    if reported is None:
        raise RuntimeError('置いたあとの照合ができません（端末で sha256sum が使えない）')
    if reported != expected:
        raise RuntimeError('置いたファイルが一致しません（端末 %s / 手元 %s）' % (reported, expected))
    return target


def main(argv=None):
    parser = argparse.ArgumentParser(description='停留所マスタから位置だけを抜いて stop_positions.json を作る')
    parser.add_argument('master', help='PlannerEX の stop_master.json（読むだけ）')
    parser.add_argument('-o', '--output', default=FILE_NAME, help='書き出し先（既定: ./%s）' % FILE_NAME)
    parser.add_argument('--push', metavar='SERIAL', help='書き出したあと、この端末へ adb push する')
    parser.add_argument('--app-id', default=DEFAULT_APP_ID, help='置き先のアプリ ID（既定: %s）' % DEFAULT_APP_ID)
    parser.add_argument('--adb', help='adb のパス（省略時は SDK の adb）')
    parser.add_argument('--decimals', type=int, default=DEFAULT_DECIMALS, choices=range(3, 7),
                        help='位置の小数の桁（既定: %d＝およそ 10m）' % DEFAULT_DECIMALS)
    args = parser.parse_args(argv)
    print('stop_positions.py（作成 %s）' % BUILT_AT)

    try:
        master = json.loads(Path(args.master).read_text(encoding='utf-8-sig'))
        points, skipped = extract_points(master, args.decimals)
    except (OSError, ValueError) as exc:
        # 入力の中身（名前など）を出さないよう、例外の種類だけを言う。
        print('マスタを読めません（%s）' % type(exc).__name__, file=sys.stderr)
        return 2
    if not points:
        print('位置を1つも取り出せませんでした（読み飛ばし %d 件）。マスタの形を確かめてください' % skipped, file=sys.stderr)
        return 2
    out = write_document(args.output, build_document(points))
    print('書き出し: %s（%d 点・読み飛ばし %d 件）' % (out, len(points), skipped))

    if args.push:
        try:
            target = push(choose_adb(args.adb), args.push, out, args.app_id)
        except RuntimeError as exc:
            print(str(exc), file=sys.stderr)
            return 3
        print('端末へ置きました: %s' % target)
    return 0


if __name__ == '__main__':
    sys.exit(main())
